package zipx.shell

import neotype.unwrap

import scala.annotation.targetName

/** The body of an `if` branch or a loop. Non-empty by construction, so `if cond; then fi` has no value that spells it.
  */
final case class Block(head: Command, rest: List[Command]):
  def commands: List[Command]                  = head :: rest
  def lines(ctx: Script.Ctx): List[ScriptLine] = commands.flatMap(_.lines(ctx))
  def rawFragments: List[String]               = commands.flatMap(_.rawFragments)

object Block:
  def apply(head: Command, rest: Command*): Block = Block(head, rest.toList)

/** One shell statement. Open so a consumer can add a construct zipx does not model: an implementation emits through
  * `ctx.line` / `ctx.emit` / `ctx.nested` ([[Script]] owns depth), returns one entry per physical line, and overrides
  * [[rawFragments]] if it carries unvalidated text.
  */
trait Command:

  /** Possibly empty (a fully disabled [[SetOpts]]); an [[InlineCommand]] always emits at least one line. */
  def lines(ctx: Script.Ctx): List[ScriptLine]

  /** Renders at depth zero; whoever emits it adds the surrounding depth. */
  def render: String = lines(Script.Ctx.root).map(_.unwrap).mkString("\n")

  /** Unvalidated text this command carries, reported by the generate-time escape-hatch warning. */
  def rawFragments: List[String] = Nil

end Command

/** A command legal where the shell wants exactly one: a pipeline leg, an `if` condition, a redirect source, a heredoc
  * feeder. Compound commands need `;` separators there that the renderer does not insert, so `Exec("wc") | If(…)` does
  * not compile. "One" is logical: [[Continued]] spans several physical lines and is legal in all of these positions.
  */
trait InlineCommand extends Command:

  def inlineLines: ShLines

  final def inlineRender: String = inlineLines.render

  final def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.indent(inlineLines).lines.toList

  infix def |(other: InlineCommand): InlineCommand = Pipe(this, other)

  infix def &&(other: InlineCommand): InlineCommand = AndThen(this, other)

  infix def ||(other: InlineCommand): InlineCommand = OrElse(this, other)

  /** `this > target`. */
  def writeTo(target: Word): InlineCommand = Redirect(this, target, append = false)

  /** `this >> target`. */
  def appendTo(target: Word): InlineCommand = Redirect(this, target, append = true)

  /** `this >/dev/null 2>&1`: keep only the exit status. */
  def silenced: InlineCommand = Silence(this)

  /** `this 2>/dev/null`. */
  def stderrSilenced: InlineCommand = SilenceErr(this)

end InlineCommand

/** Arguments are never quoted for you: only the caller knows whether `v*` should glob. Use [[Word.quoted]] /
  * [[Word.vq]] to ask for quotes.
  */
final case class Exec(program: Word, args: List[Word]) extends InlineCommand:
  def inlineLines: ShLines = Word.spaceJoined(program :: args)

  override def rawFragments: List[String] = (program :: args).flatMap(_.rawFragments)

object Exec:

  /** `unsafeMake` cannot fail: ProgramName's characters are a subset of ShText's. Nesting the two `apply`s instead
    * would ask neotype to comptime-evaluate `ProgramName(…).unwrap`, which it cannot parse.
    */
  inline def apply(inline program: String, args: Word*): Exec =
    Exec(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), args.toList)

  /** [[apply]] for an argv assembled at runtime: a varargs splat cannot pass through the `inline` overload. */
  inline def of(inline program: String, args: List[Word]): Exec =
    Exec(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), args)
end Exec

/** One command over several lines joined by `\`. Modelled because a hand-written continuation breaks silently: trailing
  * whitespace after the `\` kills it, and the last line must not carry one.
  *
  * @param continuationIndent
  *   spaces before each line after the first, on top of the script's own depth.
  */
final case class Continued(program: Word, argLines: List[List[Word]], continuationIndent: Int = 2)
    extends InlineCommand:
  def inlineLines: ShLines =
    val head     = program.lines(Quoting.Unquoted)
    val rendered = argLines.map(Word.spaceJoined)
    val first    = rendered.headOption.fold(head)(args => head + " " ++ args)
    val rest     = rendered.drop(1).map(_.indentBy(continuationIndent))
    // Counted off emitted units, not `argLines`, so a program with no arguments is one unterminated line.
    val emitted = first :: rest
    val joined  = emitted.dropRight(1).map(_ + " \\") :+ emitted.last
    ShLines.stack(joined.head, joined.tail)
  end inlineLines

  override def rawFragments: List[String] = (program :: argLines.flatten).flatMap(_.rawFragments)
