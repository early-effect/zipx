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

  /** This command as a single line, for positions that require one: `$(…)`, a pipeline leg, an `if` condition.
    *
    * Renders at depth zero and rejects a multi-line result, so a compound command used where the shell needs one line
    * fails at generate time rather than emitting broken shell. Named `inlineRender` because `inline` is a keyword.
    */
  def inlineRender: String =
    lines(Script.Ctx.root) match
      case single :: Nil => single.unwrap
      case many          =>
        throw IllegalArgumentException(
          s"command needs to render on one line here, but produced ${many.length}: ${many.map(_.unwrap).mkString("; ")}"
        )

  /** Unvalidated text carried by this command, if any. Non-empty only for [[Raw]] and commands wrapping it; drives the
    * generate-time warning about escape-hatch use.
    */
  def rawFragments: List[String] = Nil

  /** `this | other`. */
  infix def |(other: Command): Command = Pipe(this, other)

  /** `this && other`. */
  infix def &&(other: Command): Command = AndThen(this, other)

  /** `this || other`. */
  infix def ||(other: Command): Command = OrElse(this, other)

  /** `this > target`. */
  def writeTo(target: Word): Command = Redirect(this, target, append = false)

  /** `this >> target`. */
  def appendTo(target: Word): Command = Redirect(this, target, append = true)

  /** `this >/dev/null 2>&1`, the "run it, I only want the exit status" form. */
  def silenced: Command = Silence(this)

  /** `this 2>/dev/null`: discard stderr only. */
  def stderrSilenced: Command = SilenceErr(this)

end Command

/** A simple command: a program and its arguments, rendered space-separated.
  *
  * Nothing is quoted for you, because whether an argument needs quoting is a decision only the caller can make (`'v*'`
  * to suppress globbing versus `v*` to use it). Use [[Word.quoted]] / [[Word.vq]] to ask for quotes. The program is a
  * [[Word]] so `$SBT args` works; [[Command.exec]] validates a literal program name through [[ProgramName]].
  */
final case class Exec(program: Word, args: List[Word]) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    ctx.line((program :: args).map(_.render).mkString(" "))

  override def rawFragments: List[String] = (program :: args).flatMap(_.rawFragments)

object Exec:

  /** `program args…` with the program name validated at compile time.
    *
    * unsafeMake: ProgramName's character set is a subset of ShText's, so the conversion cannot fail. Nesting the two
    * `apply`s instead would ask neotype to comptime-evaluate `ProgramName(…).unwrap`, which it cannot parse.
    */
  inline def apply(inline program: String, args: Word*): Exec =
    Exec(Word.Lit(ShText.unsafeMake(ProgramName(program).unwrap)), args.toList)

/** `left | right`. */
final case class Pipe(left: Command, right: Command) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(s"${left.inlineRender} | ${right.inlineRender}")
  override def rawFragments: List[String]      = left.rawFragments ++ right.rawFragments

/** `left && right`. */
final case class AndThen(left: Command, right: Command) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(s"${left.inlineRender} && ${right.inlineRender}")
  override def rawFragments: List[String]      = left.rawFragments ++ right.rawFragments

/** `left || right`. */
final case class OrElse(left: Command, right: Command) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(s"${left.inlineRender} || ${right.inlineRender}")
  override def rawFragments: List[String]      = left.rawFragments ++ right.rawFragments

/** `command > target` or `command >> target`, optionally from a specific file descriptor (`2> log`). */
final case class Redirect(command: Command, target: Word, append: Boolean, from: Option[FileDescriptor] = None)
    extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val fd    = from.fold("")(_.unwrap.toString)
    val arrow = if append then ">>" else ">"
    ctx.line(s"${command.inlineRender} $fd$arrow ${target.render}")

  override def rawFragments: List[String] = command.rawFragments ++ target.rawFragments

/** `command 2>&1`: duplicate one file descriptor onto another. */
final case class RedirectFd(command: Command, from: FileDescriptor, to: FileDescriptor) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    ctx.line(s"${command.inlineRender} ${from.unwrap}>&${to.unwrap}")

  override def rawFragments: List[String] = command.rawFragments

/** `command >/dev/null 2>&1`: discard both streams, keep the exit status. */
final case class Silence(command: Command) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(s"${command.inlineRender} >/dev/null 2>&1")
  override def rawFragments: List[String]      = command.rawFragments

/** `command 2>/dev/null`: discard stderr only. */
final case class SilenceErr(command: Command) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(s"${command.inlineRender} 2>/dev/null")
  override def rawFragments: List[String]      = command.rawFragments

/** `name=value`, optionally `local` / `export` / `readonly`. */
final case class Assign(name: VarName, value: Word, scope: Assign.Scope = Assign.Scope.Plain) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    val prefix = scope match
      case Assign.Scope.Plain    => ""
      case Assign.Scope.Local    => "local "
      case Assign.Scope.Export   => "export "
      case Assign.Scope.ReadOnly => "readonly "
    ctx.line(s"$prefix${name.unwrap}=${value.render}")

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
final case class Heredoc(command: Command, tag: HeredocTag, body: List[ScriptLine], quoted: Boolean = true)
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
final case class Exit(code: ExitCode = ExitCode.Success) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] = ctx.line(s"exit ${code.unwrap}")

/** A shell comment (`# text`). In the AST because zipx's scripts explain themselves in CI logs. */
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
