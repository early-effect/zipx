package zipx.cli

import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object CatalogOpsSpec extends ZIOSpecDefault:

  def spec = suite("CatalogOps")(
    test("generate writes plugins.sbt from Plugin constructors and keeps self-emit") {
      val dir = Files.createTempDirectory("zipx-cli")
      val cat = dir.resolve("ZipxVersions.scala")
      val sbt = dir.resolve("plugins.sbt")
      Files.writeString(
        cat,
        """object MyVersions:
          |  val sbt: SbtVersion = SbtVersion("2.0.6")
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
        props.contains("sbt.version=2.0.6"),
      )
    },
    test("planUpdate rewrites Lib constructors from injected lookup") {
      val dir = Files.createTempDirectory("zipx-cli-upd")
      val cat = dir.resolve("ZipxVersions.scala")
      Files.writeString(
        cat,
        """object MyVersions:
          |  val zio = Lib("dev.zio", "zio", "2.1.26")
          |  val fmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
          |""".stripMargin,
        StandardCharsets.UTF_8,
      )
      val plan = CatalogOps.planUpdate(
        cat,
        lookupCoord = {
          case c if c.artifact == "zio" => Right(Some("2.1.27"))
          case _                        => Right(None)
        },
        lookupAction = _ => Right(None),
      )
      assertTrue(
        plan.isRight,
        plan.toOption.exists(_.depBumps.size == 1),
        plan.toOption.exists(_.nextSource.contains("""Lib("dev.zio", "zio", "2.1.27")""")),
        plan.toOption.exists(_.nextSource.contains("""Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")""")),
      )
    },
    test("planUpdate does not rewrite Ship or ShipGroup constructors") {
      val dir = Files.createTempDirectory("zipx-cli-ships")
      val cat = dir.resolve("ZipxVersions.scala")
      Files.writeString(
        cat,
        """object MyVersions:
          |  val zio  = Lib("dev.zio", "zio", "2.1.26")
          |  val core = Ship("core", "1.4.2")
          |  val foo  = ShipGroup("foo", "1.4.2")("foo-api", "foo-cli")
          |""".stripMargin,
        StandardCharsets.UTF_8,
      )
      val plan = CatalogOps.planUpdate(
        cat,
        lookupCoord = {
          case c if c.artifact == "zio" => Right(Some("2.1.27"))
          case _                        => Right(None)
        },
        lookupAction = _ => Right(None),
      )
      assertTrue(
        plan.isRight,
        plan.toOption.exists(_.nextSource.contains("""Lib("dev.zio", "zio", "2.1.27")""")),
        plan.toOption.exists(_.nextSource.contains("""Ship("core", "1.4.2")""")),
        plan.toOption.exists(_.nextSource.contains("""ShipGroup("foo", "1.4.2")("foo-api", "foo-cli")""")),
      )
    },
  )
end CatalogOpsSpec
