package zipx.shell

import neotype.unwrap

/** A shell program: a list of [[Command]]s plus how the rendered text ends.
  *
  * @param trailingNewline
  *   whether [[render]] ends with a newline, which decides whether the YAML block scalar this lands in emits a blank
  *   line after the last command.
  */
final case class Script(commands: List[Command], trailingNewline: Boolean = false):

  /** Append, keeping the right-hand side's ending. */
  infix def ++(other: Script): Script =
    Script(commands ++ other.commands, other.trailingNewline)

  /** Append commands, keeping this script's ending. */
  infix def :+(command: Command): Script = Script(commands :+ command, trailingNewline)

  def withTrailingNewline(value: Boolean): Script = copy(trailingNewline = value)

  /** The validated lines, at top-level indentation. */
  def lines: List[ScriptLine] = commands.flatMap(_.lines(Script.Ctx.root))

  /** The exact string that lands in a step's `run:`. */
  def render: String =
    val body = lines.map(_.unwrap).mkString("\n")
    if trailingNewline then s"$body\n" else body

  /** Escape-hatch text anywhere in this script, for the generate-time warning. Empty unless [[Raw]] was used. */
  def rawFragments: List[String] = commands.flatMap(_.rawFragments)
end Script

object Script:

  val empty: Script = Script(Nil)

  def apply(commands: Command*): Script = Script(commands.toList)

  /** `set -euo pipefail` followed by `commands`, the shape every generated script should start with. */
  def strict(commands: Command*): Script = Script(SetOpts() :: commands.toList)

  /** **Escape hatch.** A script from verbatim text, split on newlines and validated line by line. `Left` names the
    * offending line. See [[Raw]] for what this does and does not guarantee.
    */
  def raw(text: String): Either[String, Script] = Raw.make(text).map(r => Script(List(r)))

  /** Indentation state threaded through [[Command.lines]]. [[Script]] owns depth so a [[Command]] never prepends spaces
    * itself, and every line goes out through [[ScriptLine]].
    */
  final case class Ctx(depth: Int):

    /** One level deeper, for a nested body. */
    def nested: Ctx = Ctx(depth + 1)

    /** Indent and validate `text` as one logical line. Text spanning several physical lines is split and each part
      * indented and validated, since a `\` continuation or a wrapped `$(…)` is one logical command over several.
      */
    def line(text: String): List[ScriptLine] =
      text.split("\n", -1).toList.map(part => indent(ScriptLine.makeOrThrow(part)))

    /** Indent an already-validated line. Indentation is spaces, never tabs. */
    def indent(line: ScriptLine): ScriptLine =
      if depth == 0 || line.unwrap.isEmpty then line
      else ScriptLine.unsafeMake(" " * (depth * Ctx.IndentWidth) + line.unwrap)
  end Ctx

  object Ctx:

    /** Two spaces, matching `YamlPrinter.indentStep`. */
    val IndentWidth = 2

    val root: Ctx = Ctx(0)
end Script
