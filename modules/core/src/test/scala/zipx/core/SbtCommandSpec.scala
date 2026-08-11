package zipx.core

import zio.test.*

object SbtCommandSpec extends ZIOSpecDefault:

  private val api   = ModuleNode(ModuleId("api"))
  private val cross = ModuleNode(ModuleId("api"), crossScalaVersions = List("3.3.6", "2.13.16"))

  /** Named rather than written inline, so no raw control character lands in this source file. */
  private val Bell: Char = 0x07.toChar

  private def rendered(command: SbtCommand): String = command.render.render

  def spec = suite("SbtCommand")(
    suite("raw text validation")(
      test("rejects the characters that would break the generated run: line") {
        assertTrue(
          SbtCommand.raw("").isLeft,
          SbtCommand.raw("test\npublish").isLeft,
          SbtCommand.raw("test\rpublish").isLeft,
          SbtCommand.raw(s"test${Bell}publish").isLeft,
        )
      },
      test("accepts a single quote, because render has a total encoding for it") {
        assertTrue(SbtCommand.raw("""set x := "a'b"""").isRight)
      },
      test("accepts the shapes real builds use: cross, config axis, compound, aliases") {
        assertTrue(
          SbtCommand.raw("+api/publish").isRight,
          SbtCommand.raw("api/Docker/publish").isRight,
          SbtCommand.raw("clean; test").isRight,
          SbtCommand.raw("lintAll").isRight,
        )
      },
    ),
    suite("render")(
      test("wraps the command in single quotes as one argument to sbt") {
        assertTrue(rendered(SbtCommand.module(api, SbtCommand.unsafeTask("test"))) == "sbt 'api/test'")
      },
      test("a quote becomes an 'a'\\''b' concatenation, since '…' cannot escape its own delimiter") {
        val command = SbtCommand.raw("""set v := "a'b"""").toOption.get
        assertTrue(rendered(command) == """sbt 'set v := "a'\''b"'""")
      },
      test("a trailing quote keeps its final empty segment rather than losing the quote") {
        val command = SbtCommand.raw("a'").toOption.get
        assertTrue(rendered(command) == """sbt 'a'\'''""")
      },
    ),
    suite("steps and combinators")(
      test("module scopes only unscoped Task steps") {
        val task  = SbtCommand.unsafeTask("test")
        val named = SbtCommand.unsafeCommand("sonaRelease")
        val raw   = SbtCommand.raw("promote").toOption.get
        assertTrue(
          SbtCommand.module(api, task).text == "api/test",
          SbtCommand.module(api, named).text == "sonaRelease",
          SbtCommand.module(api, raw).text == "promote",
        )
      },
      test("crossModule prefixes + only when the module is actually cross-built") {
        assertTrue(
          SbtCommand.crossModule(cross, SbtCommand.unsafeTask("publish")).text == "+api/publish",
          SbtCommand.crossModule(api, SbtCommand.unsafeTask("publish")).text == "api/publish",
        )
      },
      test("session and andThen join steps with '; '") {
        val joined = SbtCommand.session(SbtCommand.unsafeCommand("cleanFull"), SbtCommand.unsafeTask("test"))
        assertTrue(
          joined.text == "cleanFull; test",
          SbtCommand.unsafeTask("a").andThen(SbtCommand.unsafeTask("b")).text == "a; b",
        )
      },
      test("join makes one session, and is None for no commands") {
        assertTrue(
          SbtCommand
            .join(List(SbtCommand.unsafeTask("a/test"), SbtCommand.unsafeTask("b/test")))
            .map(_.text)
            .contains("a/test; b/test"),
          SbtCommand.join(Nil).isEmpty,
        )
      },
      test("underScalaVersion is a Built ++ switch step ahead of the command") {
        val switched = SbtCommand.underScalaVersion(zipx.workflow.Expr.matrix("scala"), SbtCommand.unsafeTask("test"))
        assertTrue(switched.text == "++${{ matrix.scala }}; test")
      },
      test("a compound session keeps Task and Named steps distinct") {
        val cmd = SbtCommand.session(SbtCommand.unsafeTask("publishSigned"), SbtCommand.unsafeCommand("sonaRelease"))
        assertTrue(
          cmd.text == "publishSigned; sonaRelease",
          cmd.declaredNames.map(n => n: String) == List("sonaRelease"),
          cmd.rawFragments.isEmpty,
        )
      },
    ),
    suite("provenance")(
      test("a Task or Named command has nothing to report") {
        assertTrue(
          SbtCommand.unsafeTask("test").rawFragments.isEmpty,
          SbtCommand.unsafeCommand("sonaRelease").rawFragments.isEmpty,
        )
      },
      test("raw text is reportable") {
        assertTrue(SbtCommand.raw("api/tets").map(_.rawFragments).contains(List("api/tets")))
      },
      test("composing a raw command keeps it reportable; module does not rewrite Raw steps") {
        val hand   = SbtCommand.raw("promote").toOption.get
        val scoped = SbtCommand.module(api, hand)
        val joined = SbtCommand.join(List(SbtCommand.module(api, SbtCommand.unsafeTask("test")), hand)).get
        assertTrue(
          scoped.rawFragments == List("promote"),
          scoped.text == "promote",
          joined.rawFragments == List("promote"),
          joined.text == "api/test; promote",
        )
      },
      test("Steps.rawWarnings names the capability whose command is raw") {
        val hand     = SbtCommand.raw("promote").toOption.get
        val cap      = Capability.custom(name = CapabilityName("promote"), command = _ => hand)
        val warnings = Steps.rawWarnings(List(cap, Capability.test), PlanConfig())
        assertTrue(
          warnings.length == 1,
          warnings.head.contains("capability 'promote'"),
          warnings.head.contains("promote"),
        )
      },
      test("an all-built plan warns about nothing") {
        assertTrue(Steps.rawWarnings(List(Capability.test, Capability.publish), PlanConfig()).isEmpty)
      },
    ),
  )
end SbtCommandSpec
