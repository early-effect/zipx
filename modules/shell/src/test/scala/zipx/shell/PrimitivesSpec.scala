package zipx.shell

import neotype.unwrap
import zio.test.*

/** Every newtype's accepting cases, rejecting cases, and boundaries.
  *
  * `make` runs the same `validate` the compile-time path does, so testing through `make` covers both. The compile-time
  * rejection itself cannot be asserted from a spec (a bad literal fails the build), so it is demonstrated in the docs
  * page instead.
  *
  * Control characters are written via [[ctrl]] rather than as literals: a raw NUL or DEL in a source file is invisible
  * in a diff, which is the same hazard these newtypes exist to prevent.
  */
object PrimitivesSpec extends ZIOSpecDefault:

  /** A string containing the control character with the given code point, surrounded by ordinary text. */
  private def ctrl(code: Int): String = s"a${code.toChar}b"

  /** All C0 control characters plus DEL, minus tab: the set every text newtype must reject. */
  private val forbiddenControls: List[Char] =
    ((0 to 0x1f).toList.map(_.toChar) :+ 0x7f.toChar).filter(_ != '\t')

  private val gAsciiPrintable: Gen[Any, Char] = Gen.char(' ', '~')
  private val gControlChar: Gen[Any, Char]    = Gen.elements(forbiddenControls*)
  private val gIdentStart: Gen[Any, Char]     = Gen.elements((('A' to 'Z') ++ ('a' to 'z') :+ '_')*)
  private val gIdentRest: Gen[Any, Char]      = Gen.elements((('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') :+ '_')*)

  private val gIdent: Gen[Any, String] =
    for
      head <- gIdentStart
      tail <- Gen.listOf(gIdentRest)
    yield (head :: tail).mkString

  def spec = suite("primitives")(
    suite("ShText")(
      test("accepts ordinary text, including tabs and shell metacharacters") {
        assertTrue(
          ShText.make("echo hi").isRight,
          ShText.make("").isRight,
          ShText.make("a\tb").isRight, // tab mid-line is legitimate argument content
          ShText.make("\t").isRight,   // ShText has no leading-tab rule; ScriptLine does
          ShText.make("$HOME `date` \\ \" '").isRight,
          ShText.make("refs/tags/v*").isRight,
          ShText.make("café ☕").isRight, // non-ASCII is fine
        )
      },
      test("rejects newlines and carriage returns") {
        assertTrue(
          ShText.make("a\nb").isLeft,
          ShText.make("\n").isLeft,
          ShText.make("a\r\nb").isLeft,
          ShText.make("trailing\n").isLeft,
          ShText.make("a\rb").isLeft,
        )
      },
      test("rejects NUL, DEL, and the C0 boundaries") {
        assertTrue(
          ShText.make(ctrl(0x00)).isLeft, // NUL
          ShText.make(ctrl(0x01)).isLeft, // start of the C0 range
          ShText.make(ctrl(0x08)).isLeft, // backspace, just below tab
          ShText.make(ctrl(0x0b)).isLeft, // vertical tab, just above newline
          ShText.make(ctrl(0x1b)).isLeft, // ESC
          ShText.make(ctrl(0x1f)).isLeft, // top of the C0 range
          ShText.make(ctrl(0x7f)).isLeft, // DEL
          ShText.make(ctrl(0x20)).isRight, // space is printable, not control
        )
      },
      test("rejects every C0 control character and DEL, but not tab") {
        check(gControlChar)(c => assertTrue(ShText.make(s"a${c}b").isLeft))
      },
      test("accepts any string of printable ASCII") {
        check(Gen.listOf(gAsciiPrintable))(chars => assertTrue(ShText.make(chars.mkString).isRight))
      },
      test("error messages name the problem") {
        assertTrue(
          ShText.make("a\nb").swap.exists(_.contains("newline")),
          ShText.make("a\rb").swap.exists(_.contains("carriage return")),
          ShText.make(ctrl(0x00)).swap.exists(_.contains("control")),
        )
      },
      test("empty is the empty string") {
        assertTrue(ShText.empty.unwrap == "")
      },
    ),
    suite("SquoteText")(
      test("accepts text with no single quote") {
        assertTrue(
          SquoteText.make("plain text").isRight,
          SquoteText.make("").isRight,
          SquoteText.make("$HOME is not expanded").isRight,
          SquoteText.make("double \" quote is fine").isRight,
          SquoteText.make("back\\slash is fine").isRight,
          SquoteText.make("a\tb").isRight,
        )
      },
      test("rejects an embedded single quote, which '…' cannot escape") {
        assertTrue(
          SquoteText.make("it's").isLeft,
          SquoteText.make("'").isLeft,
          SquoteText.make("'leading").isLeft,
          SquoteText.make("trailing'").isLeft,
          SquoteText.make("two''quotes").isLeft,
          SquoteText.make("it's").swap.exists(_.contains("single quote")),
        )
      },
      test("also inherits the ShText rules") {
        assertTrue(
          SquoteText.make("a\nb").isLeft,
          SquoteText.make("a\rb").isLeft,
          SquoteText.make(ctrl(0x00)).isLeft,
          SquoteText.make(ctrl(0x7f)).isLeft,
        )
      },
      test("rejects every control character") {
        check(gControlChar)(c => assertTrue(SquoteText.make(s"a${c}b").isLeft))
      },
    ),
    suite("ParamText")(
      test("accepts text with no closing brace") {
        assertTrue(
          ParamText.make("").isRight,
          ParamText.make("default-value").isRight,
          ParamText.make("refs/tags/").isRight,
          ParamText.make("${nested").isRight, // an opening brace alone cannot close the expansion
          ParamText.make("a'b\"c").isRight,
        )
      },
      test("rejects a closing brace, which would end the expansion early") {
        assertTrue(
          ParamText.make("}").isLeft,
          ParamText.make("a}b").isLeft,
          ParamText.make("trailing}").isLeft,
          ParamText.make("{balanced}").isLeft, // still rejected: the first } wins
          ParamText.make("}").swap.exists(_.contains("close the expansion")),
        )
      },
      test("also inherits the ShText rules") {
        assertTrue(
          ParamText.make("a\nb").isLeft,
          ParamText.make("a\rb").isLeft,
          ParamText.make(ctrl(0x00)).isLeft,
          ParamText.make("a\tb").isRight,
        )
      },
      test("rejects every control character") {
        check(gControlChar)(c => assertTrue(ParamText.make(s"a${c}b").isLeft))
      },
    ),
    suite("ScriptLine")(
      test("accepts a single line, including one indented with spaces") {
        assertTrue(
          ScriptLine.make("echo hi").isRight,
          ScriptLine.make("").isRight,
          ScriptLine.make("  nested").isRight,
          ScriptLine.make("if [ -n \"$x\" ]; then").isRight,
          ScriptLine.make("a\tb").isRight,            // tab mid-line is fine
          ScriptLine.make(" \tafter a space").isRight, // only the *first* character matters
        )
      },
      test("rejects a leading tab: YAML block scalar indentation must be spaces") {
        assertTrue(
          ScriptLine.make("\techo hi").isLeft,
          ScriptLine.make("\t").isLeft,
          ScriptLine.make("\t\tdeep").isLeft,
          ScriptLine.make("\techo").swap.exists(_.contains("tab")),
        )
      },
      test("rejects newlines and carriage returns") {
        assertTrue(
          ScriptLine.make("a\nb").isLeft,
          ScriptLine.make("a\rb").isLeft,
          ScriptLine.make("a\r\nb").isLeft,
          ScriptLine.make("trailing\n").isLeft,
        )
      },
      test("rejects NUL, DEL, and the C0 boundaries") {
        assertTrue(
          ScriptLine.make(ctrl(0x00)).isLeft,
          ScriptLine.make(ctrl(0x01)).isLeft,
          ScriptLine.make(ctrl(0x08)).isLeft,
          ScriptLine.make(ctrl(0x0b)).isLeft,
          ScriptLine.make(ctrl(0x1f)).isLeft,
          ScriptLine.make(ctrl(0x7f)).isLeft,
        )
      },
      test("rejects every C0 control character and DEL") {
        check(gControlChar)(c => assertTrue(ScriptLine.make(s"x${c}y").isLeft))
      },
      test("accepts any line of printable ASCII that does not start with a tab") {
        check(Gen.listOf(gAsciiPrintable))(chars => assertTrue(ScriptLine.make(chars.mkString).isRight))
      },
      test("the carriage-return message explains the YAML consequence") {
        assertTrue(ScriptLine.make("a\rb").swap.exists(_.contains("collapse the script to one line")))
      },
      test("empty is a valid blank line") {
        assertTrue(ScriptLine.empty.unwrap == "")
      },
    ),
    suite("VarName")(
      test("accepts POSIX names") {
        assertTrue(
          VarName.make("PATH").isRight,
          VarName.make("_").isRight,
          VarName.make("_leading_underscore").isRight,
          VarName.make("GITHUB_OUTPUT").isRight,
          VarName.make("lower_case_1").isRight,
          VarName.make("a").isRight,
          VarName.make("A1_b2").isRight,
        )
      },
      test("accepts any generated identifier") {
        check(gIdent)(name => assertTrue(VarName.make(name).isRight))
      },
      test("rejects empty, digit-leading, and names with invalid characters") {
        assertTrue(
          VarName.make("").isLeft,
          VarName.make("1LEADING").isLeft,
          VarName.make("9").isLeft,
          VarName.make("has-dash").isLeft,
          VarName.make("has.dot").isLeft,
          VarName.make("has space").isLeft,
          VarName.make("has$dollar").isLeft,
          VarName.make("has{brace}").isLeft,
          VarName.make("café").isLeft,
          VarName.make("a\nb").isLeft,
          VarName.make(ctrl(0x00)).isLeft,
          VarName.make("").swap.exists(_.contains("non-empty")),
        )
      },
    ),
    suite("GlobPattern")(
      test("accepts patterns with glob metacharacters") {
        assertTrue(
          GlobPattern.make("refs/tags/v*").isRight,
          GlobPattern.make("*").isRight,
          GlobPattern.make("v?.?").isRight,
          GlobPattern.make("[0-9]*").isRight,
          GlobPattern.make("literal").isRight,
          GlobPattern.make("*.jar").isRight,
        )
      },
      test("rejects empty, whitespace, quotes, and backslashes because it renders unquoted") {
        assertTrue(
          GlobPattern.make("").isLeft,
          GlobPattern.make("has space").isLeft,
          GlobPattern.make("has\ttab").isLeft,
          GlobPattern.make("has'squote").isLeft,
          GlobPattern.make("has\"dquote").isLeft,
          GlobPattern.make("has\\backslash").isLeft,
          GlobPattern.make("has\nnewline").isLeft,
          GlobPattern.make(ctrl(0x00)).isLeft,
          GlobPattern.make(ctrl(0x7f)).isLeft,
          GlobPattern.make("").swap.exists(_.contains("non-empty")),
          GlobPattern.make("a b").swap.exists(_.contains("unquoted")),
        )
      },
    ),
    suite("ProgramName")(
      test("accepts program names and paths") {
        assertTrue(
          ProgramName.make("git").isRight,
          ProgramName.make("base64").isRight,
          ProgramName.make("/usr/bin/env").isRight,
          ProgramName.make("./gradlew").isRight,
          ProgramName.make("g++").isRight,
          ProgramName.make("docker-compose").isRight,
          ProgramName.make("python3.11").isRight,
          ProgramName.make("_underscore").isRight,
        )
      },
      test("rejects empty and anything that would split or reinterpret the command") {
        assertTrue(
          ProgramName.make("").isLeft,
          ProgramName.make("two words").isLeft,
          ProgramName.make("has$var").isLeft,
          ProgramName.make("pipe|cmd").isLeft,
          ProgramName.make("semi;cmd").isLeft,
          ProgramName.make("sub$(cmd)").isLeft,
          ProgramName.make("quote'").isLeft,
          ProgramName.make("dquote\"").isLeft,
          ProgramName.make("redirect>file").isLeft,
          ProgramName.make("amp&").isLeft,
          ProgramName.make("back`tick`").isLeft,
          ProgramName.make("new\nline").isLeft,
          ProgramName.make(ctrl(0x00)).isLeft,
        )
      },
    ),
    suite("HeredocTag")(
      test("accepts identifier-shaped delimiters") {
        assertTrue(
          HeredocTag.make("EOF").isRight,
          HeredocTag.make("SCRIPT").isRight,
          HeredocTag.make("_END_").isRight,
          HeredocTag.make("TAG1").isRight,
        )
      },
      test("accepts any generated identifier") {
        check(gIdent)(tag => assertTrue(HeredocTag.make(tag).isRight))
      },
      test("rejects empty, digit-leading, whitespace, and metacharacters") {
        assertTrue(
          HeredocTag.make("").isLeft,
          HeredocTag.make("1EOF").isLeft,
          HeredocTag.make("E OF").isLeft,
          HeredocTag.make("E-OF").isLeft,
          HeredocTag.make("'EOF'").isLeft,
          HeredocTag.make("E\tOF").isLeft,
          HeredocTag.make("EOF\n").isLeft,
        )
      },
    ),
    suite("ExitCode")(
      test("accepts the whole 0 to 255 range") {
        check(Gen.int(0, 255))(i => assertTrue(ExitCode.make(i).isRight))
      },
      test("boundaries: -1 and 256 are rejected, 0 and 255 accepted") {
        assertTrue(
          ExitCode.make(-1).isLeft,
          ExitCode.make(0).isRight,
          ExitCode.make(1).isRight,
          ExitCode.make(254).isRight,
          ExitCode.make(255).isRight,
          ExitCode.make(256).isLeft,
        )
      },
      test("rejects values the shell would truncate modulo 256") {
        assertTrue(
          ExitCode.make(256).isLeft, // would report success
          ExitCode.make(300).isLeft,
          ExitCode.make(Int.MaxValue).isLeft,
          ExitCode.make(Int.MinValue).isLeft,
          ExitCode.make(256).swap.exists(_.contains("256")),
        )
      },
      test("rejects anything outside the range") {
        check(Gen.oneOf(Gen.int(Int.MinValue, -1), Gen.int(256, Int.MaxValue)))(i =>
          assertTrue(ExitCode.make(i).isLeft)
        )
      },
      test("named constants") {
        assertTrue(ExitCode.Success.unwrap == 0, ExitCode.Failure.unwrap == 1)
      },
    ),
    suite("FileDescriptor")(
      test("accepts single digits only") {
        check(Gen.int(0, 9))(i => assertTrue(FileDescriptor.make(i).isRight))
      },
      test("boundaries: -1 and 10 are rejected, 0 and 9 accepted") {
        assertTrue(
          FileDescriptor.make(-1).isLeft,
          FileDescriptor.make(0).isRight,
          FileDescriptor.make(9).isRight,
          FileDescriptor.make(10).isLeft,
          FileDescriptor.make(10).swap.exists(_.contains("single-digit")),
        )
      },
      test("rejects anything outside the range") {
        check(Gen.oneOf(Gen.int(Int.MinValue, -1), Gen.int(10, Int.MaxValue)))(i =>
          assertTrue(FileDescriptor.make(i).isLeft)
        )
      },
      test("named constants match the standard streams") {
        assertTrue(
          FileDescriptor.Stdin.unwrap == 0,
          FileDescriptor.Stdout.unwrap == 1,
          FileDescriptor.Stderr.unwrap == 2,
        )
      },
    ),
  )
end PrimitivesSpec
