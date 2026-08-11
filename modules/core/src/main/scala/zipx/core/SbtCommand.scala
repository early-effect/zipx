package zipx.core

import zipx.shell.Command
import zipx.shell.Exec
import zipx.shell.ShText
import zipx.shell.SquoteText
import zipx.shell.Word
import zipx.workflow.Expr

/** Where a task command runs. [[MatrixModule]] exists because a collapsed matrix leg addresses its module through an
  * expression, which is not a [[ModuleId]].
  */
enum TaskScope:
  case Unscoped
  case Module(id: ModuleId)
  case MatrixModule

/** One element of an sbt session: what sits between two `;`. Provenance is per step. */
enum SbtStep:
  /** A task/key label zipx scopes itself: renders `[+][<scope>/]<label>`. */
  case Task(label: SbtCommandText, scope: TaskScope, cross: Boolean)

  /** A command sbt (a plugin, or `addCommandAlias`) defines by name — checkable at generate time. */
  case Named(name: SbtCommandName)

  /** Text zipx composed but does not model (a `++<ver>` switch, a `cmd"…"` result). Safe, unparsed, not warned. */
  case Built(text: SbtCommandText)

  /** Text a build handed over verbatim. Reported by [[Steps.rawWarnings]]. */
  case Raw(text: SbtCommandText)

  def render: String = this match
    case SbtStep.Task(label, scope, cross) =>
      val body = scope match
        case TaskScope.Unscoped     => label: String
        case TaskScope.Module(id)   => s"$id/${label: String}"
        case TaskScope.MatrixModule => s"${Expr.matrix("module").render}/${label: String}"
      if cross then s"+$body" else body
    case SbtStep.Named(name) => name: String
    case SbtStep.Built(text) => text: String
    case SbtStep.Raw(text)   => text: String
end SbtStep

/** An sbt command a job runs: a non-empty list of [[SbtStep]]s joined by `; `.
  *
  * Provenance is per step so a typed task next to a raw fragment reports exactly one raw fragment, and
  * [[SbtCommand.module]] scopes only unscoped [[SbtStep.Task]] steps (so `core/a; b` is unrepresentable).
  *
  * Core does not publicly encode sbt command/task name strings: sbt owns its API. Wire-form helpers ([[unsafeTask]],
  * [[unsafeCommand]], [[unsafeBuilt]], [[fromSteps]]) are `private[zipx]` for the plugin, packs, and tests. Authors use
  * `zipxTasks.of` / `session` in the plugin.
  */
final case class SbtCommand private (steps: List[SbtStep]):
  def text: SbtCommandText =
    SbtCommandText.unsafeMake(steps.map(_.render).mkString("; "))

  def rawFragments: List[String] =
    steps.collect { case SbtStep.Raw(t) => t: String }

  def declaredNames: List[SbtCommandName] =
    steps.collect { case SbtStep.Named(n) => n }

  def render: Command = Exec("sbt", SbtCommand.quoteArgument(text))

  def andThen(next: SbtCommand): SbtCommand =
    SbtCommand.fromSteps(steps ++ next.steps)
end SbtCommand

object SbtCommand:

  private[zipx] def fromSteps(steps: List[SbtStep]): SbtCommand =
    require(steps.nonEmpty, "SbtCommand requires at least one step")
    new SbtCommand(steps)

  /** Free text zipx cannot vouch for; warned by [[Steps.rawWarnings]]. */
  def raw(text: String): Either[String, SbtCommand] =
    SbtCommandText.make(text).map(t => fromSteps(List(SbtStep.Raw(t))))

  /** `a; b; c` in one session. Head + varargs, so it is total where [[join]] must be `Option`. */
  def session(first: SbtCommand, rest: SbtCommand*): SbtCommand =
    rest.foldLeft(first)((acc, next) => acc.andThen(next))

  def join(commands: List[SbtCommand]): Option[SbtCommand] =
    commands match
      case Nil          => None
      case head :: tail => Some(session(head, tail*))

  /** A module-scoped task: scopes only unscoped [[SbtStep.Task]] steps; leaves Named/Raw/Built/already-scoped alone. */
  def module(node: ModuleNode, task: SbtCommand): SbtCommand =
    fromSteps(task.steps.map {
      case SbtStep.Task(label, TaskScope.Unscoped, cross) =>
        SbtStep.Task(label, TaskScope.Module(node.id), cross)
      case other => other
    })

  /** [[module]] plus `cross = true` on the steps it scoped when the module is cross-built. */
  def crossModule(node: ModuleNode, task: SbtCommand): SbtCommand =
    val scoped = module(node, task)
    if node.crossScalaVersions.sizeIs > 1 then
      fromSteps(scoped.steps.map {
        case SbtStep.Task(label, scope @ TaskScope.Module(id), _) if id == node.id =>
          SbtStep.Task(label, scope, cross = true)
        case other => other
      })
    else scoped

  /** A cross-version switch ahead of a command: `++X; a; b` so a compound session is unambiguous. */
  def underScalaVersion(version: Expr, command: SbtCommand): SbtCommand =
    val switch = SbtStep.Built(SbtCommandText.unsafeMake(s"++${version.render}"))
    fromSteps(switch :: command.steps)

  /** Plugin key rendering: label is an sbt AttributeKey / config path, already safe. */
  private[zipx] def unsafeTask(label: String): SbtCommand =
    fromSteps(List(SbtStep.Task(SbtCommandText.unsafeMake(label), TaskScope.Unscoped, cross = false)))

  /** `cmd"…"` and other zipx-composed opaque text after its own validation. */
  private[zipx] def unsafeBuilt(text: String): SbtCommand =
    fromSteps(List(SbtStep.Built(SbtCommandText.unsafeMake(text))))

  /** Runtime construction of a declared name (catalog vals, non-literal sites). */
  private[zipx] def unsafeCommand(name: String): SbtCommand =
    fromSteps(List(SbtStep.Named(SbtCommandName.unsafeMake(name))))

  private def quoteArgument(text: String): Word =
    if !text.contains("'") then Word.Squote(SquoteText.unsafeMake(text))
    else
      val segments = text.split("'", -1).toList.map(s => Word.Squote(SquoteText.unsafeMake(s)))
      Word.Cat(intersperse(segments, Word.Lit(ShText.unsafeMake("\\'"))))

  private def intersperse(words: List[Word], separator: Word): List[Word] =
    words match
      case Nil          => Nil
      case head :: tail => head :: tail.flatMap(word => List(separator, word))

end SbtCommand
