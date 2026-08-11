package zipx.core

/** Optional sbt clean step prepended to every Verify-phase command (Aggregate root, Layer waves, and Graph per-module
  * jobs). Default is [[None]]: CI usually relies on a fresh runner + the action cache rather than cleaning.
  */
enum VerifyClean:
  case None, Clean, CleanFull

  /** Prepend this clean mode to an sbt command, e.g. `test` → `cleanFull; test`. */
  def prefixCommand(command: SbtCommand): SbtCommand = this match
    case VerifyClean.None      => command
    case VerifyClean.Clean     => VerifyClean.CleanCommand.andThen(command)
    case VerifyClean.CleanFull => VerifyClean.CleanFullCommand.andThen(command)
end VerifyClean

object VerifyClean:
  /** Wire form: sbt's `clean` task. */
  private val CleanCommand: SbtCommand = SbtCommand.unsafeTask("clean")

  /** Wire form: sbt's `cleanFull` command (declared name; generate-checked). */
  private val CleanFullCommand: SbtCommand = SbtCommand.unsafeCommand("cleanFull")
