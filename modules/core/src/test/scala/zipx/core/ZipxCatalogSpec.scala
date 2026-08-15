package zipx.core

import zio.test.*

object ZipxCatalogSpec extends ZIOSpecDefault:

  def spec = suite("ZipxCatalog")(
    test("renderPlugins writes a generated header and addSbtPlugin lines") {
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val out      = ZipxCatalog.renderPlugins(List(scalafmt), self = Some(zipx))
      assertTrue(
        out.startsWith(ZipxCatalog.PluginsHeader),
        out.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
        out.contains("""addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
        out.indexOf("sbt-zipx") < out.indexOf("sbt-scalafmt"),
      )
    },
    test("renderPlugins does not duplicate self when it is already in the catalog") {
      val zipx = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val out  = ZipxCatalog.renderPlugins(List(zipx), self = Some(zipx))
      assertTrue(out.split("sbt-zipx", -1).length == 2)
    },
    test("renderPlugins omits self when dogfooding") {
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val out      = ZipxCatalog.renderPlugins(List(scalafmt), self = None)
      assertTrue(!out.contains("sbt-zipx"), out.contains("sbt-scalafmt"))
    },
    test("renderPlugins emits excludeAll for bundled plugins") {
      val remote =
        Plugin("org.scala-sbt", "sbt-remote-cache", "2.0.5").excluding(ZipxExclude.org("org.scala-sbt"))
      val out = ZipxCatalog.renderPluginLine(remote)
      assertTrue(
        out.contains("excludeAll"),
        out.contains("""ExclusionRule(organization = "org.scala-sbt")"""),
      )
    },
    test("renderBuildProperties writes sbt.version") {
      val out = ZipxCatalog.renderBuildProperties(SbtVersion("2.0.6"))
      assertTrue(out.contains("sbt.version=2.0.6"), out.startsWith(ZipxCatalog.GeneratedHeader))
    },
    test("extraLibs is empty when every declared GAV is a catalog Lib") {
      val zio      = Lib("dev.zio", "zio", "2.1.26")
      val declared = List(DeclaredGav("dev.zio", "zio", "2.1.26"))
      assertTrue(ZipxCatalog.extraLibs(declared, List(zio)).isEmpty)
    },
    test("extraLibs names declared GAVs that are missing or at another version") {
      val zio   = Lib("dev.zio", "zio", "2.1.26")
      val extra = ZipxCatalog.extraLibs(
        List(
          DeclaredGav("dev.zio", "zio", "2.1.0"),
          DeclaredGav("org.slf4j", "slf4j-simple", "2.0.18"),
        ),
        List(zio),
      )
      assertTrue(
        extra.map(_.render).toSet == Set("dev.zio:zio:2.1.0", "org.slf4j:slf4j-simple:2.0.18")
      )
    },
    test("extraLibs ignores Plugin rows") {
      val plugin = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val extra  = ZipxCatalog.extraLibs(List(DeclaredGav("org.scalameta", "sbt-scalafmt", "2.6.2")), List(plugin))
      assertTrue(extra.size == 1)
    },
    test("extraLibs ignores sbt and Scala.js auto-platform jars") {
      val extra = ZipxCatalog.extraLibs(
        List(
          DeclaredGav("org.scala-lang", "scala3-library", "3.8.4"),
          DeclaredGav("org.scala-js", "scalajs-library", "1.22.0"),
          DeclaredGav("org.scala-js", "scalajs-library_2.13", "1.22.0"),
          DeclaredGav("org.scala-js", "scalajs-test-bridge_2.13", "1.22.0"),
          DeclaredGav("org.slf4j", "slf4j-simple", "2.0.18"),
        ),
        Nil,
      )
      assertTrue(extra.map(_.render) == List("org.slf4j:slf4j-simple:2.0.18"))
    },
    test("scalaMismatch is empty when versions agree") {
      assertTrue(
        ZipxCatalog.scalaMismatch("3.8.4", Some(ScalaVersion("3.8.4"))).isEmpty,
        ZipxCatalog.scalaMismatch("3.8.4", None).isEmpty,
        ZipxCatalog.scalaMismatch("3.7.0", Some(ScalaVersion("3.8.4"))).exists(_.contains("3.7.0")),
      )
    },
    test("outdated ignores equal versions and never rewrites the source") {
      val zio = Lib("dev.zio", "zio", "2.1.26")
      ZipxCatalog.outdated(List(zio), _ => Right(Some("2.1.26"))) match
        case Left(err)    => assertTrue(err.isEmpty)
        case Right(bumps) => assertTrue(bumps.isEmpty)
    },
    test("outdated lists a bump when lookup returns a newer stable") {
      val zio = Lib("dev.zio", "zio", "2.1.26")
      ZipxCatalog.outdated(List(zio), _ => Right(Some("2.1.27"))) match
        case Left(err)    => assertTrue(err.isEmpty)
        case Right(bumps) =>
          assertTrue(
            bumps.size == 1,
            bumps.head.to == "2.1.27",
            bumps.head.bump == BumpKind.Patch,
            ZipxCatalog.formatBumps(Nil) == "no outdated catalog versions",
          )
    },
    test("applyBumps rewrites Lib and Plugin constructors and skips .mod copies") {
      val src =
        """
          |val zio     = Lib("dev.zio", "zio", "2.1.26")
          |val zioTest = zio.mod("zio-test").test
          |val fmt     = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
          |""".stripMargin
      val bumps = List(
        DepBump(Lib("dev.zio", "zio", "2.1.26"), BumpKind.Patch, "2.1.27"),
        DepBump(Lib("dev.zio", "zio-test", "2.1.26"), BumpKind.Patch, "2.1.27"),
        DepBump(Plugin("org.scalameta", "sbt-scalafmt", "2.6.2"), BumpKind.Minor, "2.7.0"),
      )
      ZipxCatalog.applyBumps(src, bumps) match
        case Left(err)  => assertTrue(err.isEmpty)
        case Right(out) =>
          assertTrue(
            out.contains("""Lib("dev.zio", "zio", "2.1.27")"""),
            out.contains("""zio.mod("zio-test")"""),
            !out.contains("""Lib("dev.zio", "zio-test""""),
            out.contains("""Plugin("org.scalameta", "sbt-scalafmt", "2.7.0")"""),
          )
    },
  )
end ZipxCatalogSpec
