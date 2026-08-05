package zipx.shell

import neotype.unwrap
import zio.{Chunk, NonEmptyChunk}

/** The rendered form of one logical unit: a non-empty sequence of already-validated [[ScriptLine]]s.
  *
  * Non-empty because a word, a command and a test each render to *something*, and a value spelling "no lines" would
  * silently vanish from a script. That is structural rather than validated: [[NonEmptyChunk]] has no empty case, and
  * its `map` / `prepend` / `append` return one, so every operation here is total with nothing to assert.
  *
  * More than one line because a `\` continuation and a wrapped `$(…)` are one logical unit across several physical
  * ones. Everything in this module renders to one of these rather than to a `String`, so no stage splits text on
  * newlines and revalidates the pieces. `String` appears only at [[render]], the serialization boundary.
  */
final case class ShLines(lines: NonEmptyChunk[ScriptLine]):

  /** The physical lines joined: the text a shell reads. */
  def render: String = lines.toList.map(_.unwrap).mkString("\n")

  /** Concatenate, with `other` continuing this unit's *last* line, since that is where a pipe or a redirect attaches.
    * Contrast [[ShLines.stack]], which keeps the units on separate lines.
    */
  infix def ++(other: ShLines): ShLines =
    val joined = ShLines.join(lines.last, other.lines.head)
    ShLines(NonEmptyChunk.single(joined).prepend(lines.init).append(other.lines.tail))

  /** [[++]] a literal, checked while the calling file compiles. */
  inline infix def +(inline text: String): ShLines = this ++ ShLines.of(text)

  /** Prefix every line with `width` spaces, leaving blank lines blank so none gains trailing whitespace. */
  def indentBy(width: Int): ShLines =
    if width == 0 then this
    else
      val pad = ScriptLine.unsafeMake(" " * width)
      ShLines(lines.map(l => if l.unwrap.isEmpty then l else ShLines.join(pad, l)))

end ShLines

object ShLines:

  def one(line: ScriptLine): ShLines = ShLines(NonEmptyChunk.single(line))

  val empty: ShLines = one(ScriptLine.empty)

  /** One line from a literal, checked at compile time. */
  inline def of(inline text: String): ShLines = one(ScriptLine(text))

  /** One line from runtime text, reporting why it cannot be one. */
  def line(text: String): Either[String, ShLines] = ScriptLine.make(text).map(one)

  /** An [[ShText]] as one line. Total, because `ShText` validates exactly [[ScriptLine]]'s rules: this is the
    * conversion that subset relationship exists to make unconditional.
    */
  def text(value: ShText): ShLines = one(ScriptLine.unsafeMake(value.unwrap))

  /** A [[GlobPattern]] as one line. Total for the same reason as [[text]]: a pattern renders unquoted, so it already
    * excludes whitespace, quotes and control characters.
    */
  def pattern(value: GlobPattern): ShLines = one(ScriptLine.unsafeMake(value.unwrap))

  /** A [[VarName]] as one line, for a construct that names a variable outside `${…}`. Identifier-shaped, so total. */
  def varName(value: VarName): ShLines = one(ScriptLine.unsafeMake(value.unwrap))

  /** Units kept on separate physical lines, the shape a `\` continuation and a script body need. */
  def stack(head: ShLines, rest: List[ShLines]): ShLines =
    ShLines(head.lines.append(Chunk.fromIterable(rest).flatMap(_.lines.toChunk)))

  /** Already-validated lines as one unit, a command emitting none becoming a single blank line. Only [[Command]] needs
    * this: its `lines` is legitimately empty for a fully disabled [[SetOpts]], where a unit position is not.
    */
  private[shell] def fromLines(lines: List[ScriptLine]): ShLines =
    NonEmptyChunk.fromIterableOption(lines).fold(empty)(ShLines(_))

  /** Units concatenated with nothing between them, each continuing the previous one's last line. */
  def concatAll(units: List[ShLines]): ShLines = units.foldLeft(empty)(_ ++ _)

  /** Units concatenated with `separator` between them, the shape an argument list needs. */
  def joinAll(units: List[ShLines], separator: ShLines): ShLines = units match
    case Nil          => empty
    case head :: rest => rest.foldLeft(head)((acc, unit) => acc ++ separator ++ unit)

  /** One line the caller composed from validated pieces.
    *
    * `private[shell]` because the justification is per-call-site rather than universal: the composed text must satisfy
    * [[ScriptLine]] by its shape, as `'…'`, `${…}` and `exit 0` do. Use [[of]] for a literal and [[line]] for text
    * arriving from outside this module.
    */
  private[shell] def composed(text: String): ShLines = one(ScriptLine.unsafeMake(text))

  /** Neither side may hold a newline, a carriage return or a control character, and neither may start with a tab, so
    * their concatenation satisfies [[ScriptLine]] without being rechecked. This closure is why [[ShText]] carries the
    * leading-tab rule it does not otherwise need for its own sake.
    */
  private def join(left: ScriptLine, right: ScriptLine): ScriptLine =
    ScriptLine.unsafeMake(left.unwrap + right.unwrap)

end ShLines
