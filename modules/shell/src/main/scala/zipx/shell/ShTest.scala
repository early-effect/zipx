package zipx.shell

import neotype.unwrap

/** `[ "\$ref" = refs/tags/v* ]` compares against the literal string rather than matching, so [[ShTest.GlobMatch]]
  * renders `[[ ]]` and takes a [[GlobPattern]] rather than a [[Word]], which could arrive quoted.
  */
enum ShTest:

  /** Uses `=` (POSIX) rather than `==` (a bashism inside `[ ]`). */
  case StrEq(left: Word, right: Word)

  case StrNe(left: Word, right: Word)

  case Empty(word: Word)

  case NonEmpty(word: Word)

  /** `[ 01 -eq 1 ]` is true where `[ 01 = 1 ]` is not. */
  case IntEq(left: Word, right: Word)

  case IntNe(left: Word, right: Word)

  case IntGt(left: Word, right: Word)

  case IntGe(left: Word, right: Word)

  case IntLt(left: Word, right: Word)

  case IntLe(left: Word, right: Word)

  /** Bash only. The pattern renders unquoted so globbing applies. */
  case GlobMatch(word: Word, pattern: GlobPattern)

  case GlobNotMatch(word: Word, pattern: GlobPattern)

  case PathExists(path: Word)

  /** A regular file; [[PathExists]] accepts any type. */
  case FileExists(path: Word)

  case DirExists(path: Word)

  case FileNonEmpty(path: Word)

  case Executable(path: Word)

  /** An [[InlineCommand]], since `if for x in …; do … done; then` is not a conditional the shell accepts. */
  case Cmd(command: InlineCommand)

  case And(left: ShTest, right: ShTest)
  case Or(left: ShTest, right: ShTest)
  case Not(inner: ShTest)

  infix def &&(other: ShTest): ShTest = And(this, other)

  infix def ||(other: ShTest): ShTest = Or(this, other)

  def unary_! : ShTest = Not(this)

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

  def render: String = lines.render

  private def word(w: Word): ShLines = w.lines(Quoting.Unquoted)

  private def binary(left: Word, op: String, right: Word): ShLines =
    ShLines.of("[ ") ++ word(left) ++ ShLines.composed(s" $op ") ++ word(right) + " ]"

  private def unary(op: String, w: Word): ShLines =
    ShLines.composed(s"[ $op ") ++ word(w) + " ]"

  private def glob(w: Word, op: String, pattern: GlobPattern): ShLines =
    ShLines.of("[[ ") ++ word(w) ++ ShLines.composed(s" $op ${pattern.unwrap} ]]")

  def rawFragments: List[String] = this match
    case Cmd(command) => command.rawFragments
    case And(l, r)    => l.rawFragments ++ r.rawFragments
    case Or(l, r)     => l.rawFragments ++ r.rawFragments
    case Not(inner)   => inner.rawFragments
    case _            => Nil

end ShTest

object ShTest:

  /** Quotes the variable so an empty value cannot collapse the test. */
  inline def varEquals(inline name: String, inline value: String): ShTest =
    StrEq(Word.vq(name), Word.quoted(value))

  inline def varEmpty(inline name: String): ShTest = Empty(Word.vq(name))

  inline def varNonEmpty(inline name: String): ShTest = NonEmpty(Word.vq(name))

  def succeeds(command: InlineCommand): ShTest = Cmd(command)

  inline def varMatches(inline name: String, inline pattern: String): ShTest =
    GlobMatch(Word.vq(name), GlobPattern(pattern))

end ShTest
