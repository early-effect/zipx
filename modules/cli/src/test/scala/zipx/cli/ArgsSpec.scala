package zipx.cli

import zio.test.*

object ArgsSpec extends ZIOSpecDefault:

  def spec = suite("Args")(
    test("parses catalog update flags") {
      val got = Args.parse(List("catalog", "update", "--yes", "--verify-load", "--file", "project/ZipxVersions.scala"))
      assertTrue(
        got == Right(
          CatalogCommand.Update(
            yes = true,
            dryRun = false,
            verifyLoad = true,
            file = "project/ZipxVersions.scala",
          )
        )
      )
    },
    test("parses generate and check") {
      assertTrue(
        Args.parse(List("catalog", "generate")) == Right(CatalogCommand.Generate(Args.DefaultFile)),
        Args.parse(List("catalog", "check", "--file", "x.scala")) == Right(CatalogCommand.Check("x.scala")),
      )
    },
    test("unknown command is Left") {
      val got = Args.parse(List("steward"))
      assertTrue(got.isLeft, got.swap.exists(_.contains("unknown command")))
    },
  )
end ArgsSpec
