package zipx.shell

import neotype.unwrap

import scala.annotation.targetName

/** A non-empty sequence of commands: the body of an `if` branch or a loop.
  *
  * Non-emptiness is structural (a head plus a tail) rather than a checked `List`, because `if cond; then fi` is a
  * syntax error in every shell. The varargs `apply` keeps it readable: `Block(cmd1, cmd2)`.
  */
final case class Block(head: Command, rest: List[Command]):
  def commands: List[Command]                  = head :: rest
  def lines(ctx: Script.Ctx): List[ScriptLine] = commands.flatMap(_.lines(ctx))
  def rawFragments: List[String]               = commands.flatMap(_.rawFragments)

object Block:
  def apply(head: Command, rest: Command*): Block = Block(head, rest.toList)

/** One shell statement: the unit [[Script]] renders line by line.
  *
  * **This trait is deliberately open, not an `enum`.** The built-in cases cover what zipx's own generated scripts need,
  * but a consumer with a construct zipx does not model (a `case` statement, a `select`, a function definition)
  * implements `Command` in their own build or pack rather than waiting on a zipx release. The cost is that pattern
  * matches over `Command` cannot be exhaustive, which is why rendering is a *method* here instead of a match in
  * [[Script]].
  *
  * Implementing one:
  * {{{
  * final case class WhileRead(name: VarName, body: Block) extends Command:
  *   def lines(ctx: Script.Ctx): List[ScriptLine] =
  *     ctx.line(s"while read -r ${name.unwrap}; do") :: body.lines(ctx.nested) ::: List(ctx.line("done"))
  * }}}
  *
  * Contract for an implementation:
  *   - Emit lines via `ctx.line` / `ctx.nested`, never by prepending spaces yourself: [[Script]] owns depth so nested
  *     bodies line up, and [[ScriptLine]] is what guarantees the result is safe to embed in a YAML block scalar.
  *   - Return one entry per physical line.
  *   - Override [[rawFragments]] if the command carries unvalidated text, so `zipxWorkflowGenerate` can warn about it.
  */
trait Command:

  /** The physical lines this command contributes, indented for `ctx`. */
  def lines(ctx: Script.Ctx): List[ScriptLine]

  /** This command's own lines joined, for a position that accepts more than one: a `$(…)` substitution, or a
    * [[Script]].
    *
    * Renders at depth zero, so relative indentation inside the command survives while the surrounding depth is added by
    * whoever emits it.
    */
  def render: String = lines(Script.Ctx.root).map(_.unwrap).mkString("\n")

  /** Unvalidated text carried by this command, if any. Non-empty only for [[Raw]] and commands wrapping it; drives the
    * generate-time warning about escape-hatch use.
    */
  def rawFragments: List[String] = Nil

end Command

/** A command the shell accepts where it wants *one* command: a pipeline leg, an `if` condition, the left side of a
  * redirect, the command a heredoc feeds.
  *
  * The distinction is a type rather than a check. A compound command (`if`, `for`, `while`) needs `;` separators to sit
  * in those positions, and the renderer does not insert them, so `Exec("wc") | If(…)` would emit broken shell. Making
  * the position take an `InlineCommand` means it does not compile instead: there is no failure value to handle, no
  * generate-time error to surface, and the mistake is caught at the construction site.
  *
  * "One command" is logical, not physical. A `\` continuation and a `$(…)` substitution that wraps both occupy several
  * physical lines and are both legal in every one of these positions, so [[Continued]] is an `InlineCommand` and
  * [[Script.Ctx.line]] splits the result.
  *
  * Implementations define [[inlineRender]] and get [[Command.lines]] for free, which is what keeps the promise
  * structural: a single string is the only thing an implementation can produce.
  */
trait InlineCommand extends Command:

  /** This command as one logical command. Total: there is no case that cannot be rendered here. */
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
  * to suppress globbing versus `v*` to use it). Use [[Word.quoted]] / [[Word.vq]] to ask for quotes. The program is a
  * [[Word]] so `$SBT args` works; [[Command.exec]] validates a literal program name through [[ProgramName]].
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

  /** [[apply]] for an argv assembled at runtime. A varargs splat cannot pass through the `inline` overload, and a list
    * is what a caller mapping over paths or module ids already has.
    */
  inline def of(inline program: String, args: List[Word]): Exec =
    Exec(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), args)
end Exec

/** One command spread over several physical lines with `\` continuations.
  *
  * Purely presentational: the shell joins the lines back into one command, so this changes nothing but readability, and
  * a long `gh api --jq …` is far easier to read wrapped. Modelled rather than left to the caller because a continuation
  * is exactly the kind of thing that goes wrong by hand: trailing whitespace after the `\` silently breaks it, and the
  * *last* line must not carry one.
  *
  * Still an [[InlineCommand]]: a continuation is one *logical* command, and the shell accepts it in a pipeline leg or
  * an `if` condition exactly as it accepts an [[Exec]]. `Script.Ctx.line` splits the rendered result back into physical
  * lines and indents each.
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
    // Every line but the last ends with ` \`; the last must not, or the shell swallows the following line. Counted off
    // the emitted lines rather than off `argLines`, so a program with no arguments is one unterminated line.
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

/** `if …; then … [elif …; then …] [else …] fi`.
  *
  * Bodies are [[Block]]s, so an empty branch is unconstructible, and they render one nesting level deeper so [[Script]]
  * owns the indentation.
  */
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
    // The body and closing delimiter are column-zero: an indented delimiter only works with <<- plus real tabs,
    // which is precisely the leading-tab hazard ScriptLine exists to prevent.
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

/** A shell comment (`# text`). In the AST because zipx's scripts explain themselves in CI logs.
  *
  * Deliberately not an [[InlineCommand]]: `#` comments out the rest of the line, so a comment in a pipeline leg or an
  * `if` condition swallows the command it was joined to. It is text between commands, not a command.
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
  * The lines are [[ScriptLine]]s, so raw content cannot produce YAML GitHub fails to parse (no `\r`, no C0 control
  * characters, no leading tab): the type is the guard, there is no separate lint step to forget. What it *can* produce
  * is broken shell, since nothing here understands the text. Two consequences worth knowing:
  *
  *   - The lines are reported by [[Command.rawFragments]], and `zipxWorkflowGenerate` logs a warning naming the step
  *     that used them. The warning is the point: raw usage should be visible in the build log, not silent.
  *   - Nothing validates `$` handling, quoting, or exit-status behaviour. A `$$` that means "PID" to bash and "escaped
  *     dollar" to a Scala interpolator is exactly the class of bug this module exists to remove, and `Raw` opts back
  *     in.
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

/** **Escape hatch.** One verbatim line, usable where the shell wants a single command.
  *
  * The inline-position sibling of [[Raw]], and separate from it for the reason the split exists: [[Raw]] holds a list,
  * so it cannot promise one line, while a pipeline leg or an `if` condition needs exactly that. Same guarantees
  * otherwise: [[ScriptLine]] keeps it from breaking the YAML, nothing checks that it is valid *shell*, and the text is
  * reported via [[Command.rawFragments]] so `zipxWorkflowGenerate` warns.
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
