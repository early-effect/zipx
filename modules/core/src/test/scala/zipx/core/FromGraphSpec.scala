package zipx.core

import zio.test.*

object FromGraphSpec extends ZIOSpecDefault:

  private val http    = Lib("dev.zio", "zio-http", "3.11.4")
  private val testkit = http.mod("zio-http-testkit").test.fromGraph

  def spec = suite("FromGraph")(
    test("mod.fromGraph captures the pre-mod artifact") {
      assertTrue(
        testkit.artifact == ArtifactId("zio-http-testkit"),
        testkit.alignTo.contains(ArtifactId("zio-http")),
        testkit.family.contains(ArtifactId("zio-http")),
        testkit.config.contains("test"),
      )
    },
    test("revisions uses the selected probe revision, not the parent literal") {
      val probe = List(ProbeModule("dev.zio", "zio-http_3", "3.11.1"))
      FromGraph.revisions(FromGraph.requests(List(testkit), _ => "zio-http-testkit_3"), probe) match
        case Left(err)           => assertTrue(err.isEmpty)
        case Right(List((_, v))) => assertTrue(v == "3.11.1")
        case Right(other)        => assertTrue(other.size == 1)
    },
    test("missing source GAV is a zipx error") {
      val err = FromGraph.revisions(FromGraph.requests(List(testkit), _ => "x"), Nil)
      assertTrue(err.swap.exists(_.contains("did not find")))
    },
    test("multiple selected revisions are a zipx error") {
      val probe = List(
        ProbeModule("dev.zio", "zio-http_3", "3.11.1"),
        ProbeModule("dev.zio", "zio-http_3", "3.10.0"),
      )
      val err = FromGraph.revisions(FromGraph.requests(List(testkit), _ => "x"), probe)
      assertTrue(err.swap.exists(_.contains("multiple revisions")))
    },
    test("chained fromGraph is refused") {
      val a   = Lib("demo", "http", "1.0.0").mod("http-testkit").fromGraph
      val b   = a.mod("http-testkit-extra").fromGraph
      val err = FromGraph.revisions(FromGraph.requests(List(a, b), _ => "x"), Nil)
      assertTrue(err.swap.exists(_.contains("cannot chain")))
    },
    test("artifactFamily strips Scala and sjs suffixes") {
      assertTrue(
        FromGraph.artifactFamily("zio-http_3") == "zio-http",
        FromGraph.artifactFamily("zio-http_sjs1_3") == "zio-http",
        FromGraph.artifactFamily("slf4j-simple") == "slf4j-simple",
      )
    },
  )
end FromGraphSpec
