/*
 * Copyright 2014 - 2015 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package slamdata.engine

import slamdata.Predef._
import slamdata.recursionschemes._, Recursive.ops._
import slamdata.fp._
import slamdata.engine.analysis._, SemanticAnalysis._, SemanticError._
import slamdata.engine.fs.Path
import slamdata.engine.sql._
import slamdata.engine.std.StdLib._

import org.threeten.bp.{Instant, LocalDate, LocalTime, Duration}
import scalaz.{Tree => _, _}, Scalaz._

trait Compiler[F[_]] {
  import identity._
  import relations._
  import set._
  import string._
  import structural._

  import SemanticAnalysis.Annotations

  // HELPERS
  private type M[A] = EitherT[F, SemanticError, A]

  private type CompilerM[A] = StateT[M, CompilerState, A]

  private def typeOf(node: Expr)(implicit m: Monad[F]): CompilerM[Type] = attr(node).map(_._2)

  private def provenanceOf(node: Expr)(implicit m: Monad[F]): CompilerM[Provenance] = attr(node).map(_._1._1._2)

  private def syntheticOf(node: Expr)(implicit m: Monad[F]): CompilerM[List[Option[Synthetic]]] = attr(node).map(_._1._1._1)

  private def funcOf(node: Expr)(implicit m: Monad[F]): CompilerM[Func] = for {
    funcOpt <- attr(node).map(_._1._2)
    rez     <- funcOpt.map(emit _).getOrElse(fail(FunctionNotBound(node)))
  } yield rez

  private final case class TableContext(
    root: Option[Fix[LogicalPlan]],
    full: () => Fix[LogicalPlan],
    subtables: Map[String, Fix[LogicalPlan]]) {
    def ++(that: TableContext): TableContext =
      TableContext(
        None,
        () => LogicalPlan.Invoke(ObjectConcat, List(this.full(), that.full())),
        this.subtables ++ that.subtables)
  }

  private final case class Grouped(src: Fix[LogicalPlan], keys: Set[Fix[LogicalPlan]])

  private final case class CompilerState(
    tree:         AnnotatedTree[Expr, Annotations],
    fields:       List[String],
    tableContext: List[TableContext],
    nameGen:      Int,
    grouped:      Option[Grouped])

  private object CompilerState {
    /**
     * Runs a computation inside a table context, which contains compilation
     * data for the tables in scope.
     */
    def contextual[A](t: TableContext)(f: CompilerM[A])(implicit m: Monad[F]):
        CompilerM[A] = for {
      _ <- mod((s: CompilerState) => s.copy(tableContext = t :: s.tableContext))
      a <- f
      _ <- mod((s: CompilerState) => s.copy(tableContext = s.tableContext.drop(1)))
    } yield a

    def addFields[A](add: List[String])(f: CompilerM[A])(implicit m: Monad[F]):
        CompilerM[A] =
      for {
        curr <- read[CompilerState, List[String]](_.fields)
        _    <- mod((s: CompilerState) => s.copy(fields = curr ++ add))
        a <- f
      } yield a

    def fields(implicit m: Monad[F]): CompilerM[List[String]] =
      read[CompilerState, List[String]](_.fields)

    def rootTable(implicit m: Monad[F]): CompilerM[Option[Fix[LogicalPlan]]] =
      read[CompilerState, Option[Fix[LogicalPlan]]](_.tableContext.headOption.flatMap(_.root))

    def rootTableReq(implicit m: Monad[F]): CompilerM[Fix[LogicalPlan]] = {
      this.rootTable flatMap {
        case Some(t)  => emit(t)
        case None     => fail(CompiledTableMissing)
      }
    }

    def subtable(name: String)(implicit m: Monad[F]):
        CompilerM[Option[Fix[LogicalPlan]]] =
      read[CompilerState, Option[Fix[LogicalPlan]]](_.tableContext.headOption.flatMap(_.subtables.get(name)))

    def subtableReq(name: String)(implicit m: Monad[F]):
        CompilerM[Fix[LogicalPlan]] =
      subtable(name) flatMap {
        case Some(t) => emit(t)
        case None    => fail(CompiledSubtableMissing(name))
      }

    def fullTable(implicit m: Monad[F]): CompilerM[Option[Fix[LogicalPlan]]] =
      read[CompilerState, Option[Fix[LogicalPlan]]](_.tableContext.headOption.map(_.full()))

    def fullTableReq(implicit m: Monad[F]): CompilerM[Fix[LogicalPlan]] =
      fullTable flatMap {
        case Some(t) => emit(t)
        case None    => fail(CompiledTableMissing)
      }

    /**
     * Generates a fresh name for use as an identifier, e.g. tmp321.
     */
    def freshName(prefix: String)(implicit m: Monad[F]): CompilerM[Symbol] =
      for {
        num <- read[CompilerState, Int](_.nameGen)
        _   <- mod((s: CompilerState) => s.copy(nameGen = s.nameGen + 1))
      } yield Symbol(prefix + num.toString)

    def grouped(implicit m: Monad[F]): CompilerM[Option[Grouped]] =
      read[CompilerState, Option[Grouped]](_.grouped)

    def addGrouped(grouped: Option[Grouped])(implicit m: Monad[F]): CompilerM[Unit] =
      mod(_.copy(grouped = grouped))
  }

  sealed trait JoinDir
  final case object Left extends JoinDir {
    override def toString: String = "left"
  }
  final case object Right extends JoinDir {
    override def toString: String = "right"
  }

  private def read[A, B](f: A => B)(implicit m: Monad[F]): StateT[M, A, B] =
    StateT((s: A) => Applicative[M].point((s, f(s))))

  private def attr(node: Expr)(implicit m: Monad[F]): CompilerM[Annotations] =
    read(s => s.tree.attr(node))

  private def tree(implicit m: Monad[F]): CompilerM[AnnotatedTree[Expr, Annotations]] =
    read(s => s.tree)

  private def fail[A](error: SemanticError)(implicit m: Monad[F]):
      CompilerM[A] =
    lift(-\/(error))

  private def emit[A](value: A)(implicit m: Monad[F]): CompilerM[A] =
    lift(\/-(value))

  private def lift[A](v: SemanticError \/ A)(implicit m: Monad[F]): CompilerM[A] =
    StateT[M, CompilerState, A]((s: CompilerState) =>
      EitherT.eitherT(Applicative[F].point(v.map(s -> _))))

  private def whatif[S, A](f: StateT[M, S, A])(implicit m: Monad[F]):
      StateT[M, S, A] =
    for {
      oldState <- read((s: S) => s)
      rez      <- f.imap(κ(oldState))
    } yield rez

  private def mod(f: CompilerState => CompilerState)(implicit m: Monad[F]):
      CompilerM[Unit] =
    StateT[M, CompilerState, Unit](s => Applicative[M].point((f(s), ())))

  private def invoke(func: Func, args: List[Expr])(implicit m: Monad[F]): StateT[M, CompilerState, Fix[LogicalPlan]] =
    for {
      args <- args.map(compile0).sequenceU
    } yield func.apply(args: _*)

  // CORE COMPILER
  private def compile0(node: Expr)(implicit M: Monad[F]):
      CompilerM[Fix[LogicalPlan]] = {
    def compileCases(cases: List[Case[Expr]], default: Fix[LogicalPlan])(f: Case[Expr] => CompilerM[(Fix[LogicalPlan], Fix[LogicalPlan])]) =
      for {
        cases   <- cases.map(f).sequenceU
      } yield cases.foldRight(default) {
        case ((cond, expr), default) =>
          LogicalPlan.Invoke(relations.Cond, cond :: expr :: default :: Nil)
      }

    def regexForLikePattern(pattern: String, escapeChar: Option[Char]):
        String = {
      def sansEscape(pat: List[Char]): List[Char] = pat match {
        case '_' :: t =>         '.' +: escape(t)
        case '%' :: t => ".*".toList ++ escape(t)
        case c :: t   =>
          if ("\\^$.|?*+()[{".contains(c))
            '\\' +: escape(t)
          else c +: escape(t)
        case Nil      => Nil
      }

      def escape(pat: List[Char]): List[Char] =
        escapeChar match {
          case None => sansEscape(pat)
          case Some(esc) =>
            pat match {
              // NB: We only handle the escape char when it’s before a special
              //     char, otherwise you run into weird behavior when the escape
              //     char _is_ a special char. Will change if someone can find
              //     an actual definition of SQL’s semantics.
              case `esc` :: '%' :: t => '%' +: escape(t)
              case `esc` :: '_' :: t => '_' +: escape(t)
              case l                 => sansEscape(l)
            }
        }
      "^" + escape(pattern.toList).mkString + "$"
    }

    def flattenJoins(term: Fix[LogicalPlan], relations: SqlRelation[Expr]):
        Fix[LogicalPlan] = relations match {
      case _: NamedRelation[_] => term
      case JoinRelation(left, right, _, _) =>
        LogicalPlan.Invoke(ObjectConcat,
          List(
            flattenJoins(LogicalPlan.Invoke(ObjectProject, List(term, LogicalPlan.Constant(Data.Str("left")))), left),
            flattenJoins(LogicalPlan.Invoke(ObjectProject, List(term, LogicalPlan.Constant(Data.Str("right")))), right)))
    }

    def buildJoinDirectionMap(relations: SqlRelation[Expr]): Map[String, List[JoinDir]] = {
      def loop(rel: SqlRelation[Expr], acc: List[JoinDir]):
          Map[String, List[JoinDir]] = rel match {
        case t: NamedRelation[_] => Map(t.aliasName -> acc)
        case JoinRelation(left, right, tpe, clause) =>
          loop(left, Left :: acc) ++ loop(right, Right :: acc)
      }

      loop(relations, Nil)
    }

    def compileTableRefs(joined: Fix[LogicalPlan], relations: SqlRelation[Expr]): Map[String, Fix[LogicalPlan]] = {
      buildJoinDirectionMap(relations).map {
        case (name, dirs) =>
          name -> dirs.foldRight(joined) {
            case (dir, acc) =>
              LogicalPlan.Invoke(
                ObjectProject,
                acc :: LogicalPlan.Constant(Data.Str(dir.toString)) :: Nil)
          }
      }
    }

    def tableContext(joined: Fix[LogicalPlan], relations: SqlRelation[Expr]): TableContext =
      TableContext(
        Some(joined),
        () => flattenJoins(joined, relations),
        compileTableRefs(joined, relations))

    def step(relations: SqlRelation[Expr]):
        (Option[CompilerM[Fix[LogicalPlan]]] =>
          CompilerM[Fix[LogicalPlan]] =>
          CompilerM[Fix[LogicalPlan]]) = {
      (current: Option[CompilerM[Fix[LogicalPlan]]]) =>
      (next: CompilerM[Fix[LogicalPlan]]) =>
      current.map { current =>
        for {
          stepName <- CompilerState.freshName("tmp")
          current  <- current
          next2    <- CompilerState.contextual(tableContext(LogicalPlan.Free(stepName), relations))(next)
        } yield LogicalPlan.Let(stepName, current, next2)
      }.getOrElse(next)
    }

    def relationName(node: Expr): CompilerM[SemanticError \/ String] = {
      for {
        prov <- provenanceOf(node)

        namedRel = prov.namedRelations
        relations =
          if (namedRel.size <= 1) namedRel
          else {
            val filtered = namedRel.filter(x => Path(x._1).filename == pprint(node))
            if (filtered.isEmpty) namedRel else filtered
          }
      } yield relations.toList match {
        case Nil             => -\/ (NoTableDefined(node))
        case List((name, _)) =>  \/-(name)
        case x               => -\/ (AmbiguousReference(node, x.map(_._2).join))
      }
    }

    def compileFunction(func: Func, args: List[Expr]): CompilerM[Fix[LogicalPlan]] = for {
      args <- args.map(compile0).sequenceU
    } yield func.apply(args: _*)

    def buildRecord(names: List[Option[String]], values: List[Fix[LogicalPlan]]): Fix[LogicalPlan] = {
      val fields = names.zip(values).map {
        case (Some(name), value) =>
          LogicalPlan.Invoke(MakeObject,
            List(LogicalPlan.Constant(Data.Str(name)), value))
        case (None, value) => value
      }

      // TODO: If we had an optimization pass that included eliding an
      //       ObjectConcat with an empty map on one side, this could be done
      //       in a single foldLeft.
      fields match {
        case Nil => LogicalPlan.Constant(Data.Obj(Map()))
        case x :: xs =>
          NonEmptyList.nel(x, xs).foldLeft1((a, b) =>
            LogicalPlan.Invoke(ObjectConcat, a :: b :: Nil))
      }
    }

    def compileRelation(r: SqlRelation[Expr]): CompilerM[Fix[LogicalPlan]] =
      r match {
        case TableRelationAST(name, _) => emit(LogicalPlan.Read(Path(name)))

        case ExprRelationAST(expr, _) => compile0(expr)

        case JoinRelation(left, right, tpe, clause) =>
          for {
            leftName <- CompilerState.freshName("left")
            rightName <- CompilerState.freshName("right")
            leftFree = LogicalPlan.Free(leftName)
            rightFree = LogicalPlan.Free(rightName)
            left0 <- compileRelation(left)
            right0 <- compileRelation(right)
            join <- CompilerState.contextual(
              tableContext(leftFree, left) ++ tableContext(rightFree, right))(
              compile0(clause).map(c =>
                LogicalPlan.Invoke(
                  tpe match {
                    case LeftJoin  => LeftOuterJoin
                    case sql.InnerJoin => InnerJoin
                    case RightJoin => RightOuterJoin
                    case FullJoin  => FullOuterJoin
                  },
                  List(leftFree, rightFree, c))))
          } yield LogicalPlan.Let(leftName, left0,
            LogicalPlan.Let(rightName, right0, join))
      }

    typeOf(node).flatMap(_ match {
      case Type.Const(data) => emit(LogicalPlan.Constant(data))
      case _ =>
        val nodeLP: CompilerM[Fix[LogicalPlan]] = node match {
          case s @ Select(isDistinct, projections, relations, filter, groupBy, orderBy, limit, offset) =>
            /*
             * 1. Joins, crosses, subselects (FROM)
             * 2. Filter (WHERE)
             * 3. Group by (GROUP BY)
             * 4. Filter (HAVING)
             * 5. Select (SELECT)
             * 6. Squash
             * 7. Sort (ORDER BY)
             * 8. Distinct (DISTINCT/DISTINCT BY)
             * 9. Drop (OFFSET)
             * 10. Take (LIMIT)
             * 11. Prune synthetic fields
             */

            // Selection of wildcards aren't named, we merge them into any other
            // objects created from other columns:
            val names: CompilerM[List[Option[String]]] =
              relationName(node).map(x =>
                namedProjections(s, x.toOption.map(Path(_).filename)).map {
                  case (_,    Splice(_)) => None
                  case (name, _)         => Some(name)
                })
            val projs = projections.map(_.expr)

            val syntheticNames: CompilerM[List[String]] =
              (names |@| syntheticOf(s))((names, synth) =>
                (names zip synth).flatMap {
                  case (Some(name), Some(_)) => List(name)
                  case (_   ,       _)       => Nil
                })

            relations match {
              case None => for {
                names <- names
                projs <- projs.map(compile0).sequenceU
              } yield buildRecord(names, projs)
              case Some(relations) => {
                val stepBuilder = step(relations)
                stepBuilder(Some(compileRelation(relations))) {
                  val filtered = filter map { filter =>
                    (CompilerState.rootTableReq |@| compile0(filter))(Filter(_, _))
                  }

                  stepBuilder(filtered) {
                    val grouped = groupBy map { groupBy =>
                      for {
                        src  <- CompilerState.rootTableReq
                        keys <- groupBy.keys.map(compile0).sequenceU
                        _    <- CompilerState.addGrouped(Some(Grouped(src, keys.toSet)))
                      } yield GroupBy(src, MakeArrayN(keys: _*))
                    }

                    stepBuilder(grouped) {
                      val having = groupBy.flatMap(_.having) map { having =>
                        (CompilerState.rootTableReq |@| compile0(having))(
                          Filter(_, _))
                      }

                      stepBuilder(having) {
                        val select = Some {
                          for {
                            names <- names
                            t     <- CompilerState.rootTableReq
                            projs <- projs.map(compile0).sequenceU
                            _     <- CompilerState.addGrouped(None)
                          } yield buildRecord(
                            names,
                            projs.map(p => p.unFix match {
                              case LogicalPlan.ConstantF(_) => Constantly(p, t)
                              case _                        => p
                            }))
                        }

                        stepBuilder(select) {
                          val squashed = Some(for {
                            t <- CompilerState.rootTableReq
                          } yield Squash(t))

                          stepBuilder(squashed) {
                            val sort = orderBy map { orderBy =>
                              for {
                                t <- CompilerState.rootTableReq
                                names <- names
                                flat = names.flatten
                                keys <- CompilerState.addFields(flat)(orderBy.keys.map { case (_, key) => compile0(key) }.sequenceU)
                                orders = orderBy.keys.map { case (order, _) => LogicalPlan.Constant(Data.Str(order.toString)) }
                              } yield OrderBy(t, MakeArrayN(keys: _*), MakeArrayN(orders: _*))
                            }

                            stepBuilder(sort) {
                              val distincted = isDistinct match {
                                  case SelectDistinct => Some {
                                    for {
                                      ns <- syntheticNames
                                      t <- CompilerState.rootTableReq
                                    } yield if (ns.nonEmpty)
                                      DistinctBy(t, ns.foldLeft(t)((acc, field) => DeleteField(acc, LogicalPlan.Constant(Data.Str(field)))))
                                    else Distinct(t)
                                  }
                                  case _ => None
                                }

                              stepBuilder(distincted) {
                                val drop = offset map { offset =>
                                  for {
                                    t <- CompilerState.rootTableReq
                                  } yield Drop(t, LogicalPlan.Constant(Data.Int(offset)))
                                }

                                stepBuilder(drop) {
                                  val limited = limit map { limit =>
                                    for {
                                      t <- CompilerState.rootTableReq
                                    } yield Take(t, LogicalPlan.Constant(Data.Int(limit)))
                                  }

                                  stepBuilder(limited) {
                                    val pruned = for {
                                      ns <- syntheticNames
                                    } yield if (ns.nonEmpty)
                                      Some(CompilerState.rootTableReq.map(
                                        ns.foldLeft(_)((acc, field) =>
                                          DeleteField(acc,
                                            LogicalPlan.Constant(Data.Str(field))))))
                                    else None

                                    pruned.flatMap(stepBuilder(_) {
                                      CompilerState.rootTableReq
                                    })
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }

          case SetLiteral(values0) =>
            val values = (values0.map {
              case IntLiteral(v) => emit[Data](Data.Int(v))
              case FloatLiteral(v) => emit[Data](Data.Dec(v))
              case StringLiteral(v) => emit[Data](Data.Str(v))
              case x => fail[Data](ExpectedLiteral(x))
            }).sequenceU

            values.map((Data.Set.apply _) andThen (LogicalPlan.Constant.apply))

          case ArrayLiteral(exprs) =>
            val values = exprs.map(compile0).sequenceU
            values.map(_ match {
              case Nil => LogicalPlan.Constant(Data.Arr(Nil))
              case t :: ts =>
                NonEmptyList.nel(t, ts)
                  .map(x => LogicalPlan.Invoke(MakeArray, List(x)))
                  .foldLeft1((acc, x) =>
                    LogicalPlan.Invoke(ArrayConcat, List(acc, x)))
            })

          case Splice(expr) =>
            expr.fold(for {
              tableOpt <- CompilerState.fullTable
              table    <- tableOpt.map(emit _).getOrElse(fail(GenericError("Not within a table context so could not find table expression for wildcard")))
            } yield table)(
              compile0)

          case Binop(left, right, sql.Concat) =>
            typeOf(node).flatMap(_ match {
              case Type.Str         => invoke(Concat, left :: right :: Nil)
              case t if t.arrayLike => invoke(ArrayConcat, left :: right :: Nil)
              case _                => fail(GenericError("can't concat mixed/unknown types: " + pprint(left) + ", " + pprint(right)))
            })

          case Binop(left, right, op) =>
            for {
              func  <- funcOf(node)
              rez   <- invoke(func, left :: right :: Nil)
            } yield rez

          case Unop(expr, op) =>
            for {
              func <- funcOf(node)
              rez  <- invoke(func, expr :: Nil)
            } yield rez

          case Ident(name) =>
            CompilerState.fields.flatMap(fields =>
              if (fields.any(_ == name))
                CompilerState.rootTableReq.map(table =>
                  LogicalPlan.Invoke(ObjectProject,
                    List(table, LogicalPlan.Constant(Data.Str(name)))))
              else
                for {
                  relName <- relationName(node)
                  rName   <- relName.fold(fail, emit)
                  table   <- CompilerState.subtableReq(rName)
                } yield if (Path(rName).filename == name) table
                        else LogicalPlan.Invoke(ObjectProject,
                             List(table, LogicalPlan.Constant(Data.Str(name)))))

          case InvokeFunction(Like.name, List(expr, pattern, escape)) =>
            pattern match {
              case StringLiteral(str) =>
                escape match {
                  case StringLiteral(esc) =>
                    if (esc.length > 1)
                      fail(GenericError("escape character is not a single character"))
                    else
                      compile0(expr).map(x =>
                        LogicalPlan.Invoke(Search,
                          List(x, LogicalPlan.Constant(Data.Str(regexForLikePattern(str, esc.headOption))))))
                  case x => fail(ExpectedLiteral(x))
                }
              case x => fail(ExpectedLiteral(x))
            }

          case InvokeFunction(name, args) =>
            for {
              func <- funcOf(node)
              rez  <- compileFunction(func, args)
            } yield rez

          case Match(expr, cases, default0) =>
            for {
              expr    <- compile0(expr)
              default <- default0.fold(emit(LogicalPlan.Constant(Data.Null)))(compile0)
              cases   <- compileCases(cases, default) {
                          case Case(cse, expr2) =>
                            for {
                              cse   <- compile0(cse)
                              expr2 <- compile0(expr2)
                            } yield (LogicalPlan.Invoke(relations.Eq, expr :: cse :: Nil), expr2)
                        }
            } yield cases

          case Switch(cases, default0) =>
            for {
              default <- default0.fold(emit(LogicalPlan.Constant(Data.Null)))(compile0)
              cases   <- compileCases(cases, default) {
                          case Case(cond, expr2) =>
                            for {
                              cond  <- compile0(cond)
                              expr2 <- compile0(expr2)
                            } yield (cond, expr2)
                        }
            } yield cases

          case IntLiteral(value) => emit(LogicalPlan.Constant(Data.Int(value)))

          case FloatLiteral(value) => emit(LogicalPlan.Constant(Data.Dec(value)))

          case StringLiteral(value) => emit(LogicalPlan.Constant(Data.Str(value)))

          case BoolLiteral(value) => emit(LogicalPlan.Constant(Data.Bool(value)))

          case NullLiteral() => emit(LogicalPlan.Constant(Data.Null))
        }

        for {
          nodeLP  <- nodeLP
          root    <- CompilerState.rootTable
          grouped <- CompilerState.grouped
        } yield (root |@| grouped) { (root, grouped) =>
          val nodeLP1 = nodeLP.rewrite {
            case `root` => Some(grouped.src)
            case _      => None
          }
          if (grouped.keys contains nodeLP1)
            Some(LogicalPlan.Invoke(agg.Arbitrary, List(nodeLP)))
          else
            None
        }.join.getOrElse(nodeLP)
    })
  }

  def compile(tree: AnnotatedTree[Expr, Annotations])(implicit F: Monad[F]): F[SemanticError \/ Fix[LogicalPlan]] = {
    compile0(tree.root).eval(CompilerState(tree, Nil, Nil, 0, None)).run
  }
}

object Compiler {
  def apply[F[_]]: Compiler[F] = new Compiler[F] {}

  def trampoline = apply[Free.Trampoline]

  def compile(tree: AnnotatedTree[Expr, Annotations]): SemanticError \/ Fix[LogicalPlan] = {
    trampoline.compile(tree).run
  }
}
