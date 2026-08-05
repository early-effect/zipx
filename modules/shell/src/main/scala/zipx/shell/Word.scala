package zipx.shell

import neotype.unwrap

/** The quoting context a [[Word]] renders into.
  *
  * Quoting is explicit in the AST rather than inferred: the shell's two quote styles are not interchangeable, since
  * `'v*'` suppresses globbing while `"$v"` expands a variable, and the same characters need different escaping in each.
  */
enum Quoting:
  case Unquoted, InDouble

/** A parameter expansion modifier: the part after the name in `${name…}`.
  *
  * Modelled as a closed set rather than as separate optional fields so mutually exclusive modifiers are
  * unconstructible. `${name:-a#b}` has one meaning, and a `VarRef` carrying both a default and a prefix-strip has none.
  */
enum ParamMod:

  /** `${name:-text}`: substitute when unset **or empty**. The form to use under `set -u`. */
  case Default(text: ParamText)

  /** `${name-text}`: substitute only when unset. An empty value stays empty. */
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

  /** Renders the modifier text that follows the name inside `${…}`. */
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
  * A sealed hierarchy rather than an `enum` so nesting rules are types: [[Word.Quotable]] marks the words that may
  * appear inside `"…"`. [[Word.Squote]] is deliberately not `Quotable`, because a single-quoted string nested in double
  * quotes emits *literal* quote characters instead of quoting anything, which is nearly always a bug. Combined with
  * [[ParamMod]] this makes rendering total: no case of `render` can throw.
  *
  * The set of ways the shell can spell one word is fixed by its grammar, so the hierarchy is closed. [[Word.Opaque]] is
  * the seam for anything a higher layer needs to inject verbatim: `zipx-workflow` fills it from its GitHub Actions
  * expression AST, which is the only reason this module needs no GitHub concepts.
  */
sealed trait Word:

  /** Render as an unquoted word. */
  def render: String = render(Quoting.Unquoted)

  /** Render into an explicit quoting context. */
  def render(quoting: Quoting): String

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

  /** Verbatim text: flags (`--tags`), paths (`~/.gnupg/gpg.conf`), and deliberate globs (`refs/tags/v*`).
    *
    * Unquoted, the shell sees the characters as written, metacharacters included. Inside `"…"` the four characters the
    * shell still acts on are escaped so the text stays literal.
    */
  final case class Lit(text: ShText) extends Quotable:
    def render(quoting: Quoting): String = quoting match
      case Quoting.Unquoted => text.unwrap
      case Quoting.InDouble => escapeInDouble(text.unwrap)

  /** A single-quoted string, `'text'`: no expansion of any kind. Not [[Quotable]]; see [[Word]]. */
  final case class Squote(text: SquoteText) extends Word:
    def render(quoting: Quoting): String = s"'${text.unwrap}'"

  /** A double-quoted string, `"…"`. Parts render in [[Quoting.InDouble]], so variables still expand and literals are
    * escaped. The parts list is itself the concatenation, so `"${release}-ci"` is a `Dquote` of two parts. Nested
    * inside another `Dquote` this emits `\"…\"`, the form a `--jq` argument needs.
    */
  final case class Dquote(parts: List[Quotable]) extends Quotable:
    def render(quoting: Quoting): String =
      val body = parts.map(_.render(Quoting.InDouble)).mkString
      quoting match
        case Quoting.Unquoted => s"\"$body\""
        case Quoting.InDouble => s"\\\"$body\\\""

  /** A parameter expansion: `$name`, `${name}`, or `${name…}` with a [[ParamMod]].
    *
    * @param braced
    *   force `${name}` with no modifier, needed when a name character follows, as in `"${release}x"`.
    */
  final case class VarRef(name: VarName, mod: Option[ParamMod] = None, braced: Boolean = false) extends Quotable:
    def render(quoting: Quoting): String = mod match
      case Some(m)        => s"$${${name.unwrap}${m.render}}"
      case None if braced => s"$${${name.unwrap}}"
      case None           => s"$$${name.unwrap}"

  /** A command substitution, `$(command)`.
    *
    * Uses the command's own multi-line rendering rather than forcing one line: `$(…)` is one of the few positions where
    * the shell accepts a wrapped command, and a long `gh api … \` continuation inside a substitution is exactly the
    * shape that wants it. The closing paren lands on the last line, where the shell expects it.
    */
  final case class Subst(command: Command) extends Quotable:
    def render(quoting: Quoting): String = s"$$(${command.render})"

  /** Escape hatch: text emitted verbatim in every quoting context, never escaped and never quoted.
    *
    * The deliberate seam for expression languages layered on top: a GitHub Actions `${{ … }}` must not have its `$`
    * escaped. [[ShText]] still guarantees it cannot break the surrounding YAML.
    */
  final case class Opaque(rendered: ShText) extends Quotable:
    def render(quoting: Quoting): String = rendered.unwrap

  /** Concatenation with no separator, for mixing quote styles: `'literal'"$expanded"`. */
  final case class Cat(parts: List[Word]) extends Word:
    def render(quoting: Quoting): String = parts.map(_.render(quoting)).mkString

  // Literal constructors are `inline` so the newtype validates at compile time. The `*Make` siblings take genuinely
  // runtime input and return the error instead of throwing.

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

  /** `${name:-}`, the "unset or empty is fine" form used under `set -u`. */
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

  /** Inside `"…"` the shell still acts on `$`, backtick, `\` and `"`, so a literal must escape all four. */
  private def escapeInDouble(text: String): String =
    val sb = new StringBuilder(text.length + 8)
    text.foreach { c =>
      if c == '\\' || c == '"' || c == '$' || c == '`' then sb.append('\\')
      sb.append(c)
    }
    sb.toString

end Word
