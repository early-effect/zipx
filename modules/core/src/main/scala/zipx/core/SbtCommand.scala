package zipx.core

import zipx.shell.Command
import zipx.shell.Exec
import zipx.shell.ShText
import zipx.shell.SquoteText
import zipx.shell.Word
import zipx.workflow.Expr

/** An sbt command a job runs, and whether zipx built its structure or a caller supplied it.
  *
  * Two cases rather than one, for the same reason [[zipx.shell.Command]] has a `Raw` case: the text rules hold either
  * way, so both are safe to render, but *provenance* is what the generate-time warning needs. A command zipx assembled
  * from a module id and a task cannot be misspelled; command text zipx was handed can be, and a typo in `api/tets` is a
  * failing CI job rather than a compile error. [[rawFragments]] is how that reaches [[zipx.core.Steps.rawWarnings]],
  * and [[zipx.shell.Command.Raw]] is the model it follows.
  */
enum SbtCommand:

  /** Text zipx assembled: a module id and a task, joined by the combinators on the companion. */
  case Built(text: SbtCommandText)

  /** **Escape hatch.** Command text zipx did not build, for sbt syntax the combinators do not cover.
    *
    * Not a hole: [[SbtCommandText]]'s rules still apply, since they are the ones that would corrupt the generated file
    * rather than merely surprise a reader. What this skips is the *structure*. Reported by
    * [[zipx.core.Steps.rawWarnings]] at generate time, for the same reason `Script.raw` is: an escape hatch you cannot
    * see being used is one you cannot review.
    */
  case Unchecked(text: SbtCommandText)

  /** The command text, whatever its provenance. Abstract because both cases supply it as a field. */
  def text: SbtCommandText

  /** The unchecked text in this command, for the generate-time warning. Composing an unchecked command into a larger
    * one keeps it reportable, which is why the combinators below thread this rather than dropping it.
    */
  def rawFragments: List[String] = this match
    case Built(_)        => Nil
    case Unchecked(text) => List(text)

  /** `sbt '<command>'`, the step's actual program invocation.
    *
    * Total, which is the point of the type: a command containing a single quote is split into `'a'\''b'` segments,
    * since `'…'` offers no escape for its own delimiter. Every other character is safe inside single quotes, and
    * [[SbtCommandText]] has already excluded the ones that would break the YAML line.
    */
  def render: Command = Exec("sbt", SbtCommand.quoteArgument(text))

end SbtCommand

object SbtCommand:

  /** A command from a literal, validated at compile time: `SbtCommand("cleanFull")`. */
  inline def apply(inline command: String): SbtCommand = Built(SbtCommandText(command))

  def make(command: String): Either[String, SbtCommand] = SbtCommandText.make(command).map(Built(_))

  /** A `Built` command from text a caller has already established cannot break [[SbtCommandText]]'s rules.
    *
    * For construction sites that can name the reason, so that an `Either` does not propagate through signatures to
    * report a case that cannot arise. `zipx.sbt.CapabilityTasks` is the one such caller: an sbt key label and a config
    * name are both Scala identifiers, and its `cmd"…"` interpolator validates every literal piece as `ShText` first.
    */
  def unsafeMake(command: String): SbtCommand = Built(SbtCommandText.unsafeMake(command))

  /** Command text zipx did not build. See [[SbtCommand.Unchecked]] for what this does and does not skip. */
  def unchecked(command: String): Either[String, SbtCommand] = SbtCommandText.make(command).map(Unchecked(_))

  /** A module-scoped task: `api/test`. Both pieces are already validated, so this cannot fail. */
  def module(node: ModuleNode, task: SbtCommand): SbtCommand =
    task.withText(s"${node.id}/${task.text}")

  /** A module-scoped task run on every cross version when the module is cross-built: `+api/publish`. */
  def crossModule(node: ModuleNode, task: SbtCommand): SbtCommand =
    val scoped = module(node, task)
    if node.crossScalaVersions.sizeIs > 1 then scoped.withText(s"+${scoped.text}") else scoped

  /** One sbt session running several commands: `a; b`. This is what the Aggregate and Layer scopes do with the
    * per-module commands they collect, and it is why joining is a combinator rather than a `mkString` at the call site.
    *
    * `Unchecked` if any part is, so one hand-written command in a joined session still reaches the warning.
    */
  def join(commands: List[SbtCommand]): Option[SbtCommand] =
    Option.when(commands.nonEmpty)(
      taggedAs(commands, SbtCommandText.unsafeMake(commands.map(_.text).mkString("; ")))
    )

  /** Prefix another command onto this one in the same session: `cleanFull; test`. */
  def prefixedBy(prefix: SbtCommand, command: SbtCommand): SbtCommand =
    taggedAs(List(prefix, command), SbtCommandText.unsafeMake(s"${prefix.text}; ${command.text}"))

  /** A cross-version switch ahead of a command: `++${{ matrix.scala }} test`. Space-separated rather than `;`, because
    * `++<version>` takes the rest of the line as the command to run under that version.
    *
    * The version is an [[zipx.workflow.Expr]] because the only caller has a matrix axis rather than a literal, and
    * `unsafeMake` is licensed by [[zipx.workflow.Expr.renderShText]]: an expression renders to `ShText`, whose rules
    * (one line, no control characters) are the ones [[SbtCommandText]] shares.
    */
  def underScalaVersion(version: Expr, command: SbtCommand): SbtCommand =
    command.withText(s"++${version.render} ${command.text}")

  /** Keep this command's provenance while replacing its text, for a combinator that wraps validated pieces. */
  extension (command: SbtCommand)
    private def withText(text: String): SbtCommand = command match
      case Built(_)     => Built(SbtCommandText.unsafeMake(text))
      case Unchecked(_) => Unchecked(SbtCommandText.unsafeMake(text))

  /** `Unchecked` if any part is: composing hand-written text into a larger command does not make it checked. */
  private def taggedAs(parts: List[SbtCommand], text: SbtCommandText): SbtCommand =
    if parts.exists(_.rawFragments.nonEmpty) then Unchecked(text) else Built(text)

  /** `'a'\''b'` for text containing a quote, plain `'text'` otherwise. Ending and reopening the quoted run is the only
    * way to emit a `'` inside `'…'`, and the `\'` between the runs is what carries it.
    */
  private def quoteArgument(text: String): Word =
    if !text.contains("'") then Word.Squote(SquoteText.unsafeMake(text))
    else
      // Each segment is quote-free, because `split` never yields a segment containing its separator. The `-1` limit
      // keeps trailing empty segments, so `a'` renders `'a'\'''` rather than losing the final quote.
      val segments = text.split("'", -1).toList.map(s => Word.Squote(SquoteText.unsafeMake(s)))
      Word.Cat(intersperse(segments, Word.Lit(ShText.unsafeMake("\\'"))))

  private def intersperse(words: List[Word], separator: Word): List[Word] =
    words match
      case Nil          => Nil
      case head :: tail => head :: tail.flatMap(word => List(separator, word))

end SbtCommand
