package zipx.shell

import neotype.unwrap

/** The quoting context a [[Word]] renders into. Explicit rather than inferred: `'v*'` suppresses globbing where `"$v"`
  * expands, and the same characters need different escaping in each.
  */
enum Quoting:
  case Unquoted, InDouble

/** A parameter expansion modifier: the part after the name in `${name…}`. */
enum ParamMod:

  /** `${name:-text}`: substitute when unset **or empty**. The form to use under `set -u`. */
  case Default(text: ParamText)

  /** `${name-text}`: substitute only when unset; an empty value stays empty. */
  case DefaultIfUnset(text: ParamText)

  /** `${name:+text}`: substitute when set and non-empty. */
  case Alt(text: ParamText)

  /** `${name#pattern}`: remove the shortest matching prefix. */
  case StripPrefix(pattern: ParamText)

  /** `${name##pattern}`: remove the longest matching prefix. */
  case StripPrefixLong(pattern: ParamText)

  /** `${name%pattern}`: remove the shortest matching suffix. */
  case StripSuffix(pattern: ParamText)

  /** `${name%%pattern}`: remove the longest matching suffix. */
  case StripSuffixLong(pattern: ParamText)

  /** The modifier text that follows the name inside `${…}`. */
  def render: String = this match
    case Default(t)         => s":-${t.unwrap}"
    case DefaultIfUnset(t)  => s"-${t.unwrap}"
    case Alt(t)             => s":+${t.unwrap}"
    case StripPrefix(p)     => s"#${p.unwrap}"
    case StripPrefixLong(p) => s"##${p.unwrap}"
    case StripSuffix(p)     => s"%${p.unwrap}"
    case StripSuffixLong(p) => s"%%${p.unwrap}"
end ParamMod

/** A single word in command position: literal text, a quoted string, a variable expansion, a command substitution, or
  * an opaque pre-rendered fragment.
  *
  * Nesting rules are types: [[Word.Quotable]] marks the words that may appear inside `"…"`, and [[Word.Squote]] is not
  * one, because a single-quoted string nested in double quotes emits *literal* quote characters instead of quoting
  * anything. [[Word.Opaque]] is the seam for a higher layer's own expression language.
  */
sealed trait Word:

  /** The validated lines of this word in an explicit quoting context. More than one only for a [[Word.Subst]] of a
    * command that wraps, which is a position the shell accepts.
    */
  def lines(quoting: Quoting): ShLines

  /** Render as an unquoted word. */
  def render: String = render(Quoting.Unquoted)

  /** Render into an explicit quoting context. The serialization boundary; everything above it composes [[ShLines]]. */
  final def render(quoting: Quoting): String = lines(quoting).render

  /** Raw fragments carried by nested commands (a `Subst` of a `Raw`), for the generate-time warning. */
  def rawFragments: List[String] = this match
    case Word.Dquote(parts)  => parts.flatMap(_.rawFragments)
    case Word.Cat(parts)     => parts.flatMap(_.rawFragments)
    case Word.Subst(command) => command.rawFragments
    case _                   => Nil
end Word

