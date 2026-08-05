package zipx.shell

import neotype.*

// Validated primitives. Every other type in this module holds these, not String. `ShText("echo hi")` validates the
// literal at compile time; a runtime string goes through `ShText.make` (Either) or `makeOrThrow`.
//
// Validators use only what neotype can evaluate at compile time: isEmpty, contains, startsWith, matches, length, Int
// comparison. `exists` with a lambda is not, so character-class checks are regexes.

/** Text safe inside a shell word: one line, no control characters. Tabs are allowed. */
type ShText = ShText.Type
object ShText extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.contains("\n") then "shell text must not contain a newline"
    else if input.contains("\r") then "shell text must not contain a carriage return"
    else if !input.matches(Patterns.NoControlChars) then "shell text must not contain control characters"
    else true

  val empty: ShText = ShText("")

/** Text for a single-quoted word. A single quote cannot be escaped inside `'…'`, so it is rejected outright: the
  * alternative renders as `'\''` concatenation, which callers can build explicitly with [[Word.cat]].
  */
type SquoteText = SquoteText.Type
object SquoteText extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.contains("'") then "single-quoted text cannot contain a single quote; concatenate quoted segments instead"
    else if input.contains("\n") then "shell text must not contain a newline"
    else if input.contains("\r") then "shell text must not contain a carriage return"
    else if !input.matches(Patterns.NoControlChars) then "shell text must not contain control characters"
    else true

/** Text appearing inside `${…}`, such as the default in `${VAR:-default}` or the pattern in `${VAR#prefix}`. `}` closes
  * the expansion, so `${VAR:-a}b}` would silently mean a default of `a` followed by a literal `b}`.
  */
type ParamText = ParamText.Type
object ParamText extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.contains("}") then "text inside ${…} must not contain '}', which would close the expansion early"
    else if input.contains("\n") then "shell text must not contain a newline"
    else if input.contains("\r") then "shell text must not contain a carriage return"
    else if !input.matches(Patterns.NoControlChars) then "shell text must not contain control characters"
    else true

/** One physical line of a rendered script: [[ShText]]'s rules plus no leading tab.
  *
  * Both are YAML constraints rather than shell ones. Block scalar indentation must be spaces, and
  * `YamlPrinter.needsQuoting` force-quotes any string holding `\r` or a control character, which would emit a
  * multi-line program as one escaped scalar.
  */
type ScriptLine = ScriptLine.Type
object ScriptLine extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.contains("\n") then "a script line must not contain a newline; split it into separate lines"
    else if input.contains("\r") then
      "a script line must not contain a carriage return: YAML would quote-escape it and collapse the script to one line"
    else if input.startsWith("\t") then
      "a script line must not start with a tab: YAML block scalar indentation must be spaces"
    else if !input.matches(Patterns.NoControlChars) then "a script line must not contain control characters"
    else true

  val empty: ScriptLine = ScriptLine("")
end ScriptLine

/** A shell variable name. POSIX name rules, which are also what GitHub Actions requires of `env:` keys. */
type VarName = VarName.Type
object VarName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a shell variable name must be non-empty"
    else if input.matches(Patterns.Ident) then true
    else s"invalid shell variable name '$input': must match ${Patterns.Ident}"

/** A glob pattern for `[[ … == pattern ]]`. Renders unquoted so the shell globs it, hence no whitespace or quotes. */
type GlobPattern = GlobPattern.Type
object GlobPattern extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a glob pattern must be non-empty"
    else if !input.matches(Patterns.NoWhitespaceOrQuotes) then
      s"invalid glob pattern '$input': it renders unquoted, so whitespace and quote characters are not allowed"
    else true

/** A program name or subcommand in command position, so `Exec` is not a second splicing hole. Its arguments stay
  * [[Word]]s that choose their own quoting.
  */
type ProgramName = ProgramName.Type
object ProgramName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a program name must be non-empty"
    else if !input.matches(Patterns.ProgramName) then
      s"invalid program name '$input': allowed characters are letters, digits, and _ . / - +"
    else true

/** A heredoc delimiter. Identifier-shaped so it needs no quoting. */
type HeredocTag = HeredocTag.Type
object HeredocTag extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a heredoc delimiter must be non-empty"
    else if input.matches(Patterns.Ident) then true
    else s"invalid heredoc delimiter '$input': must match ${Patterns.Ident}"

/** A process exit status, 0 to 255. Outside that range the shell truncates modulo 256, so `exit 256` reports success.
  */
type ExitCode = ExitCode.Type
object ExitCode extends Newtype[Int]:
  override inline def validate(input: Int): Boolean | String =
    if input < 0 then "an exit code must not be negative; the shell truncates it modulo 256"
    else if input > 255 then "an exit code must be at most 255; the shell truncates it modulo 256"
    else true

  val Success: ExitCode = ExitCode(0)
  val Failure: ExitCode = ExitCode(1)

/** A file descriptor for redirections. Only single digits are portable: `10>` is a valid fd in bash but ambiguous with
  * `1` followed by `0>` in a POSIX shell.
  */
type FileDescriptor = FileDescriptor.Type
object FileDescriptor extends Newtype[Int]:
  override inline def validate(input: Int): Boolean | String =
    if input < 0 then "a file descriptor must not be negative"
    else if input > 9 then "only single-digit file descriptors are portable"
    else true

  val Stdin: FileDescriptor  = FileDescriptor(0)
  val Stdout: FileDescriptor = FileDescriptor(1)
  val Stderr: FileDescriptor = FileDescriptor(2)

// Literal Strings, not Regex values: `validate` is inlined and a compiled Regex is not comptime-evaluable.
object Patterns:
  inline val Ident = "[A-Za-z_][A-Za-z0-9_]*"

  /** Excludes C0 controls and DEL; tab (0x09) is deliberately permitted. */
  inline val NoControlChars = "[^\\x00-\\x08\\x0A-\\x1F\\x7F]*"

  inline val NoWhitespaceOrQuotes = "[^\\s'\"\\\\\\x00-\\x1F\\x7F]+"
  inline val ProgramName          = "[A-Za-z0-9_./+-]+"
