package zipx.core

import zio.test.*

object CatalogChangeSpec extends ZIOSpecDefault:

  private def coord(artifact: String): LibCoordinate =
    LibCoordinate(GroupId("com.lihaoyi"), ArtifactId.unsafeMake(artifact))

  private val fansi   = coord("fansi")
  private val upickle = coord("upickle")

  /** `models` ← `svcB`, and only `svcB` declares fansi; `models` declares upickle. */
  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("models"), baseDir = "models", libraries = Set(upickle)),
      ModuleNode(ModuleId("svcB"), dependsOn = List("models"), baseDir = "svc-b", libraries = Set(fansi)),
      ModuleNode(ModuleId("svcA"), dependsOn = List("models"), baseDir = "svc-a"),
    )
  )

  private val catalogPath = "project/ZipxVersions.scala"

  private def affected(changes: List[CatalogChange], files: List[String] = List(catalogPath)): Set[String] =
    Affected.affectedModules(graph, files, List(CatalogEdit(catalogPath, changes).reading(graph)))

  def spec = suite("CatalogChange")(
    test("L6: bumping a row only svcB declares affects svcB alone") {
      assertTrue(affected(List(CatalogChange.LibMoved(fansi))) == Set("svcB"))
    },
    test("a moved row seeds its users, then the reverse closure applies") {
      assertTrue(affected(List(CatalogChange.LibMoved(upickle))) == Set("models", "svcB", "svcA"))
    },
    test("an Action pin moving affects no module") {
      assertTrue(affected(List(CatalogChange.ActionMoved("actions/checkout"))).isEmpty)
    },
    test("anything build-wide affects every module, whatever else moved") {
      check(Gen.elements(fansi, upickle)) { lib =>
        val changes = List(CatalogChange.LibMoved(lib), CatalogChange.BuildWide("the sbt version moved"))
        assertTrue(affected(changes) == graph.ids.toSet)
      }
    },
    test("a moved row no module declares is build-wide: something the graph cannot see uses it") {
      assertTrue(affected(List(CatalogChange.LibMoved(coord("sourcecode")))) == graph.ids.toSet)
    },
    test("another build file in the same diff still affects everything") {
      assertTrue(affected(List(CatalogChange.LibMoved(fansi)), List(catalogPath, "build.sbt")) == graph.ids.toSet)
    },
    test("with no reading of the catalog, the catalog file is a build file as before") {
      assertTrue(Affected.affectedModules(graph, List(catalogPath)) == graph.ids.toSet)
    },
    test("an unrelated edit beside the catalog adds its own module") {
      assertTrue(
        affected(List(CatalogChange.LibMoved(fansi)), List(catalogPath, "svc-a/src/main/scala/A.scala")) ==
          Set("svcB", "svcA")
      )
    },
    test("a moved row carries its .mod copies and the rows aligned to it") {
      val zioCore    = Lib("dev.zio", "zio", "2.1.26")
      val zioStreams = zioCore.mod("zio-streams")
      val zioTest    = zioCore.mod("zio-test").fromGraph
      val other      = Lib("dev.zio", "zio-json", "0.7.44")
      val moved      = CatalogChange.withFamilies(
        List(CatalogChange.LibMoved(zioCore.coordinate)),
        List(zioCore, zioStreams, zioTest, other),
      )
      assertTrue(
        moved.toSet == Set(zioCore, zioStreams, zioTest).map(l => CatalogChange.LibMoved(l.coordinate))
      )
    },
  )
end CatalogChangeSpec