end Continued

object Continued:

  inline def apply(inline program: String, argLines: List[List[Word]]): Continued =
    Continued(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), argLines)

// The three list operators join onto the left side's *last* line, so `Continued(…) | wc -l` puts the pipe after the
// final continuation rather than after the first line.

final case class Pipe(left: InlineCommand, right: InlineCommand) extends InlineCommand:
  def inlineLines: ShLines                = left.inlineLines + " | " ++ right.inlineLines
  override def rawFragments: List[String] = left.rawFragments ++ right.rawFragments

final case class AndThen(left: InlineCommand, right: InlineCommand) extends InlineCommand:
  def inlineLines: ShLines                = left.inlineLines + " && " ++ right.inlineLines
  override def rawFragments: List[String] = left.rawFragments ++ right.rawFragments

final case class OrElse(left: InlineCommand, right: InlineCommand) extends InlineCommand:
  def inlineLines: ShLines                = left.inlineLines + " || " ++ right.inlineLines
  override def rawFragments: List[String] = left.rawFragments ++ right.rawFragments

final case class Redirect(command: InlineCommand, target: Word, append: Boolean, from: Option[FileDescriptor] = None)
    extends InlineCommand:
  def inlineLines: ShLines =
    val fd    = from.fold("")(_.unwrap.toString)
    val arrow = if append then ">>" else ">"
    command.inlineLines ++ ShLines.composed(s" $fd$arrow ") ++ target.lines(Quoting.Unquoted)

  override def rawFragments: List[String] = command.rawFragments ++ target.rawFragments

final case class RedirectFd(command: InlineCommand, from: FileDescriptor, to: FileDescriptor) extends InlineCommand:
  def inlineLines: ShLines                = command.inlineLines ++ ShLines.composed(s" ${from.unwrap}>&${to.unwrap}")
  override def rawFragments: List[String] = command.rawFragments

final case class Silence(command: InlineCommand) extends InlineCommand:
  def inlineLines: ShLines                = command.inlineLines + " >/dev/null 2>&1"
  override def rawFragments: List[String] = command.rawFragments

final case class SilenceErr(command: InlineCommand) extends InlineCommand:
  def inlineLines: ShLines                = command.inlineLines + " 2>/dev/null"
  override def rawFragments: List[String] = command.rawFragments

final case class Assign(name: VarName, value: Word, scope: Assign.Scope = Assign.Scope.Plain) extends InlineCommand:
  def inlineLines: ShLines =
    val prefix = scope match
      case Assign.Scope.Plain    => ShLines.empty
      case Assign.Scope.Local    => ShLines.of("local ")
      case Assign.Scope.Export   => ShLines.of("export ")
      case Assign.Scope.ReadOnly => ShLines.of("readonly ")
    prefix ++ ShLines.varName(name) + "=" ++ value.lines(Quoting.Unquoted)

  override def rawFragments: List[String] = value.rawFragments
end Assign

object Assign:
  enum Scope:
    case Plain, Local, Export, ReadOnly

  inline def apply(inline name: String, value: Word): Assign = Assign(VarName(name), value)

final case class If(
    cond: ShTest,
    thenDo: Block,
    elifs: List[(ShTest, Block)] = Nil,
    elseDo: Option[Block] = None,
) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val inner                                                  = ctx.nested
    def opens(keyword: String, test: ShTest): List[ScriptLine] =
      ctx.emit(ShLines.composed(s"$keyword ") ++ test.lines + "; then")
    val head = opens("if", cond) ::: thenDo.lines(inner)
    val mid  = elifs.flatMap((c, body) => opens("elif", c) ::: body.lines(inner))
    val tail = elseDo.fold(List.empty[ScriptLine])(body => ctx.line("else") ::: body.lines(inner))
    head ++ mid ++ tail ++ ctx.line("fi")

  override def rawFragments: List[String] =
    cond.rawFragments ++ thenDo.rawFragments ++
      elifs.flatMap((c, body) => c.rawFragments ++ body.rawFragments) ++
      elseDo.fold(Nil)(_.rawFragments)
