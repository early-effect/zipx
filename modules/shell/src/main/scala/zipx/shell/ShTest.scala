package zipx.shell

import neotype.unwrap

/** A shell conditional, the `…` in `if … ; then`.
  *
  * Each variant knows which bracket form it needs. `[ "$ref" = refs/tags/v* ]` compares against the literal string
  * rather than matching, so [[ShTest.GlobMatch]] renders `[[ ]]` and takes a [[GlobPattern]] rather than a [[Word]],
  * which could arrive quoted; every other comparison renders `[ ]`.
  */
enum ShTest:

  /** `[ left = right ]`. Uses `=` (POSIX) rather than `==` (a bashism inside `[ ]`). */
  case StrEq(left: Word, right: Word)

  /** `[ left != right ]`. */
  case StrNe(left: Word, right: Word)

  /** `[ -z word ]`: unset or empty. */
  case Empty(word: Word)

  /** `[ -n word ]`: set and non-empty. */
  case NonEmpty(word: Word)

  /** `[ left -eq right ]`: integer equality. `[ 01 -eq 1 ]` is true where `[ 01 = 1 ]` is not. */
  case IntEq(left: Word, right: Word)

  /** `[ left -ne right ]`. */
  case IntNe(left: Word, right: Word)

  /** `[ left -gt right ]`. */
  case IntGt(left: Word, right: Word)

  /** `[ left -ge right ]`. */
  case IntGe(left: Word, right: Word)

  /** `[ left -lt right ]`. */
  case IntLt(left: Word, right: Word)

  /** `[ left -le right ]`. */
  case IntLe(left: Word, right: Word)

  /** `[[ word == pattern ]]`: bash pattern match, with the pattern rendered unquoted so globbing applies. */
  case GlobMatch(word: Word, pattern: GlobPattern)

  /** `[[ word != pattern ]]`. */
  case GlobNotMatch(word: Word, pattern: GlobPattern)

  /** `[ -e path ]`: the path exists, whatever its type. */
  case PathExists(path: Word)

  /** `[ -f path ]`: a regular file exists. */
  case FileExists(path: Word)

  /** `[ -d path ]`: a directory exists. */
  case DirExists(path: Word)

  /** `[ -s path ]`: exists and is non-empty. */
  case FileNonEmpty(path: Word)

  /** `[ -x path ]`: exists and is executable. */
  case Executable(path: Word)

  /** Test by exit status: `if command; then`, with no brackets. An [[InlineCommand]], since
    * `if for x in …; do … done; then` is not a conditional the shell accepts.
    */
  case Cmd(command: InlineCommand)

  case And(left: ShTest, right: ShTest)
  case Or(left: ShTest, right: ShTest)
  case Not(inner: ShTest)

  /** `this && other`. */
  infix def &&(other: ShTest): ShTest = And(this, other)

  /** `this || other`. */
  infix def ||(other: ShTest): ShTest = Or(this, other)

  /** `! this`. */
  def unary_! : ShTest = Not(this)

  /** The validated lines between `if` and `; then`. */
  def lines: ShLines = this match
    case StrEq(l, r)        => binary(l, "=", r)
    case StrNe(l, r)        => binary(l, "!=", r)
    case IntEq(l, r)        => binary(l, "-eq", r)
    case IntNe(l, r)        => binary(l, "-ne", r)
    case IntGt(l, r)        => binary(l, "-gt", r)
    case IntGe(l, r)        => binary(l, "-ge", r)
    case IntLt(l, r)        => binary(l, "-lt", r)
    case IntLe(l, r)        => binary(l, "-le", r)
    case Empty(w)           => unary("-z", w)
    case NonEmpty(w)        => unary("-n", w)
    case PathExists(p)      => unary("-e", p)
    case FileExists(p)      => unary("-f", p)
    case DirExists(p)       => unary("-d", p)
    case FileNonEmpty(p)    => unary("-s", p)
    case Executable(p)      => unary("-x", p)
    case GlobMatch(w, p)    => glob(w, "==", p)
    case GlobNotMatch(w, p) => glob(w, "!=", p)
    case Cmd(command)       => command.inlineLines
    case And(l, r)          => l.lines + " && " ++ r.lines
    case Or(l, r)           => l.lines + " || " ++ r.lines
    case Not(inner)         => ShLines.of("! ") ++ inner.lines

  /** Render to the text between `if` and `; then`. */
  def render: String = lines.render

  private def word(w: Word): ShLines = w.lines(Quoting.Unquoted)

  private def binary(left: Word, op: String, right: Word): ShLines =
    ShLines.of("[ ") ++ word(left) ++ ShLines.composed(s" $op ") ++ word(right) + " ]"

  private def unary(op: String, w: Word): ShLines =
    ShLines.composed(s"[ $op ") ++ word(w) + " ]"

  private def glob(w: Word, op: String, pattern: GlobPattern): ShLines =
    ShLines.of("[[ ") ++ word(w) ++ ShLines.composed(s" $op ${pattern.unwrap} ]]")

  /** Raw fragments carried by a [[ShTest.Cmd]] test, for the generate-time warning. */
  def rawFragments: List[String] = this match
    case Cmd(command) => command.rawFragments
    case And(l, r)    => l.rawFragments ++ r.rawFragments
    case Or(l, r)     => l.rawFragments ++ r.rawFragments
    case Not(inner)   => inner.rawFragments
    case _            => Nil

end ShTest

object ShTest:

  /** `[ "$name" = value ]`, with the variable quoted so an empty value cannot collapse the test. */
  inline def varEquals(inline name: String, inline value: String): ShTest =
    StrEq(Word.vq(name), Word.quoted(value))

  /** `[ -z "$name" ]`. */
  inline def varEmpty(inline name: String): ShTest = Empty(Word.vq(name))

  /** `[ -n "$name" ]`. */
  inline def varNonEmpty(inline name: String): ShTest = NonEmpty(Word.vq(name))

  /** `if command; then`: test a program's exit status. */
  def succeeds(command: InlineCommand): ShTest = Cmd(command)

  /** `[[ "$name" == pattern ]]`: bash glob match against an unquoted pattern. */
  inline def varMatches(inline name: String, inline pattern: String): ShTest =
    GlobMatch(Word.vq(name), GlobPattern(pattern))

end ShTest
