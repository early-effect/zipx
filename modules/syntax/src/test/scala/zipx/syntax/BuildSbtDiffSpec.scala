package zipx.syntax

import zipx.core.*
import zio.test.*

object BuildSbtDiffSpec extends ZIOSpecDefault:

  /** The lab's shape: a cross-built svcA read by imageIt through an alias, a shared helper, and an aggregator. */
  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("lib"), baseDir = "modules/lib"),
      ModuleNode(ModuleId("svcA"), dependsOn = List("lib"), baseDir = ".sbt/matrix/svcA"),
      ModuleNode(ModuleId("svcAJS"), baseDir = ".sbt/matrix/svcAJS", matrixRootOpt = Some(ModuleId("svcA"))),
      ModuleNode(ModuleId("svcB"), dependsOn = List("lib"), baseDir = "modules/svc-b"),
      ModuleNode(ModuleId("imageIt"), baseDir = "modules/image-it"),
      ModuleNode(ModuleId("root"), ciRelevant = false),
    )
  )

  private def build(
      svcA: String = """.settings(name := "svc-a")""",
      svcB: String = """.settings(name := "svc-b")""",
      helper: String = """publish / skip := false""",
      bare: String = """scalaVersion := "3.9.0"""",
      root: String = ".aggregate(lib, svcB)",
      extra: String = "",
  ): String =
    s"""|$bare
        |
        |def publishedLibrary: Seq[Setting[?]] = Seq($helper)
        |
        |lazy val lib = (project in file("modules/lib")).settings(publishedLibrary)
        |
        |lazy val svcA = (projectMatrix in file("modules/svc-a"))
        |  .dependsOn(lib)
        |  $svcA
        |
        |lazy val svcB = (project in file("modules/svc-b")).dependsOn(lib)$svcB
        |
        |// read through an alias, as the lab's image test does
        |val svcAJvm = LocalProject("svcA")
        |
        |lazy val imageIt = (project in file("modules/image-it")).settings(dockerAlias := (svcAJvm / dockerAlias).value)
        |
        |lazy val root = (project in file("."))$root
        |$extra""".stripMargin

  private val base = build()

  private def read(head: String): Option[Set[String]] = BuildSbtDiff.reading(base, head, graph).seeds

  def spec = suite("BuildSbtDiff")(
    test("L7a: a setting inside svcA's definition affects svcA's rows and the project that reads svcA") {
      val head = build(svcA = """.settings(name := "svc-a", dockerExposedPorts := Seq(8080))""")
      assertTrue(read(head).contains(Set("svcA", "svcAJS", "imageIt")))
    },
    test("a setting inside svcB's definition affects svcB alone") {
      assertTrue(read(build(svcB = """.settings(name := "svc-b", run / fork := true)""")).contains(Set("svcB")))
    },
    test("L7b: a shared helper affects every module") {
      assertTrue(read(build(helper = """publish / skip := false, pomIncludeRepository := (_ => false)""")).isEmpty)
    },
    test("a bare setting affects every module, since sbt 2 applies it to every project") {
      assertTrue(read(build(bare = """scalaVersion := "3.9.1"""")).isEmpty)
    },
    test("an aggregator's definition affects every module") {
      assertTrue(read(build(root = ".aggregate(lib, svcA, svcB)")).isEmpty)
    },
    test("adding a definition affects every module") {
      assertTrue(read(build(extra = """lazy val svcC = project.dependsOn(lib)""")).isEmpty)
    },
    test("a bare setting naming a changed project affects every module") {
      val head = build(
        svcA = """.settings(name := "svc-a2")""",
        extra =
          """ThisBuild / zipxCapabilities += zipxTasks.once(name = CapabilityName("smoke"), command = svcA / test)""",
      )
      val baseWith = build(
        extra =
          """ThisBuild / zipxCapabilities += zipxTasks.once(name = CapabilityName("smoke"), command = svcA / test)"""
      )
      assertTrue(BuildSbtDiff.reading(baseWith, head, graph).seeds.isEmpty)
    },
    test("a comment-only edit affects nothing") {
      assertTrue(
        read(base.replace("// read through an alias", "// read through a LocalProject alias")).contains(Set.empty)
      )
    },
    test("an unparseable file affects every module") {
      assertTrue(read(base + "\nlazy val broken = (project in file(\"x\")").isEmpty)
    },
  )
end BuildSbtDiffSpec
