package zipx.syntax

import zipx.core.*
import zio.test.*

object CatalogDiffSpec extends ZIOSpecDefault:

  private val sha1 = "1" * 40
  private val sha2 = "2" * 40

  /** A catalog as `zipxDepUpdate` and the version-updates companion leave it: constructors in canonical form. */
  private def catalog(
      sbt: String = "2.1.0-M2",
      fansi: String = "0.5.1",
      upickle: String = "4.4.2",
      checkout: String = sha1,
      service: String = "library(fansi)",
      extra: String = "",
  ): String =
    s"""|import zipx.*
        |
        |object LabVersions extends ZipxVersions:
        |  val sbt: SbtVersion     = SbtVersion("$sbt")
        |  val scala: ScalaVersion = ScalaVersion("3.9.0")
        |  val fansi               = Lib("com.lihaoyi", "fansi", "$fansi")
        |  val upickle             = Lib("com.lihaoyi", "upickle", "$upickle")
        |  val checkout            = Action("actions/checkout", "v6.0.3", sha = "$checkout")
        |$extra  def service           = $service
        |""".stripMargin

  private val base = catalog()

  private def diff(head: String): List[CatalogChange] = CatalogDiff.between(base, head, "ZipxVersions.scala")

  private def isBuildWide(changes: List[CatalogChange]): Boolean = changes match
    case List(CatalogChange.BuildWide(_)) => true
    case _                                => false

  private val fansiRow = LibCoordinate(GroupId("com.lihaoyi"), ArtifactId("fansi"))

  def spec = suite("CatalogDiff")(
    test("a Lib version bump reads as that row moving") {
      assertTrue(diff(catalog(fansi = "0.5.2")) == List(CatalogChange.LibMoved(fansiRow)))
    },
    test("an Action pin bump reads as that Action moving") {
      assertTrue(diff(catalog(checkout = sha2)) == List(CatalogChange.ActionMoved("actions/checkout")))
    },
    test("two rows moving read as both") {
      assertTrue(diff(catalog(fansi = "0.5.2", upickle = "4.4.3")).size == 2)
    },
    suite("is build-wide")(
      test("when the sbt version moves") {
        assertTrue(isBuildWide(diff(catalog(sbt = "2.1.0"))))
      },
      test("when a group changes which rows a module gets, though no constructor moved") {
        assertTrue(isBuildWide(diff(catalog(service = "library(fansi, upickle)"))))
      },
      test("when a group changes alongside a version bump") {
        assertTrue(isBuildWide(diff(catalog(fansi = "0.5.2", service = "library(fansi, upickle)"))))
      },
      test("when a row is added") {
        assertTrue(isBuildWide(diff(catalog(extra = "  val os = Lib(\"com.lihaoyi\", \"os-lib\", \"0.11.4\")\n"))))
      },
      test("when a constructor is reformatted, so its version literal cannot be matched") {
        val reformatted = base.replace(
          """Lib("com.lihaoyi", "fansi", "0.5.1")""",
          """Lib("com.lihaoyi", "fansi",  "0.5.2")""",
        )
        assertTrue(isBuildWide(diff(reformatted)))
      },
      test("when either side does not parse") {
        assertTrue(isBuildWide(diff("object Broken { val x = ")))
      },
    ),
  )
end CatalogDiffSpec
