package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zio.test.*

/** Typed versions catalog: one Scala file, generated plugins.sbt, local zipxDepUpdate. */
object Versions extends DocSpecSuite:

  private val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  private val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
  private val zio      = Lib("dev.zio", "zio", "2.1.26")

  def doc = page("Versions")(
    md"""
Scala Steward's hard part is rewriting `build.sbt`. zipx makes apply trivial: one typed catalog, generated
`project/plugins.sbt` / `project/build.properties`, and `zipxDepUpdate` that only rewrites version literals in that
file.

```scala
// project/ZipxVersions.scala
import zipx.core.*

object ZipxVersions:
  val sbt: SbtVersion      = SbtVersion("2.0.6")
  val scala3: ScalaVersion = ScalaVersion("3.8.4")
  val zio                  = Lib("dev.zio", "zio", "2.1.26")
  val zioTest              = zio.mod("zio-test").test
  val scalafmt             = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  def coords: Seq[ZipxCoord] = List(zio, zioTest, scalafmt)
```
""",
    section("Select, do not inline")(
      md"""
```scala
ThisBuild / scalaVersion := ZipxVersions.scala3
libraryDependencies ++= ZipxDeps(ZipxVersions.zio, ZipxVersions.zioTest)

zipxVersions  := ZipxVersions.coords
zipxSbt       := Some(ZipxVersions.sbt)
zipxScala     := Some(ZipxVersions.scala3)
zipxCheckDeps := true
```

A raw `"dev.zio" %% "zio" % "2.1.26"` still compiles. `zipxWorkflowGenerate` fails if that GAV is not a `Lib` row.
""",
      exampleValue {
        ZipxCatalog
          .extraLibs(List(DeclaredGav("org.slf4j", "slf4j-simple", "2.0.18")), List(zio))
          .map(_.render)
          .mkString("\n")
      }.assert(text => assertTrue(text.contains("org.slf4j:slf4j-simple:2.0.18"))),
    ),
    section("Generated plugins.sbt")(
      md"""
`zipxWorkflowGenerate` writes `project/plugins.sbt`. Consumers get the loaded `sbt-zipx` line first (`zipxEmitSelf`,
default true). This repo sets `zipxEmitSelf := false` because dogfood loads zipx from source.
""",
      exampleValue {
        ZipxCatalog.renderPlugins(List(scalafmt), self = Some(zipx))
      }.assert(text =>
        assertTrue(
          text.startsWith(ZipxCatalog.PluginsHeader),
          text.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
          text.contains("""addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
          text.indexOf("sbt-zipx") < text.indexOf("sbt-scalafmt"),
        )
      ),
    ),
    section("Local update")(
      md"""
```text
sbt zipxDepUpdate             # list, then prompt
sbt "zipxDepUpdate yes"       # rewrite constructors in zipxVersionsFile
sbt "zipxDepUpdate dry-run"
```

Lookup is Maven Central metadata (then the sbt plugin repo). Apply rewrites `Lib("g", "a", "from")` /
`Plugin("g", "a", "from")` only. `.mod` copies share the parent version literal.
""",
      exampleValue {
        val src   = """val zio = Lib("dev.zio", "zio", "2.1.26")
val test = zio.mod("zio-test").test
"""
        val bumps = List(DepBump(zio, BumpKind.Patch, "2.1.27"))
        ZipxCatalog.applyBumps(src, bumps).yaml
      }.assert(text =>
        assertTrue(
          text.contains("""Lib("dev.zio", "zio", "2.1.27")"""),
          text.contains("""zio.mod("zio-test")"""),
        )
      ),
    ),
  )
end Versions
