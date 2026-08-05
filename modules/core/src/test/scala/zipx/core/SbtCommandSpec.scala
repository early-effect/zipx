package zipx.core

import zio.test.*

object SbtCommandSpec extends ZIOSpecDefault:

  private val api   = ModuleNode(ModuleId("api"))
  private val cross = ModuleNode(ModuleId("api"), crossScalaVersions = List("3.3.6", "2.13.16"))

  /** Named rather than written inline, so no raw control character lands in this source file. */
  private val Bell: Char = 0x07.toChar

  private def rendered(command: SbtCommand): String = command.render.render

  def spec = suite("SbtCommand")(
    suite("text validation")(
      test("rejects the characters that would break the generated run: line") {
        assertTrue(
          SbtCommand.make("").isLeft,
          SbtCommand.make("test\npublish").isLeft,
          SbtCommand.make("test\rpublish").isLeft,
          SbtCommand.make(s"test${Bell}publish").isLeft,
        )
      },
      test("accepts a single quote, because render has a total encoding for it") {
        assertTrue(SbtCommand.make("""set x := "a'b"""").isRight)
      },
      test("accepts the shapes real builds use: cross, config axis, compound, aliases") {
        assertTrue(
          SbtCommand.make("+api/publish").isRight,
          SbtCommand.make("api/Docker/publish").isRight,
          SbtCommand.make("clean; test").isRight,
          SbtCommand.make("lintAll").isRight,
        )
      },
    ),
    suite("render")(
      test("wraps the command in single quotes as one argument to sbt") {
        assertTrue(rendered(SbtCommand("api/test")) == "sbt 'api/test'")
      },
      test("a quote becomes an 'a'\\''b' concatenation, since '…' cannot escape its own delimiter") {
        val command = SbtCommand.make("""set v := "a'b"""").toOption.get
        assertTrue(rendered(command) == """sbt 'set v := "a'\''b"'""")
      },
      test("a trailing quote keeps its final empty segment rather than losing the quote") {
        val command = SbtCommand.make("a'").toOption.get
        assertTrue(rendered(command) == """sbt 'a'\'''""")
      },
    ),
    suite("combinators")(
      test("module scopes a task to a module id") {
        assertTrue(SbtCommand.module(api, SbtCommand("test")).text == "api/test")
      },
      test("crossModule prefixes + only when the module is actually cross-built") {
        assertTrue(
          SbtCommand.crossModule(cross, SbtCommand("publish")).text == "+api/publish",
          SbtCommand.crossModule(api, SbtCommand("publish")).text == "api/publish",
        )
      },
      test("join makes one session, and is None for no commands") {
        assertTrue(
          SbtCommand.join(List(SbtCommand("a/test"), SbtCommand("b/test"))).map(_.text).contains("a/test; b/test"),
          SbtCommand.join(Nil).isEmpty,
        )
      },
      test("prefixedBy puts the prefix first in the same session") {
        assertTrue(SbtCommand.prefixedBy(SbtCommand("cleanFull"), SbtCommand("test")).text == "cleanFull; test")
      },
      test("underScalaVersion is space-separated, because ++<ver> takes the rest of the line") {
        val switched = SbtCommand.underScalaVersion(zipx.workflow.Expr.matrix("scala"), SbtCommand("test"))
        assertTrue(switched.text == "++${{ matrix.scala }} test")
      },
    ),
    suite("provenance")(
      test("a built command has nothing to report") {
        assertTrue(SbtCommand("api/test").rawFragments.isEmpty)
      },
      test("unchecked text is reportable") {
        assertTrue(SbtCommand.unchecked("api/tets").map(_.rawFragments).contains(List("api/tets")))
      },
      test("composing an unchecked command keeps it reportable, so the warning still fires") {
        val hand   = SbtCommand.unchecked("promote").toOption.get
        val scoped = SbtCommand.module(api, hand)
        val joined = SbtCommand.join(List(SbtCommand("api/test"), scoped)).get
        assertTrue(
          scoped.rawFragments == List("api/promote"),
          joined.rawFragments.nonEmpty,
          joined.text == "api/test; api/promote",
        )
      },
      test("Steps.rawWarnings names the capability whose command is unchecked") {
        val hand     = SbtCommand.unchecked("promote").toOption.get
        val cap      = Capability.custom(name = "promote", command = _ => hand)
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
