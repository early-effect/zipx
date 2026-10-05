package zipx.core

/** Prepended to every Verify-phase command. Off by default: CI relies on a fresh runner and the action cache. */
enum VerifyClean:
  case None, Clean, CleanFull

  def prefixCommand(command: SbtCommand): SbtCommand = this match
    case VerifyClean.None      => command
    case VerifyClean.Clean     => VerifyClean.CleanCommand.andThen(command)
    case VerifyClean.CleanFull => VerifyClean.CleanFullCommand.andThen(command)
end VerifyClean

object VerifyClean:
  private val CleanCommand: SbtCommand = SbtCommand.unsafeTask("clean")

  /** A command, not a task, so generate checks the name. */
  private val CleanFullCommand: SbtCommand = SbtCommand.unsafeCommand("cleanFull")
