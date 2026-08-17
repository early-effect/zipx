package zipx.core

import neotype.unwrap
import zio.test.*

object ZipxCatalogSpec extends ZIOSpecDefault:

  def spec = suite("ZipxCatalog")(
    test("renderPlugins writes a generated header and addSbtPlugin lines") {
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val out      = ZipxCatalog.renderPlugins(List(scalafmt), self = List(zipx))
      assertTrue(
        out.startsWith(ZipxCatalog.PluginsHeader),
        out.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
        out.contains("""addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
        out.indexOf("sbt-zipx") < out.indexOf("sbt-scalafmt"),
      )
    },
    test("renderPlugins does not duplicate self when it is already in the catalog") {
      val zipx = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val out  = ZipxCatalog.renderPlugins(List(zipx), self = List(zipx))
      assertTrue(out.split("sbt-zipx", -1).length == 2)
    },
    test("renderPlugins omits self when dogfooding") {
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val out      = ZipxCatalog.renderPlugins(List(scalafmt))
      assertTrue(!out.contains("sbt-zipx"), out.contains("sbt-scalafmt"))
    },
    test("renderPlugins writes zipx, then other self plugins, then catalog plugins") {
      val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
      val acme     = Plugin("com.acme", "sbt-acme", "9.9.9")
      val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      val out      = ZipxCatalog.renderPlugins(List(scalafmt), self = List(zipx, acme))
      assertTrue(
        out.contains("""addSbtPlugin("com.acme" % "sbt-acme" % "9.9.9")"""),
        out.indexOf("sbt-zipx") < out.indexOf("sbt-acme"),
        out.indexOf("sbt-acme") < out.indexOf("sbt-scalafmt"),
      )
    },
    test("renderPlugins drops a catalog row whose group and artifact are already self-emitted") {
      val acmeLoaded  = Plugin("com.acme", "sbt-acme", "9.9.9")
      val acmeCatalog = Plugin("com.acme", "sbt-acme", "0.0.1")
      val out         = ZipxCatalog.renderPlugins(List(acmeCatalog), self = List(acmeLoaded))
      assertTrue(
        out.contains("""addSbtPlugin("com.acme" % "sbt-acme" % "9.9.9")"""),
        !out.contains("0.0.1"),
      )
    },
    test("Lib.excluding accumulates on a java copy") {
      val coursier = Lib("io.get-coursier", "coursier-cache_2.13", "2.1.25-M26").java
        .excluding(ZipxExclude.org("org.scala-lang.modules"))
      val twice = coursier.excluding(ZipxExclude.org("com.example", "example-lib"))
      assertTrue(
        coursier.cross == Cross.Java,
        coursier.excludes == List(ZipxExclude.org("org.scala-lang.modules")),
        twice.excludes.size == 2,
      )
    },
    test("duplicateSelf names the repeated group and artifact") {
      val a = Plugin("com.acme", "sbt-acme", "1.0.0")
      val b = Plugin("com.acme", "sbt-acme", "2.0.0")
      assertTrue(
        ZipxCatalog.duplicateSelf(List(a)).isEmpty,
        ZipxCatalog.duplicateSelf(List(a, b)).exists(_.contains("com.acme % sbt-acme")),
      )
    },
    test("ZipxSelf.plugin uses an explicit version and refuses when none is resolved") {
      val from = classOf[nomanifest.Empty]
      val ok   = ZipxSelf.plugin("com.acme", "sbt-acme", from, version = Some("1.2.3"))
      val miss = ZipxSelf.fromVersion("com.acme", "sbt-acme", None, from.getName)
      assertTrue(
        ok == Right(Plugin("com.acme", "sbt-acme", "1.2.3")),
        miss == Left(
          s"zipx: cannot emit com.acme % sbt-acme: Implementation-Version is missing. Pass version, or set it on ${from.getName}."
        ),
      )
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
    test("extraLibs ignores sbt, Scala.js, and Scala Native auto-platform jars") {
      val extra = ZipxCatalog.extraLibs(
        List(
          DeclaredGav("org.scala-lang", "scala3-library", "3.8.4"),
          DeclaredGav("org.scala-js", "scalajs-library", "1.22.0"),
          DeclaredGav("org.scala-js", "scalajs-library_2.13", "1.22.0"),
          DeclaredGav("org.scala-js", "scalajs-test-bridge_2.13", "1.22.0"),
          DeclaredGav("org.scala-native", "nativelib", "0.5.12"),
          DeclaredGav("org.scala-native", "nativelib_native0.5_3", "0.5.12"),
          DeclaredGav("org.scala-native", "scala3lib", "3.8.4+0.5.12"),
          DeclaredGav("org.scala-native", "test-interface", "0.5.12"),
          DeclaredGav("org.scala-native", "clib", "0.5.12"),
          DeclaredGav("org.scala-native", "posixlib", "0.5.12"),
          DeclaredGav("org.scala-native", "windowslib", "0.5.12"),
          DeclaredGav("org.scala-native", "javalib", "0.5.12"),
          DeclaredGav("org.scala-native", "auxlib", "0.5.12"),
          DeclaredGav("org.scala-native", "bindgen", "0.1.0"),
          DeclaredGav("org.slf4j", "slf4j-simple", "2.0.18"),
        ),
        Nil,
      )
      assertTrue(
        extra.map(_.render).toSet == Set(
          "org.scala-native:bindgen:0.1.0",
          "org.slf4j:slf4j-simple:2.0.18",
        )
      )
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
    test("outdated skips a pre-release by default") {
      val slf4j = Lib("org.slf4j", "slf4j-simple", "2.0.18")
      ZipxCatalog.outdated(List(slf4j), _ => Right(Some("2.1.0-alpha1"))) match
        case Left(err)    => assertTrue(err.isEmpty)
        case Right(bumps) => assertTrue(bumps.isEmpty)
    },
    test("outdated lists a pre-release when Include") {
      val slf4j = Lib("org.slf4j", "slf4j-simple", "2.0.18")
      ZipxCatalog.outdated(List(slf4j), _ => Right(Some("2.1.0-alpha1")), preRelease = PreRelease.Include) match
        case Left(err)    => assertTrue(err.isEmpty)
        case Right(bumps) =>
          assertTrue(
            bumps.size == 1,
            bumps.head.to == "2.1.0-alpha1",
            bumps.head.bump == BumpKind.PreRelease,
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
    test("pinsOf collects Pin vals and skips Lib, lists, and defs") {
      trait Catalog:
        inline def pins: Seq[Pin] = ZipxCatalog.pinsOf[this.type](this)
      object Sample extends Catalog:
        val zio    = Lib("dev.zio", "zio", "2.1.26")
        val preact = Pin("cdn", "preact", "10.26.4", sha256 = "abc", purl = "pkg:npm/preact@10.26.4")
        val htm    = Pin("cdn", "htm", "3.1.1")
        val listed = List(preact)
        def unused = preact
      val ids = Sample.pins.map(_.id)
      assertTrue(ids == List("preact", "htm"))
    },
    test("applyPinBumps rewrites version, sha256, and purl together") {
      val src =
        """val preact = Pin("cdn", "preact", "10.26.4", sha256 = "abc", purl = "pkg:npm/preact@10.26.4")
          |val zio    = Lib("dev.zio", "zio", "2.1.26")
          |""".stripMargin
      val pin  = Pin("cdn", "preact", "10.26.4", sha256 = "abc", purl = "pkg:npm/preact@10.26.4")
      val bump =
        PinBump(pin, BumpKind.Patch, PinCandidate("10.26.5", Some("def"), Some(Purl("pkg:npm/preact@10.26.5"))))
      ZipxCatalog.applyPinBumps(src, List(bump)) match
        case Left(err)  => assertTrue(err.isEmpty)
        case Right(out) =>
          assertTrue(
            out.contains("""Pin("cdn", "preact", "10.26.5", sha256 = "def", purl = "pkg:npm/preact@10.26.5")"""),
            out.contains("""Lib("dev.zio", "zio", "2.1.26")"""),
            !out.contains("10.26.4"),
          )
    },
    test("applyPinBumps is Left when the constructor is missing") {
      val pin  = Pin("cdn", "preact", "10.26.4", sha256 = "abc", purl = "pkg:npm/preact@10.26.4")
      val bump = PinBump(pin, BumpKind.Patch, PinCandidate("10.26.5"))
      ZipxCatalog.applyPinBumps("val zio = Lib(\"dev.zio\", \"zio\", \"2.1.26\")\n", List(bump)) match
        case Left(err) => assertTrue(err.contains("no Pin constructor"), err.contains("preact"))
        case Right(_)  => assertTrue(false)
    },
    test("unknownPinFeeds names pins whose feed is not registered") {
      val pins  = List(Pin("cdn", "preact", "10.26.4"))
      val feeds = Seq.empty[PinFeed]
      assertTrue(
        ZipxCatalog.unknownPinFeeds(feeds, pins).exists(_.contains("preact")),
        ZipxCatalog.unknownPinFeeds(feeds, Nil).isEmpty,
      )
    },
    test("coordsOf collects Lib and Plugin vals and skips SbtVersion, groups, and lists") {
      trait Catalog:
        inline def coords: Seq[ZipxCoord] = ZipxCatalog.coordsOf[this.type](this)
      object Sample extends Catalog:
        val sbt: SbtVersion     = SbtVersion("2.0.6")
        val scala: ScalaVersion = ScalaVersion("3.8.4")
        val zio                 = Lib("dev.zio", "zio", "2.1.26")
        val zioTest             = zio.mod("zio-test").test
        val packager            = Plugin("com.github.sbt", "sbt-native-packager", "1.11.7")
        val fmt                 = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
        val libs                = List(zio)
        def service             = List(zio)
      val arts = Sample.coords.map(c => c.artifact: String)
      assertTrue(
        arts == List("zio", "zio-test", "sbt-native-packager", "sbt-scalafmt")
      )
    },
    test("coordsOf collects a parent-trait Lib val and an AsCoords bundle") {
      final case class Bundle(runtime: Lib, plugin: Plugin)
      object Bundle:
        given AsCoords[Bundle] with
          def coords(b: Bundle): Seq[ZipxCoord] = Seq(b.runtime, b.plugin)
      trait Spliceish:
        val fromTrait = Lib("com.acme", "from-trait", "1.0.0")
      trait Catalog extends Spliceish:
        inline def coords: Seq[ZipxCoord] = ZipxCatalog.coordsOf[this.type](this)
      object Sample extends Catalog:
        val bundle = Bundle(
          Lib("com.acme", "runtime", "1.2.3"),
          Plugin("com.acme", "sbt-acme", "1.2.3"),
        )
      val ids = Sample.coords.map(c => c.artifact: String)
      assertTrue(ids == List("from-trait", "runtime", "sbt-acme"))
    },
    test("actionsOf collects Action vals and skips Lib") {
      trait Catalog:
        inline def actions: Seq[Action] = ZipxCatalog.actionsOf[this.type](this)
      object Sample extends Catalog:
        val zio      = Lib("dev.zio", "zio", "2.1.26")
        val checkout = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
      assertTrue(Sample.actions.map(_.name) == List("actions/checkout"))
    },
    test("applyActionBumps rewrites version and sha together") {
      val src =
        """val checkout = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
          |val zio      = Lib("dev.zio", "zio", "2.1.26")
          |""".stripMargin
      val action = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
      val bump   = ActionBump(action, BumpKind.Minor, "v8.0.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
      ZipxCatalog.applyActionBumps(src, List(bump)) match
        case Left(err)  => assertTrue(err.isEmpty)
        case Right(out) =>
          assertTrue(
            out.contains("""Action("actions/checkout", "v8.0.0", sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")"""),
            out.contains("""Lib("dev.zio", "zio", "2.1.26")"""),
            !out.contains("v7.0.1"),
          )
    },
    test("applyActionBumps is Left when the constructor is missing") {
      val action = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
      val bump   = ActionBump(action, BumpKind.Patch, "v7.0.2", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
      ZipxCatalog.applyActionBumps("val zio = Lib(\"dev.zio\", \"zio\", \"2.1.26\")\n", List(bump)) match
        case Left(err) =>
          assertTrue(err.contains("no Action constructor"), err.contains("zipxActionUpdate yes"))
        case Right(_) => assertTrue(false)
    },
    test("leftover pin file error names constructors and generate") {
      val err = ZipxCatalog.leftoverPinFileError(ActionPinFile.DefaultPath, ActionPins.Bootstrap)
      assertTrue(
        err.contains("Action(\"actions/checkout\""),
        err.contains("zipxWorkflowGenerate"),
        err.contains(ActionPinFile.DefaultPath),
      )
    },
    test("overlay updates a Field by prefix and extra by name") {
      val checkout = Action("actions/checkout", "v9.0.0", sha = "cccccccccccccccccccccccccccccccccccccccc")
      val aws      = Action(
        "aws-actions/configure-aws-credentials",
        "v6.9.9",
        sha = "1111111111111111111111111111111111111111",
      )
      ActionPins.overlay(ActionPins.Bootstrap, List(checkout, aws)) match
        case Left(err)   => assertTrue(err.isEmpty)
        case Right(pins) =>
          assertTrue(
            pins.checkout.unwrap.endsWith("cccccccccccccccccccccccccccccccccccccccc"),
            pins.version(ActionPins.Field.Checkout).contains("v9.0.0"),
            pins.extraByPrefix("aws-actions/configure-aws-credentials").exists(_.unwrap.contains("11111111")),
          )
    },
    test("overlay refuses a duplicate Action name") {
      val a = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
      val b = Action("actions/checkout", "v8.0.0", sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
      ActionPins.overlay(ActionPins.Bootstrap, List(a, b)) match
        case Left(err) => assertTrue(err.contains("duplicate Action name"), err.contains("actions/checkout"))
        case Right(_)  => assertTrue(false)
    },
    test("Action.make refuses a short SHA and a name with @") {
      assertTrue(
        Action.make("actions/checkout", "v7.0.1", "abc").left.exists(_.contains("40 hex")),
        Action.make("actions/checkout@v7", "v7.0.1", "3d3c42e5aac5ba805825da76410c181273ba90b1").isLeft,
        Action.make("", "v7.0.1", "3d3c42e5aac5ba805825da76410c181273ba90b1").isLeft,
        Action.make("actions/checkout", "v7.0.1", "3d3c42e5aac5ba805825da76410c181273ba90b1").isRight,
      )
    },
  )
end ZipxCatalogSpec
