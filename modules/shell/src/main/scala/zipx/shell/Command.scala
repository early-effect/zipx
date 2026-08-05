package zipx.shell

import neotype.unwrap

import scala.annotation.targetName

/** A non-empty sequence of commands: the body of an `if` branch or a loop. Non-emptiness is structural, so
  * `if cond; then fi` has no value that spells it.
  */
final case class Block(head: Command, rest: List[Command]):
  def commands: List[Command]                  = head :: rest
  def lines(ctx: Script.Ctx): List[ScriptLine] = commands.flatMap(_.lines(ctx))
  def rawFragments: List[String]               = commands.flatMap(_.rawFragments)

object Block:
  def apply(head: Command, rest: Command*): Block = Block(head, rest.toList)

/** One shell statement: the unit [[Script]] renders line by line.
  *
  * Open on purpose, so a consumer can add a construct zipx does not model. An implementation must emit lines via
  * `ctx.line` / `ctx.nested` rather than prepending spaces itself ([[Script]] owns depth), return one entry per
  * physical line, and override [[rawFragments]] if it carries unvalidated text.
  * {{{
  * final case class WhileRead(name: VarName, body: Block) extends Command:
  *   def lines(ctx: Script.Ctx): List[ScriptLine] =
  *     ctx.line(s"while read -r ${name.unwrap}; do") ::: body.lines(ctx.nested) ::: ctx.line("done")
  * }}}
  */
trait Command:

  /** The physical lines this command contributes, indented for `ctx`. */
  def lines(ctx: Script.Ctx): List[ScriptLine]

  /** This command's own lines joined, for a position that accepts more than one. Renders at depth zero; the surrounding
    * depth is added by whoever emits it.
    */
  def render: String = lines(Script.Ctx.root).map(_.unwrap).mkString("\n")

  /** Unvalidated text carried by this command. Drives the generate-time warning about escape-hatch use. */
  def rawFragments: List[String] = Nil

end Command

/** A command the shell accepts where it wants *one* command: a pipeline leg, an `if` condition, the left side of a
  * redirect, the command a heredoc feeds. A compound command (`if`, `for`, `while`) needs `;` separators there and the
  * renderer does not insert them, so those positions take an `InlineCommand` and `Exec("wc") | If(…)` does not compile.
  *
  * "One command" is logical, not physical: [[Continued]] spans several lines and is still legal in every one of these
  * positions, and [[Script.Ctx.line]] splits the result.
  */
trait InlineCommand extends Command:

  /** This command as one logical command. */
  def inlineRender: String

  final def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(inlineRender)

  /** `this | other`. */
  infix def |(other: InlineCommand): InlineCommand = Pipe(this, other)

  /** `this && other`. */
  infix def &&(other: InlineCommand): InlineCommand = AndThen(this, other)

  /** `this || other`. */
  infix def ||(other: InlineCommand): InlineCommand = OrElse(this, other)

  /** `this > target`. */
  def writeTo(target: Word): InlineCommand = Redirect(this, target, append = false)

  /** `this >> target`. */
  def appendTo(target: Word): InlineCommand = Redirect(this, target, append = true)

  /** `this >/dev/null 2>&1`, the "run it, I only want the exit status" form. */
  def silenced: InlineCommand = Silence(this)

  /** `this 2>/dev/null`: discard stderr only. */
  def stderrSilenced: InlineCommand = SilenceErr(this)

end InlineCommand

/** A simple command: a program and its arguments, rendered space-separated.
  *
  * Nothing is quoted for you, because whether an argument needs quoting is a decision only the caller can make (`'v*'`
  * to suppress globbing versus `v*` to use it). Use [[Word.quoted]] / [[Word.vq]] to ask for quotes.
  */
final case class Exec(program: Word, args: List[Word]) extends InlineCommand:
  def inlineRender: String = (program :: args).map(_.render).mkString(" ")

  override def rawFragments: List[String] = (program :: args).flatMap(_.rawFragments)

object Exec:

  /** `program args…` with the program name validated at compile time.
    *
    * unsafeMake: ProgramName's character set is a subset of ShText's, so the conversion cannot fail. Nesting the two
    * `apply`s instead would ask neotype to comptime-evaluate `ProgramName(…).unwrap`, which it cannot parse.
    */
  inline def apply(inline program: String, args: Word*): Exec =
    Exec(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), args.toList)

  /** [[apply]] for an argv assembled at runtime: a varargs splat cannot pass through the `inline` overload. */
  inline def of(inline program: String, args: List[Word]): Exec =
    Exec(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), args)
end Exec

/** One command spread over several physical lines with `\` continuations.
  *
  * Purely presentational, but modelled because a hand-written continuation breaks silently: trailing whitespace after
  * the `\` kills it, and the last line must not carry one.
  *
  * @param continuationIndent
  *   spaces prefixed to each line after the first, on top of the script's own depth.
  */
