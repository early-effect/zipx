package zipx.cli

import zipx.core.ReleaseLookup
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object CatalogOpsSpec extends ZIOSpecDefault:

  /** A repository holding exactly these artifacts, named as sbt crosses them. */
  private def repo(published: (String, List[String])*): ReleaseLookup =
    val byName = published.toMap
    (_, artifact) => Right(byName.get(artifact))

  private val header =
    """object MyVersions:
      |  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
      |  val scala: ScalaVersion = ScalaVersion("3.9.0")
      |""".stripMargin

  private def catalog(rows: String) =
    val dir = Files.createTempDirectory("zipx-cli-upd")
    val cat = dir.resolve("ZipxVersions.scala")
    Files.writeString(cat, header + rows, StandardCharsets.UTF_8)
    cat

  def spec = suite("CatalogOps")(
    test("generate writes plugins.sbt from Plugin constructors and keeps self-emit") {
      val dir = Files.createTempDirectory("zipx-cli")
      val cat = dir.resolve("ZipxVersions.scala")
      val sbt = dir.resolve("plugins.sbt")
      Files.writeString(
        cat,
        """object MyVersions:
          |  val sbt: SbtVersion = SbtVersion("2.0.8")
          |  val fmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
          |""".stripMargin,
        StandardCharsets.UTF_8,
      )
      Files.writeString(
        sbt,
        """addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")
          |addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
          |""".stripMargin,
        StandardCharsets.UTF_8,
      )
      val out   = CatalogOps.generate(cat)
      val got   = Files.readString(sbt, StandardCharsets.UTF_8)
      val props = Files.readString(dir.resolve("build.properties"), StandardCharsets.UTF_8)
      assertTrue(
        out.isRight,
        got.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
        got.contains("""addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
        !got.contains("2.6.1"),
        props.contains("sbt.version=2.0.8"),
      )
    },
    test("planUpdate rewrites Lib constructors from the repository") {
      val cat = catalog(
        """  val zio = Lib("dev.zio", "zio", "2.1.26")
          |  val fmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
          |""".stripMargin
      )
      val plan =
        CatalogOps.planUpdate(cat, lookup = repo("zio_3" -> List("2.1.26", "2.1.27")), lookupAction = _ => Right(None))
      assertTrue(
        plan.map(_.depBumps.size) == Right(1),
        plan.exists(_.nextSource.contains("""Lib("dev.zio", "zio", "2.1.27")""")),
        plan.exists(_.nextSource.contains("""Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")""")),
      )
    },
    test("planUpdate reads a JS-only row, a .java row, and a .mod family the way the catalog builds them") {
      val cat = catalog(
        """  val js     = Lib("rocks.earlyeffect", "ascent-js", "0.7.1")
          |  val css    = js.mod("ascent-css")
          |  val brotli = Lib("org.brotli", "dec", "0.1.2").java
          |""".stripMargin
      )
      val plan = CatalogOps.planUpdate(
        cat,
        lookup = repo(
          "ascent-js_sjs1_3"  -> List("0.7.1", "0.9.0", "0.10.0"),
          "ascent-css_3"      -> List("0.7.1", "0.9.0"),
          "ascent-css_sjs1_3" -> List("0.7.1", "0.9.0"),
          "dec"               -> List("0.1.2", "0.1.3"),
        ),
        lookupAction = _ => Right(None),
      )
      assertTrue(
        plan.exists(_.nextSource.contains("""Lib("rocks.earlyeffect", "ascent-js", "0.9.0")""")),
        plan.exists(_.nextSource.contains("""Lib("org.brotli", "dec", "0.1.3").java""")),
        plan.exists(_.holds.exists(_.contains("ascent-js could move to 0.10.0 alone"))),
      )
    },
    test("a catalog that states no Scala or sbt version is refused, not crossed by guess") {
      val dir = Files.createTempDirectory("zipx-cli-bare")
      val cat = dir.resolve("ZipxVersions.scala")
      Files.writeString(cat, "object MyVersions:\n  val zio = Lib(\"dev.zio\", \"zio\", \"2.1.26\")\n")
      val plan = CatalogOps.planUpdate(cat, lookup = repo(), lookupAction = _ => Right(None))
      assertTrue(plan.left.exists(_.contains("no ScalaVersion or SbtVersion")))
    },
    test("planUpdate does not rewrite Ship or ShipGroup constructors") {
      val cat = catalog(
        """  val zio  = Lib("dev.zio", "zio", "2.1.26")
          |  val core = Ship("core", "1.4.2")
          |  val foo  = ShipGroup("foo", "1.4.2")("foo-api", "foo-cli")
          |""".stripMargin
      )
      val plan =
        CatalogOps.planUpdate(cat, lookup = repo("zio_3" -> List("2.1.26", "2.1.27")), lookupAction = _ => Right(None))
      assertTrue(
        plan.exists(_.nextSource.contains("""Lib("dev.zio", "zio", "2.1.27")""")),
        plan.exists(_.nextSource.contains("""Ship("core", "1.4.2")""")),
        plan.exists(_.nextSource.contains("""ShipGroup("foo", "1.4.2")("foo-api", "foo-cli")""")),
      )
    },
  )
end CatalogOpsSpec
