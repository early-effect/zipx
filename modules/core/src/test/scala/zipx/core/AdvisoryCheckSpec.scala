package zipx.core

import zio.test.*

object AdvisoryCheckSpec extends ZIOSpecDefault:

  def spec = suite("AdvisoryCheck")(
    test("mavenPurl is Right for a catalog Lib") {
      val lib = Lib("dev.zio", "zio", "2.1.26")
      assertTrue(AdvisoryCheck.mavenPurl(lib) == Purl.make("pkg:maven/dev.zio/zio@2.1.26"))
    },
    test("githubPurl is Right for owner/repo and a version") {
      assertTrue(
        AdvisoryCheck.githubPurl("actions/checkout", "v7.0.1") ==
          Purl.make("pkg:github/actions/checkout@v7.0.1")
      )
    },
    test("actionQueries is Right for Defaults") {
      val listed = AdvisoryCheck.actionQueries(ActionPins.Defaults)
      assertTrue(listed.nonEmpty, listed.forall(_.isRight))
    },
  )
end AdvisoryCheckSpec
