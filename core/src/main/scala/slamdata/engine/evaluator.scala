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

import scalaz._
import scalaz.concurrent._

import slamdata.engine.fs._; import Path._
import slamdata.engine.Errors._

sealed trait ResultPath {
  def path: Path
}
object ResultPath {
  /** Path to a result which names an unaltered source resource or the requested destination. */
  final case class User(path: Path) extends ResultPath

  /** Path to a result which is a new temporary resource created during query execution. */
  final case class Temp(path: Path) extends ResultPath
}

trait Evaluator[PhysicalPlan] {
  import Evaluator._

  /**
   * Executes the specified physical plan.
   *
   * Returns the location where the output results are located. In some
   * cases (e.g. SELECT * FROM FOO), this may not be equal to the specified
   * destination resource (because this would require copying all the data).
   */
  def execute(physical: PhysicalPlan): ETask[EvaluationError, ResultPath]

  /**
   * Compile the specified physical plan to a command
   * that can be run natively on the backend.
   */
  def compile(physical: PhysicalPlan): (String, Cord)

  /**
   * Fails if the backend implementation is not compatible with the connected
   * system (typically because it does not have not the correct version number).
   */
  def checkCompatibility: ETask[EnvironmentError, Unit]
}
object Evaluator {
  sealed trait EnvironmentError {
    def message: String
  }
  object EnvironmentError {
    final case class MissingBackend(message: String) extends EnvironmentError
    final case class MissingFileSystem(path: Path, config: slamdata.engine.config.BackendConfig) extends EnvironmentError {
      def message = "No data source could be mounted at the path " + path + " using the config " + config
    }
    final case object MissingDatabase extends EnvironmentError {
      def message = "no database found"
    }
    final case class InvalidConfig(message: String) extends EnvironmentError
    final case class ConnectionFailed(message: String) extends EnvironmentError
    final case class InvalidCredentials(message: String) extends EnvironmentError
    final case class InsufficientPermissions(message: String) extends EnvironmentError
    final case class EnvPathError(error: PathError) extends EnvironmentError {
      def message = error.message
    }
    final case class EnvEvalError(error: EvaluationError) extends EnvironmentError {
      def message = error.message
    }
    final case class EnvWriteError(error: Backend.ProcessingError) extends EnvironmentError {
      def message = "write failed: " + error.message
    }
    final case class UnsupportedVersion(backend: Evaluator[_], version: List[Int]) extends EnvironmentError {
      def message = "Unsupported " + backend + " version: " + version.mkString(".")
    }

    import argonaut._, Argonaut._
    implicit val EnvironmentErrorEncodeJson = {
      def format(message: String, detail: Option[String]) =
        Json(("error" := message) :: detail.toList.map("errorDetail" := _): _*)

      EncodeJson[EnvironmentError] {
        case MissingDatabase              => format("Authentication database not specified in connection URI.", None)
        case ConnectionFailed(msg)        => format("Invalid server and / or port specified.", Some(msg))
        case InvalidCredentials(msg)      => format("Invalid username and/or password specified.", Some(msg))
        case InsufficientPermissions(msg) => format("Database user does not have permissions on database.", Some(msg))
        case EnvWriteError(pe)            => format("Database user does not have necessary write permissions.", Some(pe.message))
        case e                            => format(e.message, None)
      }
    }
  }

  type EnvTask[A] = EitherT[Task, EnvironmentError, A]
  implicit val EnvironmentErrorShow = Show.showFromToString[EnvironmentError]

