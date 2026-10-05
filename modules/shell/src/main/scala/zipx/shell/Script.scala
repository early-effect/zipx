package zipx.shell

import neotype.unwrap

/** @param trailingNewline
  *   whether [[render]] ends with a newline, which decides whether the YAML block scalar this lands in emits a blank
  *   line after the last command.
  */
final case class Script(commands: List[Command], trailingNewline: Boolean = false):

  /** Keeps the right-hand side's ending. */
  infix def ++(other: Script): Script =
    Script(commands ++ other.commands, other.trailingNewline)

  /** Keeps this script's ending. */
  infix def :+(command: Command): Script = Script(commands :+ command, trailingNewline)

  def withTrailingNewline(value: Boolean): Script = copy(trailingNewline = value)

  def lines: List[ScriptLine] = commands.flatMap(_.lines(Script.Ctx.root))

  def render: String =
    val body = lines.map(_.unwrap).mkString("\n")
    if trailingNewline then s"$body\n" else body

  def rawFragments: List[String] = commands.flatMap(_.rawFragments)
end Script

object Script:

  val empty: Script = Script(Nil)

  def apply(commands: Command*): Script = Script(commands.toList)

  /** Prefixes `set -euo pipefail`, the shape every generated script should start with. */
  def strict(commands: Command*): Script = Script(SetOpts() :: commands.toList)

  /** **Escape hatch.** See [[Raw]] for what this does and does not guarantee.
    */
  def raw(text: String): Either[String, Script] = Raw.make(text).map(r => Script(List(r)))

  /** [[Script]] owns depth so a [[Command]] never prepends spaces itself. */
  final case class Ctx(depth: Int):

    def nested: Ctx = Ctx(depth + 1)

    def indent(unit: ShLines): ShLines = unit.indentBy(depth * Ctx.IndentWidth)

    def emit(unit: ShLines): List[ScriptLine] = indent(unit).lines.toList

    /** For fixed keywords (`else`, `fi`, `done`); a [[Command]] with structure to render builds [[ShLines]]. */
    inline def line(inline text: String): List[ScriptLine] = emit(ShLines.of(text))
  end Ctx

  object Ctx:

    /** Matches `YamlPrinter.indentStep`. */
    val IndentWidth = 2

    val root: Ctx = Ctx(0)
end Script