final case class Continued(program: Word, argLines: List[List[Word]], continuationIndent: Int = 2)
    extends InlineCommand:
  def inlineRender: String =
    val pad      = " " * continuationIndent
    val rendered = argLines.map(_.map(_.render).mkString(" "))
    val first    = (program.render :: rendered.headOption.toList).mkString(" ")
    val rest     = rendered.drop(1).map(pad + _)
    // Counted off the emitted lines rather than off `argLines`, so a program with no arguments is one unterminated line.
    val emitted = first :: rest
    emitted.zipWithIndex
      .map((text, i) => if i == emitted.length - 1 then text else s"$text \\")
      .mkString("\n")
  end inlineRender

  override def rawFragments: List[String] = (program :: argLines.flatten).flatMap(_.rawFragments)
end Continued

object Continued:

  /** `program args… \` continued on further lines, with the program name validated at compile time. */
  inline def apply(inline program: String, argLines: List[List[Word]]): Continued =
    Continued(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), argLines)

/** `left | right`. */
final case class Pipe(left: InlineCommand, right: InlineCommand) extends InlineCommand:
  def inlineRender: String                = s"${left.inlineRender} | ${right.inlineRender}"
  override def rawFragments: List[String] = left.rawFragments ++ right.rawFragments

/** `left && right`. */
final case class AndThen(left: InlineCommand, right: InlineCommand) extends InlineCommand:
  def inlineRender: String                = s"${left.inlineRender} && ${right.inlineRender}"
  override def rawFragments: List[String] = left.rawFragments ++ right.rawFragments

/** `left || right`. */
final case class OrElse(left: InlineCommand, right: InlineCommand) extends InlineCommand:
  def inlineRender: String                = s"${left.inlineRender} || ${right.inlineRender}"
  override def rawFragments: List[String] = left.rawFragments ++ right.rawFragments

/** `command > target` or `command >> target`, optionally from a specific file descriptor (`2> log`). */
final case class Redirect(command: InlineCommand, target: Word, append: Boolean, from: Option[FileDescriptor] = None)
    extends InlineCommand:
  def inlineRender: String =
    val fd    = from.fold("")(_.unwrap.toString)
    val arrow = if append then ">>" else ">"
    s"${command.inlineRender} $fd$arrow ${target.render}"

  override def rawFragments: List[String] = command.rawFragments ++ target.rawFragments

/** `command 2>&1`: duplicate one file descriptor onto another. */
final case class RedirectFd(command: InlineCommand, from: FileDescriptor, to: FileDescriptor) extends InlineCommand:
  def inlineRender: String                = s"${command.inlineRender} ${from.unwrap}>&${to.unwrap}"
  override def rawFragments: List[String] = command.rawFragments

/** `command >/dev/null 2>&1`: discard both streams, keep the exit status. */
final case class Silence(command: InlineCommand) extends InlineCommand:
  def inlineRender: String                = s"${command.inlineRender} >/dev/null 2>&1"
  override def rawFragments: List[String] = command.rawFragments

/** `command 2>/dev/null`: discard stderr only. */
final case class SilenceErr(command: InlineCommand) extends InlineCommand:
  def inlineRender: String                = s"${command.inlineRender} 2>/dev/null"
  override def rawFragments: List[String] = command.rawFragments

/** `name=value`, optionally `local` / `export` / `readonly`. */
final case class Assign(name: VarName, value: Word, scope: Assign.Scope = Assign.Scope.Plain) extends InlineCommand:
  def inlineRender: String =
    val prefix = scope match
      case Assign.Scope.Plain    => ""
      case Assign.Scope.Local    => "local "
      case Assign.Scope.Export   => "export "
      case Assign.Scope.ReadOnly => "readonly "
    s"$prefix${name.unwrap}=${value.render}"

  override def rawFragments: List[String] = value.rawFragments
end Assign

object Assign:
  enum Scope:
    case Plain, Local, Export, ReadOnly

  /** `name=value` with the name validated at compile time. */
  inline def apply(inline name: String, value: Word): Assign = Assign(VarName(name), value)

/** `if …; then … [elif …; then …] [else …] fi`. */
final case class If(
    cond: ShTest,
    thenDo: Block,
    elifs: List[(ShTest, Block)] = Nil,
    elseDo: Option[Block] = None,
) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val inner = ctx.nested
    val head  = ctx.line(s"if ${cond.render}; then") ::: thenDo.lines(inner)
    val mid   = elifs.flatMap((c, body) => ctx.line(s"elif ${c.render}; then") ::: body.lines(inner))
    val tail  = elseDo.fold(List.empty[ScriptLine])(body => ctx.line("else") ::: body.lines(inner))
    head ++ mid ++ tail ++ ctx.line("fi")

  override def rawFragments: List[String] =
    cond.rawFragments ++ thenDo.rawFragments ++
      elifs.flatMap((c, body) => c.rawFragments ++ body.rawFragments) ++
      elseDo.fold(Nil)(_.rawFragments)
