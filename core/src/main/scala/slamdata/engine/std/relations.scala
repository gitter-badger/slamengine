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

package slamdata.engine.std

import slamdata.Predef._
import slamdata.recursionschemes._
import slamdata.fp._
import slamdata.engine.{Data, Func, LogicalPlan, Type, Mapping, SemanticError}, LogicalPlan._, SemanticError._

import scalaz._, NonEmptyList.nel, Validation.{success, failure}

// TODO: Cleanup!
trait RelationsLib extends Library {
  private val BinaryAny: Func.Untyper =
    Type.typecheck(_, Type.Bool) map κ(Type.Top :: Type.Top :: Nil)

  private val BinaryBool: Func.Untyper =
    Type.typecheck(_, Type.Bool) map κ(Type.Bool :: Type.Bool :: Nil)

  private val UnaryBool: Func.Untyper =
    Type.typecheck(_, Type.Bool) map κ(Type.Bool :: Nil)

  val Eq = Mapping("(=)", "Determines if two values are equal", Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(data1) :: Type.Const(data2) :: Nil => Type.Const(Data.Bool(data1 == data2))
      case type1 :: type2 :: Nil if Type.lub(type1, type2) == Type.Top && type1 != Type.Top && type2 != Type.Top => Type.Const(Data.Bool(false))
      case _ => Type.Bool
    },
    BinaryAny
  )

  val Neq = Mapping("(<>)", "Determines if two values are not equal", Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(data1) :: Type.Const(data2) :: Nil => Type.Const(Data.Bool(data1 != data2))
      case type1 :: type2 :: Nil if Type.lub(type1, type2) == Type.Top && type1 != Type.Top && type2 != Type.Top => Type.Const(Data.Bool(true))
      case _ => Type.Bool
    },
    BinaryAny
  )

  val Lt = Mapping("(<)", "Determines if one value is less than another value of the same type", Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(Data.Bool(v1)) :: Type.Const(Data.Bool(v2)) :: Nil => Type.Const(Data.Bool(v1 < v2))
      case Type.Const(Data.Number(v1)) :: Type.Const(Data.Number(v2)) :: Nil => Type.Const(Data.Bool(v1 < v2))
      case Type.Const(Data.Str(v1)) :: Type.Const(Data.Str(v2)) :: Nil => Type.Const(Data.Bool(v1 < v2))
      case Type.Const(Data.Timestamp(v1)) :: Type.Const(Data.Timestamp(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) < 0))
      case Type.Const(Data.Interval(v1)) :: Type.Const(Data.Interval(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) < 0))
      case _ => Type.Bool
    },
    BinaryAny
  )

  val Lte = Mapping("(<=)", "Determines if one value is less than or equal to another value of the same type", Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(Data.Bool(v1)) :: Type.Const(Data.Bool(v2)) :: Nil => Type.Const(Data.Bool(v1 <= v2))
      case Type.Const(Data.Number(v1)) :: Type.Const(Data.Number(v2)) :: Nil => Type.Const(Data.Bool(v1 <= v2))
      case Type.Const(Data.Str(v1)) :: Type.Const(Data.Str(v2)) :: Nil => Type.Const(Data.Bool(v1 <= v2))
      case Type.Const(Data.Timestamp(v1)) :: Type.Const(Data.Timestamp(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) <= 0))
      case Type.Const(Data.Interval(v1)) :: Type.Const(Data.Interval(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) <= 0))
      case _ => Type.Bool
    },
    BinaryAny
  )

  val Gt = Mapping("(>)", "Determines if one value is greater than another value of the same type", Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(Data.Bool(v1)) :: Type.Const(Data.Bool(v2)) :: Nil => Type.Const(Data.Bool(v1 > v2))
      case Type.Const(Data.Number(v1)) :: Type.Const(Data.Number(v2)) :: Nil => Type.Const(Data.Bool(v1 > v2))
      case Type.Const(Data.Str(v1)) :: Type.Const(Data.Str(v2)) :: Nil => Type.Const(Data.Bool(v1 > v2))
      case Type.Const(Data.Timestamp(v1)) :: Type.Const(Data.Timestamp(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) > 0))
      case Type.Const(Data.Interval(v1)) :: Type.Const(Data.Interval(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) > 0))
      case _ => Type.Bool
    },
    BinaryAny
  )

  val Gte = Mapping("(>=)", "Determines if one value is greater than or equal to another value of the same type", Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(Data.Bool(v1)) :: Type.Const(Data.Bool(v2)) :: Nil => Type.Const(Data.Bool(v1 >= v2))
      case Type.Const(Data.Number(v1)) :: Type.Const(Data.Number(v2)) :: Nil => Type.Const(Data.Bool(v1 >= v2))
      case Type.Const(Data.Str(v1)) :: Type.Const(Data.Str(v2)) :: Nil => Type.Const(Data.Bool(v1 >= v2))
      case Type.Const(Data.Timestamp(v1)) :: Type.Const(Data.Timestamp(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) >= 0))
      case Type.Const(Data.Interval(v1)) :: Type.Const(Data.Interval(v2)) :: Nil => Type.Const(Data.Bool(v1.compareTo(v2) >= 0))
      case _ => Type.Bool
    },
    BinaryAny
  )

  val Between = Mapping("(BETWEEN)", "Determines if a value is between two other values of the same type, inclusive", Type.Top :: Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper {
      // TODO: partial evaluation for Int and Dec and possibly other constants
      case _ :: _ :: _ :: Nil => Type.Bool
      case _ => Type.Bool
    },
    {
      case Type.Const(Data.Bool(_)) => success(Type.Top :: Type.Top :: Type.Top :: Nil)
      case Type.Bool => success(Type.Top :: Type.Top :: Type.Top :: Nil)
      case t => failure(nel(TypeError(Type.Bool, t, None), Nil))
    }
  )

  val IsNull = Mapping("IS_NULL", "Determines if a value is the special value Null. May or may not be equivalent to applying Eq to the value and Null.", Type.Top :: Nil,
    noSimplification,
    partialTyper {
      case List(Type.Const(Data.Null)) => Type.Const(Data.Bool(true))
      case List(Type.Const(_))         => Type.Const(Data.Bool(false))
      case List(_)                     => Type.Bool
    },
    UnaryBool
  )

  val And = Mapping("(AND)", "Performs a logical AND of two boolean values", Type.Bool :: Type.Bool :: Nil,
    partialSimplifier {
      case List(Fix(ConstantF(Data.True)), other) => other
      case List(other, Fix(ConstantF(Data.True))) => other
    },
    partialTyper {
      case Type.Const(Data.Bool(v1)) :: Type.Const(Data.Bool(v2)) :: Nil => Type.Const(Data.Bool(v1 && v2))
      case Type.Const(Data.Bool(false)) :: _ :: Nil => Type.Const(Data.Bool(false))
      case _ :: Type.Const(Data.Bool(false)) :: Nil => Type.Const(Data.Bool(false))
      case Type.Const(Data.Bool(true)) :: x :: Nil => x
      case x :: Type.Const(Data.Bool(true)) :: Nil => x
      case _ => Type.Bool
    },
    BinaryBool
  )

  val Or = Mapping("(OR)", "Performs a logical OR of two boolean values", Type.Bool :: Type.Bool :: Nil,
    partialSimplifier {
      case List(Fix(ConstantF(Data.False)), other) => other
      case List(other, Fix(ConstantF(Data.False))) => other
    },
    partialTyper {
      case Type.Const(Data.Bool(v1)) :: Type.Const(Data.Bool(v2)) :: Nil => Type.Const(Data.Bool(v1 || v2))
      case Type.Const(Data.Bool(true)) :: _ :: Nil => Type.Const(Data.Bool(true))
      case _ :: Type.Const(Data.Bool(true)) :: Nil => Type.Const(Data.Bool(true))
      case Type.Const(Data.Bool(false)) :: x :: Nil => x
      case x :: Type.Const(Data.Bool(false)) :: Nil => x
      case _ => Type.Bool
    },
    BinaryBool
  )

  val Not = Mapping("NOT", "Performs a logical negation of a boolean value", Type.Bool :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(Data.Bool(v)) :: Nil => Type.Const(Data.Bool(!v))
      case _ => Type.Bool
    },
    UnaryBool
  )

  val Cond = Mapping("(IF_THEN_ELSE)", "Chooses between one of two cases based on the value of a boolean expression", Type.Bool :: Type.Top :: Type.Top :: Nil,
    partialSimplifier {
      case List(Fix(ConstantF(Data.True)),  c, _) => c
      case List(Fix(ConstantF(Data.False)), _, a) => a
    },
    partialTyper {
      case Type.Const(Data.Bool(true)) :: ifTrue :: ifFalse :: Nil => ifTrue
      case Type.Const(Data.Bool(false)) :: ifTrue :: ifFalse :: Nil => ifFalse
      case Type.Bool :: ifTrue :: ifFalse :: Nil => Type.lub(ifTrue, ifFalse)
    },
    t => success(Type.Bool :: t :: t :: Nil)
  )

  val Coalesce = Mapping(
    "coalesce",
    "Returns the first of its arguments that isn't null.",
    Type.Top :: Type.Top :: Nil,
    partialSimplifier {
      case List(Fix(ConstantF(Data.Null)), second) => second
      case List(first, Fix(ConstantF(Data.Null))) => first
    },
    partialTyper {
      case Type.Null             :: v2 :: Nil => v2
      case Type.Const(Data.Null) :: v2 :: Nil => v2
      case Type.Const(v1)        :: _  :: Nil => Type.Const(v1)
      case v1 :: Type.Null             :: Nil => v1
      case v1 :: Type.Const(Data.Null) :: Nil => v1
      case v1 :: v2                    :: Nil => Type.lub(v1, v2)
    },
    t => success(t :: t :: Nil))

  val Constantly = Mapping("CONSTANTLY", "Always return the same value",
    Type.Top :: Type.Top :: Nil,
    noSimplification,
    partialTyper { case const :: _ :: Nil => const },
    t => success(t :: Type.Top :: Nil))

  def functions = Eq :: Neq :: Lt :: Lte :: Gt :: Gte :: Between :: IsNull :: And :: Or :: Not :: Cond :: Coalesce :: Nil

  def flip(f: Mapping): Option[Mapping] = f match {
    case Eq  => Some(Eq)
    case Neq => Some(Neq)
    case Lt  => Some(Gt)
    case Lte => Some(Gte)
    case Gt  => Some(Lt)
    case Gte => Some(Lte)
    case And => Some(And)
    case Or  => Some(Or)
    case _   => None
  }

  def negate(f: Mapping): Option[Mapping] = f match {
    case Eq  => Some(Neq)
    case Neq => Some(Eq)
    case Lt  => Some(Gte)
    case Lte => Some(Gt)
    case Gt  => Some(Lte)
    case Gte => Some(Lt)
    case _   => None
  }
}
object RelationsLib extends RelationsLib
