package zipx.core

/** Optional sbt clean step prepended to every Verify-phase command (Aggregate root, Layer waves, and Graph per-module
  * jobs). Default is [[None]]: CI usually relies on a fresh runner + the action cache rather than cleaning.
  */
enum VerifyClean:
  case None, Clean, CleanFull

  /** Prepend this clean mode to an sbt command, e.g. `test` → `cleanFull; test`. */
  def prefixCommand(command: SbtCommand): SbtCommand = this match
    case VerifyClean.None      => command
    case VerifyClean.Clean     => SbtCommand.prefixedBy(VerifyClean.CleanCommand, command)
    case VerifyClean.CleanFull => SbtCommand.prefixedBy(VerifyClean.CleanFullCommand, command)
end VerifyClean

object VerifyClean:
  private val CleanCommand: SbtCommand     = SbtCommand("clean")
  private val CleanFullCommand: SbtCommand = SbtCommand("cleanFull")