end If

final case class ForIn(name: VarName, words: List[Word], body: Block) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    ctx.emit(ShLines.of("for ") ++ ShLines.varName(name) + " in " ++ Word.spaceJoined(words) + "; do") :::
      body.lines(ctx.nested) ::: ctx.line("done")

  override def rawFragments: List[String] = words.flatMap(_.rawFragments) ++ body.rawFragments

final case class While(cond: ShTest, body: Block) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    ctx.emit(ShLines.of("while ") ++ cond.lines + "; do") ::: body.lines(ctx.nested) ::: ctx.line("done")

  override def rawFragments: List[String] = cond.rawFragments ++ body.rawFragments

/** @param quoted
  *   `<<'TAG'`, so the body is not expanded. On by default: an unexpanded body cannot have its `$` interpreted.
  */
final case class Heredoc(command: InlineCommand, tag: HeredocTag, body: List[ScriptLine], quoted: Boolean = true)
    extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val open = if quoted then s"<<'${tag.unwrap}'" else s"<<${tag.unwrap}"
    // The body and closing delimiter are column-zero: an indented delimiter needs <<- plus real tabs, the leading-tab
    // hazard ScriptLine exists to prevent.
    // composed: a HeredocTag is identifier-shaped, so it satisfies ScriptLine by construction.
    ctx.emit(command.inlineLines ++ ShLines.composed(s" $open")) ::: body ::: ShLines.composed(tag.unwrap).lines.toList

  override def rawFragments: List[String] = command.rawFragments
end Heredoc

final case class SetOpts(errexit: Boolean = true, nounset: Boolean = true, pipefail: Boolean = true) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val short = (if errexit then "e" else "") + (if nounset then "u" else "")
    if short.isEmpty && !pipefail then Nil
    else if pipefail && short.nonEmpty then ctx.emit(ShLines.composed(s"set -${short}o pipefail"))
    else if pipefail then ctx.line("set -o pipefail")
    else ctx.emit(ShLines.composed(s"set -$short"))

final case class Exit(code: ExitCode = ExitCode.Success) extends InlineCommand:
  def inlineLines: ShLines = ShLines.composed(s"exit ${code.unwrap}")

/** `# text`. Not an [[InlineCommand]]: in a pipeline leg it would comment out the command it was joined to. */
final case class Comment(text: ShText) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.emit(ShLines.of("# ") ++ ShLines.text(text))

object Comment:
  // @targetName because ShText erases to String, so this collides with the case class apply.
  @targetName("commentLiteral")
  inline def apply(inline text: String): Comment = Comment(ShText(text))

case object BlankLine extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = List(ScriptLine.empty)

/** **Escape hatch.** Verbatim lines, indented but otherwise untouched. [[ScriptLine]] keeps them from breaking the
  * YAML, not the shell: nothing checks `$` handling, quoting or exit status. [[Command.rawFragments]] reports the text
  * so `zipxWorkflowGenerate` warns. Prefer implementing [[Command]] for a construct you need repeatedly.
  */
final case class Raw(rawLines: List[ScriptLine]) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = rawLines.flatMap(l => ctx.emit(ShLines.one(l)))
  override def rawFragments: List[String]      = rawLines.map(_.unwrap)

object Raw:

  /** `Left` names the first offending line. */
  def make(text: String): Either[String, Raw] =
    val split = text.split("\n", -1).toList
    val bad   = split.map(ScriptLine.make).zipWithIndex.collectFirst { case (Left(err), i) => s"line ${i + 1}: $err" }
    bad match
      case Some(err) => Left(err)
      case None      => Right(Raw(split.map(ScriptLine.unsafeMake)))

/** **Escape hatch.** [[Raw]] for a single-command position: a list cannot promise one line, so this holds exactly one.
  */
final case class RawLine(rawLine: ScriptLine) extends InlineCommand:
  def inlineLines: ShLines                = ShLines.one(rawLine)
  override def rawFragments: List[String] = List(rawLine.unwrap)

object RawLine:

  // @targetName because ScriptLine erases to String, so this collides with the case class apply.
  @targetName("rawLineLiteral")
  inline def apply(inline text: String): RawLine = RawLine(ScriptLine(text))

  def make(text: String): Either[String, RawLine] = ScriptLine.make(text).map(RawLine(_))
