package zipx.core

import zio.test.*

object DepCleanupSpec extends ZIOSpecDefault:

  private val json           = SelectedLib("zioJson", "dev.zio", "zio-json", "1.0.0", "compile")
  private val theme          = SelectedLib("specularTheme", "rocks.earlyeffect", "specular-theme", "0.1.0", "test")
  private val themePullsJson = CallerEdge(
    organization = "dev.zio",
    name = "zio-json_3",
    revision = "0.10.0",
    callerOrganization = "rocks.earlyeffect",
    callerName = "specular-theme_3",
    config = "test",
  )
  private val docsSelectsTheme = CallerEdge(
    organization = "rocks.earlyeffect",
    name = "specular-theme_3",
    revision = "0.1.0",
    callerOrganization = "rocks.earlyeffect",
    callerName = "docs_3",
    config = "test",
  )

  def spec = suite("DepCleanup")(
    test("docs json is redundant when a selected theme already pulls json") {
      val report = DepCleanup.analyze("docs", List(json, theme), List(docsSelectsTheme, themePullsJson))
      assertTrue(
        report.redundant.map(_.selected.valName) == List("zioJson"),
        report.alignable.isEmpty,
        report.render.contains("zioJson"),
        report.render.contains("docs"),
      )
    },
    test("provided compile-only json is not redundant") {
      val provided = json.copy(config = "provided")
      val report   = DepCleanup.analyze("core", List(provided), Nil)
      assertTrue(report.redundant.isEmpty, report.isEmpty)
    },
    test("testkit that is not redundant is alignable when http is already on the graph") {
      val testkit  = SelectedLib("zioHttpTestkit", "dev.zio", "zio-http-testkit", "3.11.4", "test")
      val httpEdge = CallerEdge(
        organization = "dev.zio",
        name = "zio-http_3",
        revision = "3.11.1",
        callerOrganization = "dev.zio",
        callerName = "datastar-http_3",
        config = "compile",
      )
      val report = DepCleanup.analyze("api", List(testkit), List(httpEdge))
      assertTrue(
        report.redundant.isEmpty,
        report.alignable.map(_.selected.valName) == List("zioHttpTestkit"),
        report.alignable.head.familyRevision == "3.11.1",
      )
    },
    test("reachability drops the whole redundant set together") {
      val json2  = SelectedLib("zioJson2", "dev.zio", "zio-json", "1.0.0", "compile")
      val report = DepCleanup.analyze("docs", List(json, json2, theme), List(docsSelectsTheme, themePullsJson))
      assertTrue(report.redundant.map(_.selected.valName).toSet == Set("zioJson", "zioJson2"))
    },
  )
end DepCleanupSpec