end If

/** `for name in words…; do … done`. */
final case class ForIn(name: VarName, words: List[Word], body: Block) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    ctx.line(s"for ${name.unwrap} in ${words.map(_.render).mkString(" ")}; do") :::
      body.lines(ctx.nested) ::: ctx.line("done")

  override def rawFragments: List[String] = words.flatMap(_.rawFragments) ++ body.rawFragments

/** `while cond; do … done`. */
final case class While(cond: ShTest, body: Block) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    ctx.line(s"while ${cond.render}; do") ::: body.lines(ctx.nested) ::: ctx.line("done")

  override def rawFragments: List[String] = cond.rawFragments ++ body.rawFragments

/** `command <<'TAG' … TAG`: a here-document.
  *
  * @param quoted
  *   quote the opening delimiter (`<<'TAG'`) so the body is *not* expanded. Default true, because an unexpanded heredoc
  *   is the safe one: it cannot have its `$` interpreted by the shell.
  */
final case class Heredoc(command: InlineCommand, tag: HeredocTag, body: List[ScriptLine], quoted: Boolean = true)
    extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val open = if quoted then s"<<'${tag.unwrap}'" else s"<<${tag.unwrap}"
    // The body and closing delimiter are column-zero: an indented delimiter needs <<- plus real tabs, the leading-tab
    // hazard ScriptLine exists to prevent.
    // unsafeMake: a HeredocTag is identifier-shaped, so it satisfies ScriptLine by construction.
    ctx.line(s"${command.inlineRender} $open") ::: body ::: List(ScriptLine.unsafeMake(tag.unwrap))

  override def rawFragments: List[String] = command.rawFragments
end Heredoc

/** `set -euo pipefail` and friends: fail fast, fail on unset, fail on a broken pipe. */
final case class SetOpts(errexit: Boolean = true, nounset: Boolean = true, pipefail: Boolean = true) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val short = (if errexit then "e" else "") + (if nounset then "u" else "")
    if short.isEmpty && !pipefail then Nil
    else if pipefail && short.nonEmpty then ctx.line(s"set -${short}o pipefail")
    else if pipefail then ctx.line("set -o pipefail")
    else ctx.line(s"set -$short")

/** `exit <code>`. */
final case class Exit(code: ExitCode = ExitCode.Success) extends InlineCommand:
  def inlineRender: String = s"exit ${code.unwrap}"

/** A shell comment (`# text`).
  *
  * Not an [[InlineCommand]]: `#` comments out the rest of the line, so one in a pipeline leg would swallow the command
  * it was joined to.
  */
final case class Comment(text: ShText) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(s"# ${text.unwrap}")

object Comment:
  // @targetName because ShText erases to String, so this collides with the case class apply.
  @targetName("commentLiteral")
  inline def apply(inline text: String): Comment = Comment(ShText(text))

/** A blank line, for readability in the generated script. */
case object BlankLine extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = List(ScriptLine.empty)

/** **Escape hatch.** Verbatim lines, indented but otherwise untouched.
  *
  * The lines are [[ScriptLine]]s, so raw content cannot break the YAML. It can still be broken *shell*: nothing here
  * validates `$` handling, quoting or exit status. The text is reported through [[Command.rawFragments]] so
  * `zipxWorkflowGenerate` warns, naming the step that used it.
  *
  * Prefer implementing [[Command]] for a construct you need repeatedly: an implementation is checked, composable, and
  * reusable, where `Raw` is none of the three.
  */
final case class Raw(rawLines: List[ScriptLine]) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = rawLines.map(l => ctx.indent(l))
  override def rawFragments: List[String]      = rawLines.map(_.unwrap)

object Raw:

  /** Split a block of text into raw lines, validating each. `Left` names the offending line. */
  def make(text: String): Either[String, Raw] =
    val split = text.split("\n", -1).toList
    val bad   = split.map(ScriptLine.make).zipWithIndex.collectFirst { case (Left(err), i) => s"line ${i + 1}: $err" }
    bad match
      case Some(err) => Left(err)
      case None      => Right(Raw(split.map(ScriptLine.unsafeMake)))

/** **Escape hatch.** One verbatim line, usable where the shell wants a single command. Separate from [[Raw]] because a
  * list cannot promise one line; the guarantees are otherwise the same.
  */
final case class RawLine(rawLine: ScriptLine) extends InlineCommand:
  def inlineRender: String                = rawLine.unwrap
  override def rawFragments: List[String] = List(rawLine.unwrap)

object RawLine:

  /** A raw single-line command from a literal, checked at compile time. */
  // @targetName because ScriptLine erases to String, so this collides with the case class apply.
  @targetName("rawLineLiteral")
  inline def apply(inline text: String): RawLine = RawLine(ScriptLine(text))

  def make(text: String): Either[String, RawLine] = ScriptLine.make(text).map(RawLine(_))