object Word:

  /** A word that is safe to nest inside a double-quoted string. */
  sealed trait Quotable extends Word

  /** Verbatim text: flags (`--tags`), paths (`~/.gnupg/gpg.conf`), and deliberate globs (`refs/tags/v*`). Unquoted the
    * shell sees the characters as written, metacharacters included; inside `"…"` they are escaped to stay literal.
    */
  final case class Lit(text: ShText) extends Quotable:
    def lines(quoting: Quoting): ShLines = quoting match
      case Quoting.Unquoted => ShLines.text(text)
      // Escaping only adds backslashes before printable characters, so the result is still one ScriptLine.
      case Quoting.InDouble => ShLines.composed(escapeInDouble(text.unwrap))

  /** A single-quoted string, `'text'`: no expansion of any kind. Not [[Quotable]]; see [[Word]]. */
  final case class Squote(text: SquoteText) extends Word:
    def lines(quoting: Quoting): ShLines = ShLines.composed(s"'${text.unwrap}'")

  /** A double-quoted string, `"…"`. The parts list is the concatenation, so `"${release}-ci"` is a `Dquote` of two
    * parts. Nested inside another `Dquote` this emits `\"…\"`, the form a `--jq` argument needs.
    */
  final case class Dquote(parts: List[Quotable]) extends Quotable:
    def lines(quoting: Quoting): ShLines =
      val quote = ShLines.composed(if quoting == Quoting.Unquoted then "\"" else "\\\"")
      quote ++ ShLines.concatAll(parts.map(_.lines(Quoting.InDouble))) ++ quote

  /** A parameter expansion: `$name`, `${name}`, or `${name…}` with a [[ParamMod]].
    *
    * @param braced
    *   force `${name}` with no modifier, needed when a name character follows, as in `"${release}x"`.
    */
  final case class VarRef(name: VarName, mod: Option[ParamMod] = None, braced: Boolean = false) extends Quotable:
    def lines(quoting: Quoting): ShLines = ShLines.composed(mod match
      case Some(m)        => s"$${${name.unwrap}${m.render}}"
      case None if braced => s"$${${name.unwrap}}"
      case None           => s"$$${name.unwrap}")

  /** A command substitution, `$(command)`.
    *
    * Renders the command over as many lines as it takes rather than forcing one: `$(…)` is one of the few positions
    * where the shell accepts a wrapped command, and the closing paren lands on the last line.
    */
  final case class Subst(command: Command) extends Quotable:
    def lines(quoting: Quoting): ShLines =
      ShLines.of("$(") ++ ShLines.fromLines(command.lines(Script.Ctx.root)) + ")"

  /** Escape hatch: text emitted verbatim in every quoting context, never escaped and never quoted.
    *
    * The seam for an expression language layered on top, whose `${{ … }}` must not have its `$` escaped. [[ShText]]
    * still guarantees it cannot break the surrounding YAML.
    */
  final case class Opaque(rendered: ShText) extends Quotable:
    def lines(quoting: Quoting): ShLines = ShLines.text(rendered)

  /** Concatenation with no separator, for mixing quote styles: `'literal'"$expanded"`. */
  final case class Cat(parts: List[Word]) extends Word:
    def lines(quoting: Quoting): ShLines = ShLines.concatAll(parts.map(_.lines(quoting)))

  // Literal constructors are `inline` so the newtype validates at compile time; the `*Make` siblings take runtime input
  // and return the error.

  /** `Word.Lit` from a literal, checked at compile time. */
  inline def lit(inline text: String): Lit = Lit(ShText(text))

  def litMake(text: String): Either[String, Lit] = ShText.make(text).map(Lit(_))

  /** `'text'`, checked at compile time. */
  inline def squote(inline text: String): Squote = Squote(SquoteText(text))

  def squoteMake(text: String): Either[String, Squote] = SquoteText.make(text).map(Squote(_))

  /** `"…"` from already-typed parts. */
  def dquote(parts: Quotable*): Dquote = Dquote(parts.toList)

  /** `"text"`, the common case of a double-quoted literal. */
  inline def quoted(inline text: String): Dquote = Dquote(List(lit(text)))

  def quotedMake(text: String): Either[String, Dquote] = litMake(text).map(l => Dquote(List(l)))

  /** `$name`. */
  inline def v(inline name: String): VarRef = VarRef(VarName(name))

  def vMake(name: String): Either[String, VarRef] = VarName.make(name).map(VarRef(_))

  /** `"$name"`, the form that survives word splitting. Prefer this over [[v]] in argument position. */
  inline def vq(inline name: String): Dquote = Dquote(List(v(name)))

  def vqMake(name: String): Either[String, Dquote] = vMake(name).map(r => Dquote(List(r)))

  /** `${name:-}`, the form used under `set -u`. */
  inline def vOrEmpty(inline name: String): VarRef =
    VarRef(VarName(name), Some(ParamMod.Default(ParamText(""))))

  /** `${name:-default}`. */
  inline def vOrElse(inline name: String, inline default: String): VarRef =
    VarRef(VarName(name), Some(ParamMod.Default(ParamText(default))))

  /** `${name#prefix}`, shortest-prefix removal. */
  inline def vStrip(inline name: String, inline prefix: String): VarRef =
    VarRef(VarName(name), Some(ParamMod.StripPrefix(ParamText(prefix))))

  /** `${name}`, braced with no modifier. */
  inline def vBraced(inline name: String): VarRef = VarRef(VarName(name), None, braced = true)

  /** `$(command)`. */
  def subst(command: Command): Subst = Subst(command)

  /** Concatenate words with no separator. */
  def cat(parts: Word*): Cat = Cat(parts.toList)

  /** Escape hatch: verbatim text, never escaped and never quoted. See [[Word.Opaque]]. */
  inline def opaque(inline rendered: String): Opaque = Opaque(ShText(rendered))

  def opaqueMake(rendered: String): Either[String, Opaque] = ShText.make(rendered).map(Opaque(_))

  /** Words separated by a single space: an argv, a `for` word list. */
  def spaceJoined(words: List[Word]): ShLines =
    ShLines.joinAll(words.map(_.lines(Quoting.Unquoted)), ShLines.of(" "))

  /** Inside `"…"` the shell still acts on `$`, backtick, `\` and `"`, so a literal must escape all four. */
  private def escapeInDouble(text: String): String =
    val sb = new StringBuilder(text.length + 8)
    text.foreach { c =>
      if c == '\\' || c == '"' || c == '$' || c == '`' then sb.append('\\')
      sb.append(c)
    }
    sb.toString

end Word
