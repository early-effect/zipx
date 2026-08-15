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
One Scala file lists the library and plugin versions this build may use. You pick rows in `build.sbt`. Generate writes
`project/plugins.sbt` and `project/build.properties` so those files stay in sync. When something is stale, run
`zipxDepUpdate`, say yes, commit, and open a pull request. That is the bump path. See **Dependency updates** for the
full local loop.

The usual Scala bump is search-and-replace on version strings scattered through `build.sbt` and `plugins.sbt`. Bots do
it with regex. It misses, double-hits, and rewrites comments. zipx does not. The catalog is typed values the build
*selects*; apply rewrites constructors only; generate owns `plugins.sbt`. That is a stronger source of truth than a
lock file of strings, including Bazel `maven_install` / `MODULE.bazel` coordinates. Details in **Not a string rewrite**.

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
    section("Not a string rewrite of the build")(
      md"""
Scala dependency management has been weak for a long time, and most people do not notice until a bot PR scares them.

The common apply path (including popular update bots) is: find `"group" %% "artifact" % "1.2.3"` somewhere in the
repo, or worse the version token alone, and replace it. That has to work across `build.sbt`, `project/plugins.sbt`, a
`Dependencies.scala`, comments, and docs samples. Miss a call site and you ship mixed versions. Hit a comment and the
diff is noise. There is no type that says "this is a catalog row."

zipx's apply path is the opposite:

| Usual Scala bump | zipx catalog |
|---|---|
| Regex / search-replace across the build | Rewrite `Lib("g", "a", "from")` / `Plugin("g", "a", "from")` only |
| Versions copied into `plugins.sbt` by hand | Generate writes `plugins.sbt` and `build.properties` |
| A raw `%` coordinate is invisible | `zipxCheckDeps` fails generate if `libraryDependencies` is not a `Lib` row |
| Each `"zio-test"` line is another string | `.mod("zio-test")` shares the parent version literal |

Bazel locks a *resolved* graph well (`maven_install.json` and friends). The coordinates you type are still strings, and
bumping them is still editing strings or regenerating JSON. zipx's catalog is the same `Lib` / `Plugin` values
`libraryDependencies` selects. That is the bump path Scala has been missing: typed, one file, mechanically applied,
checked.
"""
    ),
    section("Generated plugins.sbt")(
      md"""
`zipxWorkflowGenerate` writes `project/plugins.sbt`. Consumers get the loaded `sbt-zipx` line first (`zipxEmitSelf`,
default true). This repo sets `zipxEmitSelf := false` because dogfood loads zipx from source. The
[`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo) consumer also sets it false:
CI injects the in-dev plugin via `-Dzipx.version` in `project/zipx.sbt`, and generate owns the other plugin lines.
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
There is no weekly zipx job that opens a catalog PR. You bump locally, then you open the PR.

```text
sbt zipxDepUpdate             # list, then prompt Apply N catalog update(s)? [y/N]
sbt "zipxDepUpdate yes"       # rewrite constructors in zipxVersionsFile
sbt "zipxDepUpdate dry-run"
```

Lookup is Maven Central metadata (then the sbt plugin repo). `yes` applies **every** listed bump. With no terminal, a
bare command lists and stops.

The catalog file lives under `project/`, so it is part of the build definition. After a rewrite, `reload` (or a fresh
sbt) before you generate. If a `Plugin`, `zipxSbt`, or `zipxScala` version moved, run `zipxWorkflowGenerate` and commit
`plugins.sbt` / `build.properties` too. Then test, commit, open a PR.

Apply rewrites `Lib("g", "a", "from")` / `Plugin("g", "a", "from")` only. `.mod` copies share the parent version
literal.
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
