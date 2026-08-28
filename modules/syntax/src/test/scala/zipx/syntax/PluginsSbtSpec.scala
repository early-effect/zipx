package zipx.syntax

import zipx.core.*
import zio.test.*

object PluginsSbtSpec extends ZIOSpecDefault:

  def spec = suite("PluginsSbt")(
    test("parsePlugins round-trips renderPlugins including excludeAll") {
      val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val remote   =
        Plugin("org.scala-sbt", "sbt-remote-cache", "2.0.8").excluding(ZipxExclude.org("org.scala-sbt"))
      val rendered = ZipxCatalog.renderPlugins(List(scalafmt, remote), self = List(zipx))
      val expected = ZipxCatalog.pluginInventory(List(scalafmt, remote), self = List(zipx))
      assertTrue(PluginsSbt.parse(rendered) == Right(expected))
    },
    test("parsePlugins treats % alignment, extra parens, and wrapping as trivia") {
      val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val remote   =
        Plugin("org.scala-sbt", "sbt-remote-cache", "2.0.8").excluding(ZipxExclude.org("org.scala-sbt"))
      val aligned =
        s"""${ZipxCatalog.PluginsHeader}
           |addSbtPlugin("rocks.earlyeffect" % "sbt-zipx"     % "0.5.1")
           |addSbtPlugin("org.scalameta"     % "sbt-scalafmt"  % "2.6.2")
           |addSbtPlugin(
           |  ("org.scala-sbt" % "sbt-remote-cache" % "2.0.8")
           |    .excludeAll(ExclusionRule(organization = "org.scala-sbt"))
           |)
           |""".stripMargin
      val expected = List(zipx, scalafmt, remote)
      assertTrue(PluginsSbt.parse(aligned) == Right(expected))
    },
    test("parsePlugins refuses resolvers and %%") {
      val resolvers = PluginsSbt.parse("""resolvers += Resolver.sonatypeCentralRepo""")
      val crossed   = PluginsSbt.parse("""addSbtPlugin("org.scalameta" %% "sbt-scalafmt" % "2.6.2")""")
      assertTrue(
        resolvers == Left("unexpected 'resolvers'"),
        crossed == Left("uses %%; generated plugins are %"),
      )
    },
    test("checkPlugins is green when bytes differ but inventory matches") {
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val expected = List(scalafmt)
      val aligned  = """addSbtPlugin("org.scalameta"     % "sbt-scalafmt"  % "2.6.2")""" + "\n"
      assertTrue(ZipxCatalog.checkPlugins("project/plugins.sbt", PluginsSbt.parse(aligned), expected).isRight)
    },
    test("checkPlugins names a version drift") {
      val expected = List(Plugin("org.scalameta", "sbt-scalafmt", "2.6.2"))
      val got      = """addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.3")"""
      val err      = ZipxCatalog.checkPlugins("project/plugins.sbt", PluginsSbt.parse(got), expected)
      assertTrue(
        err.isLeft,
        err.swap.exists(_.contains("plugin list drifted")),
        err.swap.exists(_.contains("2.6.2")),
        err.swap.exists(_.contains("2.6.3")),
        err.swap.exists(_.contains("zipxCatalogGenerate")),
      )
    },
    test("checkPlugins names a parse error on an extra statement") {
      val expected = List(Plugin("org.scalameta", "sbt-scalafmt", "2.6.2"))
      val src      =
        """addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
          |resolvers += "x"
          |""".stripMargin
      val err = ZipxCatalog.checkPlugins("project/plugins.sbt", PluginsSbt.parse(src), expected)
      assertTrue(err.swap.exists(_.contains("unexpected 'resolvers'")))
    },
    test("mergePlugins keeps self-emit and overlays catalog versions") {
      val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val bumped   = Plugin("org.scalameta", "sbt-scalafmt", "2.7.0")
      val extra    = Plugin("com.acme", "sbt-acme", "1.0.0")
      val merged   = ZipxCatalog.mergePlugins(List(bumped, extra), List(zipx, scalafmt))
      assertTrue(
        merged == List(zipx, bumped, extra)
      )
    },
    test("span apply rewrites Lib and Plugin constructors and skips .mod copies") {
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
      CatalogApply.applyBumps(src, bumps) match
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
end PluginsSbtSpec
