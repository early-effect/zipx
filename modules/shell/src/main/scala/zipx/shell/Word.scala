package zipx.shell

import neotype.unwrap

/** Explicit rather than inferred: the same characters need different escaping unquoted and inside `"…"`. */
enum Quoting:
  case Unquoted, InDouble

enum ParamMod:

  /** `\${name:-text}`: substitute when unset **or empty**. The form to use under `set -u`. */
  case Default(text: ParamText)

  /** `\${name-text}`: substitute only when unset; an empty value stays empty. */
  case DefaultIfUnset(text: ParamText)

  /** `\${name:+text}`: substitute when set and non-empty. */
  case Alt(text: ParamText)

  case StripPrefix(pattern: ParamText)

  case StripPrefixLong(pattern: ParamText)

  case StripSuffix(pattern: ParamText)

  case StripSuffixLong(pattern: ParamText)

  def render: String = this match
    case Default(t)         => s":-${t.unwrap}"
    case DefaultIfUnset(t)  => s"-${t.unwrap}"
    case Alt(t)             => s":+${t.unwrap}"
    case StripPrefix(p)     => s"#${p.unwrap}"
    case StripPrefixLong(p) => s"##${p.unwrap}"
    case StripSuffix(p)     => s"%${p.unwrap}"
    case StripSuffixLong(p) => s"%%${p.unwrap}"
end ParamMod

/** Nesting rules are types: only a [[Word.Quotable]] may appear inside `"…"`. [[Word.Squote]] is not one, because
  * single quotes nested in double quotes are literal characters rather than quoting.
  */
sealed trait Word:

  /** More than one line only for a [[Word.Subst]] of a command that wraps. */
  def lines(quoting: Quoting): ShLines

  def render: String = render(Quoting.Unquoted)

  final def render(quoting: Quoting): String = lines(quoting).render

  def rawFragments: List[String] = this match
    case Word.Dquote(parts)  => parts.flatMap(_.rawFragments)
    case Word.Cat(parts)     => parts.flatMap(_.rawFragments)
    case Word.Subst(command) => command.rawFragments
    case _                   => Nil
end Word

object Word:

  sealed trait Quotable extends Word

  /** Unquoted, metacharacters reach the shell as written (`refs/tags/v*` globs); inside `"…"` they are escaped to stay
    * literal.
    */
  final case class Lit(text: ShText) extends Quotable:
    def lines(quoting: Quoting): ShLines = quoting match
      case Quoting.Unquoted => ShLines.text(text)
      // Escaping only adds backslashes before printable characters, so the result is still one ScriptLine.
      case Quoting.InDouble => ShLines.composed(escapeInDouble(text.unwrap))

  final case class Squote(text: SquoteText) extends Word:
    def lines(quoting: Quoting): ShLines = ShLines.composed(s"'${text.unwrap}'")

  /** Nested inside another `Dquote` this emits `\"…\"`, the form a `--jq` argument needs. */
  final case class Dquote(parts: List[Quotable]) extends Quotable:
    def lines(quoting: Quoting): ShLines =
      val quote = ShLines.composed(if quoting == Quoting.Unquoted then "\"" else "\\\"")
      quote ++ ShLines.concatAll(parts.map(_.lines(Quoting.InDouble))) ++ quote

  /** @param braced
    *   force `\${name}` with no modifier, needed when a name character follows, as in `"\${release}x"`.
    */
  final case class VarRef(name: VarName, mod: Option[ParamMod] = None, braced: Boolean = false) extends Quotable:
    def lines(quoting: Quoting): ShLines = ShLines.composed(mod match
      case Some(m)        => s"$${${name.unwrap}${m.render}}"
      case None if braced => s"$${${name.unwrap}}"
      case None           => s"$$${name.unwrap}")

  /** Renders over as many lines as the command takes: `$(…)` is one of the few positions where the shell accepts a
    * wrapped command.
    */
  final case class Subst(command: Command) extends Quotable:
    def lines(quoting: Quoting): ShLines =
      ShLines.of("$(") ++ ShLines.fromLines(command.lines(Script.Ctx.root)) + ")"

  /** Escape hatch: never escaped or quoted, for a layered expression language whose `\${{ … }}` must keep its `$`.
    * [[ShText]] still keeps it from breaking the surrounding YAML.
    */
  final case class Opaque(rendered: ShText) extends Quotable:
    def lines(quoting: Quoting): ShLines = ShLines.text(rendered)

  final case class Cat(parts: List[Word]) extends Word:
    def lines(quoting: Quoting): ShLines = ShLines.concatAll(parts.map(_.lines(quoting)))

  // Literal constructors are `inline` so the newtype validates at compile time; the `*Make` siblings take runtime input
  // and return the error.

  inline def lit(inline text: String): Lit = Lit(ShText(text))

  def litMake(text: String): Either[String, Lit] = ShText.make(text).map(Lit(_))

  inline def squote(inline text: String): Squote = Squote(SquoteText(text))

  def squoteMake(text: String): Either[String, Squote] = SquoteText.make(text).map(Squote(_))

  def dquote(parts: Quotable*): Dquote = Dquote(parts.toList)

  inline def quoted(inline text: String): Dquote = Dquote(List(lit(text)))

  def quotedMake(text: String): Either[String, Dquote] = litMake(text).map(l => Dquote(List(l)))

  inline def v(inline name: String): VarRef = VarRef(VarName(name))

  def vMake(name: String): Either[String, VarRef] = VarName.make(name).map(VarRef(_))

  /** `"\$name"`: survives word splitting, so prefer it over [[v]] in argument position. */
  inline def vq(inline name: String): Dquote = Dquote(List(v(name)))

  def vqMake(name: String): Either[String, Dquote] = vMake(name).map(r => Dquote(List(r)))

  /** `\${name:-}`: reads an unset variable without tripping `set -u`. */
  inline def vOrEmpty(inline name: String): VarRef =
    VarRef(VarName(name), Some(ParamMod.Default(ParamText(""))))

  inline def vOrElse(inline name: String, inline default: String): VarRef =
    VarRef(VarName(name), Some(ParamMod.Default(ParamText(default))))

  inline def vStrip(inline name: String, inline prefix: String): VarRef =
    VarRef(VarName(name), Some(ParamMod.StripPrefix(ParamText(prefix))))

  inline def vBraced(inline name: String): VarRef = VarRef(VarName(name), None, braced = true)

  def subst(command: Command): Subst = Subst(command)

  def cat(parts: Word*): Cat = Cat(parts.toList)

  /** Escape hatch; see [[Word.Opaque]]. */
  inline def opaque(inline rendered: String): Opaque = Opaque(ShText(rendered))

  def opaqueMake(rendered: String): Either[String, Opaque] = ShText.make(rendered).map(Opaque(_))

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