  object MissingBackend {
    def apply(message: String): EnvironmentError = EnvironmentError.MissingBackend(message)
    def unapply(obj: EnvironmentError): Option[String] = obj match {
      case EnvironmentError.MissingBackend(message) => Some(message)
      case _                       => None
    }
  }
  object MissingFileSystem {
    def apply(path: Path, config: slamdata.engine.config.BackendConfig): EnvironmentError = EnvironmentError.MissingFileSystem(path, config)
    def unapply(obj: EnvironmentError): Option[(Path, slamdata.engine.config.BackendConfig)] = obj match {
      case EnvironmentError.MissingFileSystem(path, config) => Some((path, config))
      case _                       => None
    }
  }
  object MissingDatabase {
    def apply(): EnvironmentError = EnvironmentError.MissingDatabase
    def unapply(obj: EnvironmentError): Boolean = obj match {
      case EnvironmentError.MissingDatabase => true
      case _                     => false
    }
  }
  object InvalidConfig {
    def apply(message: String): EnvironmentError = EnvironmentError.InvalidConfig(message)
    def unapply(obj: EnvironmentError): Option[String] = obj match {
      case EnvironmentError.InvalidConfig(message) => Some(message)
      case _                       => None
    }
  }
  object ConnectionFailed {
    def apply(message: String): EnvironmentError = EnvironmentError.ConnectionFailed(message)
    def unapply(obj: EnvironmentError): Option[String] = obj match {
      case EnvironmentError.ConnectionFailed(message) => Some(message)
      case _                       => None
    }
  }
  object InvalidCredentials {
    def apply(message: String): EnvironmentError = EnvironmentError.InvalidCredentials(message)
    def unapply(obj: EnvironmentError): Option[String] = obj match {
      case EnvironmentError.InvalidCredentials(message) => Some(message)
      case _                       => None
    }
  }
  object InsufficientPermissions {
    def apply(message: String): EnvironmentError = EnvironmentError.InsufficientPermissions(message)
    def unapply(obj: EnvironmentError): Option[String] = obj match {
      case EnvironmentError.InsufficientPermissions(message) => Some(message)
      case _                       => None
    }
  }
  object EnvPathError {
    def apply(error: PathError): EnvironmentError =
      EnvironmentError.EnvPathError(error)
    def unapply(obj: EnvironmentError): Option[PathError] = obj match {
      case EnvironmentError.EnvPathError(error) => Some(error)
      case _                       => None
    }
  }
  object EnvWriteError {
    def apply(error: Backend.ProcessingError): EnvironmentError =
      EnvironmentError.EnvWriteError(error)
    def unapply(obj: EnvironmentError): Option[Backend.ProcessingError] = obj match {
      case EnvironmentError.EnvWriteError(error) => Some(error)
      case _                       => None
    }
  }
  object EnvEvalError {
    def apply(error: EvaluationError): EnvironmentError = EnvironmentError.EnvEvalError(error)
    def unapply(obj: EnvironmentError): Option[EvaluationError] = obj match {
      case EnvironmentError.EnvEvalError(error) => Some(error)
      case _                       => None
    }
  }
  object UnsupportedVersion {
    def apply(backend: Evaluator[_], version: List[Int]): EnvironmentError = EnvironmentError.UnsupportedVersion(backend, version)
    def unapply(obj: EnvironmentError): Option[(Evaluator[_], List[Int])] = obj match {
      case EnvironmentError.UnsupportedVersion(backend, version) => Some((backend, version))
      case _                       => None
    }
  }

  sealed trait EvaluationError {
    def message: String
  }
  object EvaluationError {
    final case class EvalPathError(error: PathError) extends EvaluationError {
      def message = error.message
    }
    final case object NoDatabase extends EvaluationError {
      def message = "no database found"
    }
    final case class UnableToStore(message: String) extends EvaluationError
    final case class InvalidTask(message: String) extends EvaluationError
    final case class CommandFailed(message: String) extends EvaluationError
  }

  type EvaluationTask[A] = ETask[EvaluationError, A]

  object EvalPathError {
    def apply(error: PathError): EvaluationError =
      EvaluationError.EvalPathError(error)
    def unapply(obj: EvaluationError): Option[PathError] = obj match {
      case EvaluationError.EvalPathError(error) => Some(error)
      case _                       => None
    }
  }
  object NoDatabase {
    def apply(): EvaluationError = EvaluationError.NoDatabase
    def unapply(obj: EvaluationError): Boolean = obj match {
      case EvaluationError.NoDatabase => true
      case _                => false
    }
  }
  object UnableToStore {
    def apply(message: String): EvaluationError = EvaluationError.UnableToStore(message)
    def unapply(obj: EvaluationError): Option[String] = obj match {
      case EvaluationError.UnableToStore(message) => Some(message)
      case _                       => None
    }
  }
  object InvalidTask {
    def apply(message: String): EvaluationError = EvaluationError.InvalidTask(message)
    def unapply(obj: EvaluationError): Option[String] = obj match {
      case EvaluationError.InvalidTask(message) => Some(message)
      case _                       => None
    }
  }
  object CommandFailed {
    def apply(message: String): EvaluationError = EvaluationError.CommandFailed(message)
    def unapply(obj: EvaluationError): Option[String] = obj match {
      case EvaluationError.CommandFailed(message) => Some(message)
      case _                       => None
    }
  }
}
