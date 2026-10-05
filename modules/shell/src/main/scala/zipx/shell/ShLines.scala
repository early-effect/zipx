package zipx.shell

import neotype.unwrap
import zio.{Chunk, NonEmptyChunk}

/** One logical unit (a `\` continuation or a wrapped `$(…)` spans several physical lines). Non-empty so nothing renders
  * to a value that silently vanishes from a script. The module renders to this rather than `String`, so no stage
  * re-splits text on newlines and revalidates it; `String` appears only at [[render]].
  */
final case class ShLines(lines: NonEmptyChunk[ScriptLine]):

  def render: String = lines.toList.map(_.unwrap).mkString("\n")

  /** `other` continues this unit's *last* line, where a pipe or a redirect attaches. [[ShLines.stack]] keeps the units
    * on separate lines instead.
    */
  infix def ++(other: ShLines): ShLines =
    val joined = ShLines.join(lines.last, other.lines.head)
    ShLines(NonEmptyChunk.single(joined).prepend(lines.init).append(other.lines.tail))

  inline infix def +(inline text: String): ShLines = this ++ ShLines.of(text)

  /** Blank lines stay blank, so none gains trailing whitespace. */
  def indentBy(width: Int): ShLines =
    if width == 0 then this
    else
      val pad = ScriptLine.unsafeMake(" " * width)
      ShLines(lines.map(l => if l.unwrap.isEmpty then l else ShLines.join(pad, l)))

end ShLines

object ShLines:

  def one(line: ScriptLine): ShLines = ShLines(NonEmptyChunk.single(line))

  val empty: ShLines = one(ScriptLine.empty)

  inline def of(inline text: String): ShLines = one(ScriptLine(text))

  def line(text: String): Either[String, ShLines] = ScriptLine.make(text).map(one)

  /** Total: `ShText` validates exactly [[ScriptLine]]'s rules. */
  def text(value: ShText): ShLines = one(ScriptLine.unsafeMake(value.unwrap))

  /** Total: a [[GlobPattern]] already excludes whitespace, quotes and control characters. */
  def pattern(value: GlobPattern): ShLines = one(ScriptLine.unsafeMake(value.unwrap))

  /** Total: a [[VarName]] is identifier-shaped. */
  def varName(value: VarName): ShLines = one(ScriptLine.unsafeMake(value.unwrap))

  def stack(head: ShLines, rest: List[ShLines]): ShLines =
    ShLines(head.lines.append(Chunk.fromIterable(rest).flatMap(_.lines.toChunk)))

  /** No lines become one blank line: [[Command.lines]] may be empty (a disabled [[SetOpts]]) where a unit may not. */
  private[shell] def fromLines(lines: List[ScriptLine]): ShLines =
    NonEmptyChunk.fromIterableOption(lines).fold(empty)(ShLines(_))

  def concatAll(units: List[ShLines]): ShLines = units.foldLeft(empty)(_ ++ _)

  def joinAll(units: List[ShLines], separator: ShLines): ShLines = units match
    case Nil          => empty
    case head :: rest => rest.foldLeft(head)((acc, unit) => acc ++ separator ++ unit)

  /** Unchecked: each call site must compose text that satisfies [[ScriptLine]] by its shape (`'…'`, `exit 0`), hence
    * `private[shell]`. Use [[of]] for a literal and [[line]] for text from outside this module.
    */
  private[shell] def composed(text: String): ShLines = one(ScriptLine.unsafeMake(text))

  /** Neither side holds a newline, carriage return or control character, or starts with a tab, so the concatenation
    * satisfies [[ScriptLine]] unchecked. This closure is why [[ShText]] carries the leading-tab rule.
    */
  private def join(left: ScriptLine, right: ScriptLine): ScriptLine =
    ScriptLine.unsafeMake(left.unwrap + right.unwrap)

end ShLines
