package zipx.core

/** Optional sbt clean step prepended to every Verify-phase command (Aggregate root, Layer waves, and Graph per-module
  * jobs). Default is [[None]] — CI usually relies on a fresh runner + the action cache rather than cleaning.
  */
enum VerifyClean:
  case None, Clean, CleanFull

  /** Prepend this clean mode to an sbt command, e.g. `test` → `cleanFull; test`. */
  def prefixCommand(command: String): String = this match
    case VerifyClean.None      => command
    case VerifyClean.Clean     => s"clean; $command"
    case VerifyClean.CleanFull => s"cleanFull; $command"
end VerifyClean
