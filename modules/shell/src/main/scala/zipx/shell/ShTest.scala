package zipx.shell

import neotype.unwrap

/** A shell conditional, the `…` in `if … ; then`.
  *
  * The variants know which bracket form they need, which is the point of typing them. `[ … ]` is POSIX `test`, portable
  * but with no pattern matching; `[[ … ]]` is a bash keyword where the right-hand side of `==` is a *glob pattern* and
  * must not be quoted. Getting that backwards is a silent bug: `[ "$ref" = refs/tags/v* ]` compares against the literal
  * string `refs/tags/v*` rather than matching, so [[GlobMatch]] renders `[[ ]]` and takes a [[GlobPattern]] (not a
  * [[Word]], which could arrive quoted), while every other comparison renders `[ ]`.
  *
  * Unlike [[Command]] this is closed: a fixed grammar with no useful extension point, and [[Cmd]] already covers "test
  * by running something", which is where custom logic belongs.
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

  /** `[ left -eq right ]`: integer equality. Distinct from [[StrEq]] because `[ 01 -eq 1 ]` is true and `[ 01 = 1 ]` is
    * not.
    */
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

  /** Test by exit status: `if command; then`. No brackets, so this is how you test `git describe`, `grep -q`, or any
    * other program's success.
    *
    * An [[InlineCommand]]: `if for x in …; do … done; then` is not a conditional the shell accepts, so the type rules
    * it out rather than the renderer discovering it.
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

  /** Render to the text between `if` and `; then`. */
  def render: String = this match
    case StrEq(l, r)        => s"[ ${l.render} = ${r.render} ]"
    case StrNe(l, r)        => s"[ ${l.render} != ${r.render} ]"
    case Empty(w)           => s"[ -z ${w.render} ]"
    case NonEmpty(w)        => s"[ -n ${w.render} ]"
    case IntEq(l, r)        => s"[ ${l.render} -eq ${r.render} ]"
    case IntNe(l, r)        => s"[ ${l.render} -ne ${r.render} ]"
    case IntGt(l, r)        => s"[ ${l.render} -gt ${r.render} ]"
    case IntGe(l, r)        => s"[ ${l.render} -ge ${r.render} ]"
    case IntLt(l, r)        => s"[ ${l.render} -lt ${r.render} ]"
    case IntLe(l, r)        => s"[ ${l.render} -le ${r.render} ]"
    case GlobMatch(w, p)    => s"[[ ${w.render} == ${p.unwrap} ]]"
    case GlobNotMatch(w, p) => s"[[ ${w.render} != ${p.unwrap} ]]"
    case PathExists(p)      => s"[ -e ${p.render} ]"
    case FileExists(p)      => s"[ -f ${p.render} ]"
    case DirExists(p)       => s"[ -d ${p.render} ]"
    case FileNonEmpty(p)    => s"[ -s ${p.render} ]"
    case Executable(p)      => s"[ -x ${p.render} ]"
    case Cmd(command)       => command.inlineRender
    case And(l, r)          => s"${l.render} && ${r.render}"
    case Or(l, r)           => s"${l.render} || ${r.render}"
    case Not(inner)         => s"! ${inner.render}"

  /** Raw fragments carried by a [[Cmd]] test, for the generate-time warning. */
  def rawFragments: List[String] = this match
    case Cmd(command) => command.rawFragments
    case And(l, r)    => l.rawFragments ++ r.rawFragments
    case Or(l, r)     => l.rawFragments ++ r.rawFragments
    case Not(inner)   => inner.rawFragments
    case _            => Nil

end ShTest

object ShTest:

  /** `[ "$name" = value ]`, the safe shape: the variable is quoted so an empty value cannot collapse the test. */
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
