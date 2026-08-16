package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zio.test.*

/** Typed versions catalog: extend ZipxVersions, drop settings, bump locally. */
object Versions extends DocSpecSuite:

  private val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  private val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
  private val zio      = Lib("dev.zio", "zio", "2.1.26")

  def doc = page("Versions")(
    md"""
Extend `ZipxVersions`. Drop `MyVersions.settings` at the top of `build.sbt`. Every `Lib` / `Plugin` / `Pin` / `Action`
**val** is a catalog row; zipx collects them. You do not write a second `coords` list. Each module picks a group. That
is the catalog: one object, not version strings copied through `build.sbt` and `plugins.sbt`.

```scala
// project/ZipxVersions.scala
import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val zio                 = Lib("dev.zio", "zio", "2.1.26")
  val zioTest             = zio.mod("zio-test").test
  val slf4j               = Lib("org.slf4j", "slf4j-simple", "2.0.18").java
  val scalafmt            = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val preact              = Pin("cdn", "preact", "10.26.4", sha256 = "sha256-abc", purl = "pkg:npm/preact@10.26.4")
  val checkout            = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")

  def libraries = library(zio, zioTest)
  def client    = library(zio)
  def service   = library(zio, slf4j)
```

```scala
// build.sbt
MyVersions.settings

lazy val core    = project.settings(MyVersions.cross, MyVersions.libraries)
lazy val client  = project.settings(MyVersions.cross, MyVersions.client)
lazy val service = project.settings(MyVersions.service)
```

`.sbt` files get plugin autoImport for free. Scala sources under `project/` import `zipx.*` and extend the trait. The
object name is yours. `settings` is a bare sbt 2 common setting (`scalaVersion`, `zipxVersions`, `zipxPins`,
`zipxActionRows`, `zipxCheckDeps`).
No `ThisBuild /`. Named groups (`libraries`, `client`, `service`) are `def`s on your object that *select* rows; a service
is not a zipx type. `cross`, `deps`, `library`, and `moduleID` are the other paved methods. Override `crossScala` for
2.13 + 3.

When a row is stale, `zipxDepUpdate` (Maven), `zipxPinUpdate` (`Pin` vals), or `zipxActionUpdate` (`Action` vals), say
yes, commit, open a pull request. Generate writes `project/plugins.sbt` and `project/build.properties`. See
**Dependency updates** for the full local loop.
""",
    section("Every val is a row")(
      md"""
zipx collects every val whose type has an `AsCoords`, `AsPins`, or `AsActions` given. `Lib` and `Plugin` share
`AsCoords`; `Pin` has `AsPins`; `Action` has `AsActions`. A `def` is not a val, so it is not a row. That is why groups
are `def libraries = library(zio)`: selection, not a second copy of the coordinate.

| On the object | Catalog row? |
|---|---|
| `val zio = Lib(...)` | yes |
| `val zioTest = zio.mod("zio-test").test` | yes (`zipxCheckDeps` sees it; apply rewrites the parent constructor) |
| `val coursier = Lib(...).java.excluding(...)` | yes (excludes live on the row) |
| `val scalafmt = Plugin(...)` | yes (generate writes `plugins.sbt`) |
| `val preact = Pin("cdn", "preact", …)` | yes (`zipxPins`; apply rewrites version, sha256, and purl together) |
| `val checkout = Action("actions/checkout", …, sha = …)` | yes (`zipxActionRows`; apply rewrites version and git SHA together) |
| `val sbt` / `val scala` | no (`SbtVersion` / `ScalaVersion` are not Maven coordinates) |
| `def service = library(zio, slf4j)` | no (picks rows for a module) |
| `val libs = List(zio)` | no (a list has no `AsCoords` given; do not add one for `List`) |
| a bundle val with `given AsCoords[YourType]` / `AsPins` | yes (plugin authors: **Extending Versions**) |

A row no module selects is still legal. Unused plugins still land in `plugins.sbt`.
"""
    ),
    section("Select, do not paste")(
      md"""
A raw `"dev.zio" %% "zio" % "2.1.26"` still compiles. `zipxWorkflowGenerate` fails if that GAV is not a `Lib` row.
The service can take `slf4j` while the client does not.
""",
      exampleValue {
        ZipxCatalog
          .extraLibs(List(DeclaredGav("org.slf4j", "slf4j-simple", "2.0.18")), List(zio))
          .map(_.render)
          .mkString("\n")
      }.assert(text => assertTrue(text.contains("org.slf4j:slf4j-simple:2.0.18"))),
    ),
    section("Excludes live on the row")(
      md"""
Do not put `excludeAll` at the `libraryDependencies` use site. The catalog row is the whole coordinate: GAV, cross
(`.java` / `.full`), config (`.test`), and Maven excludes (`.excluding`). `Lib` and `Plugin` share that last helper.
`ZipxExclude.org` is organization-only; pass a second argument for an organization plus artifact. `zipxCheckDeps` still
compares GAV only. Lib excludes never appear in `plugins.sbt`; that file is plugins only.
""",
      exampleValue {
        val coursier = Lib("io.get-coursier", "coursier-cache_2.13", "2.1.25-M26").java
          .excluding(ZipxExclude.org("org.scala-lang.modules"))
        val remote = Plugin("org.scala-sbt", "sbt-remote-cache", "2.0.5")
          .excluding(ZipxExclude.org("org.scala-sbt"))
        val libEx =
          coursier.excludes
            .map(e => s"${e.organization}${e.artifact.fold("")(a => " / " + a)}")
            .mkString(", ")
        s"lib: $libEx\n${ZipxCatalog.renderPluginLine(remote)}"
      }.assert(text =>
        assertTrue(
          text.contains("lib: org.scala-lang.modules"),
          text.contains("excludeAll"),
          text.contains("""ExclusionRule(organization = "org.scala-sbt")"""),
          !text.contains("coursier-cache"),
        )
      ),
    ),
    section("The trait is the extension point")(
      md"""
`ZipxVersions` is a trait, not a closed object zipx owns. Your build extends it. Another plugin that sits on zipx
(splice, a company catalog, CDN pins) extends it too. One object under `project/`, one `.settings` call.

How that plugin's libraries become catalog rows (`AsCoords`, a bundle type, `inline override def settings`), and how it
emits its own `addSbtPlugin` line, is **Extending Versions**. Consumers stay on this page.
"""
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
| Regex / search-replace across the build | Rewrite `Lib` / `Plugin` / `Pin` constructors only |
| A `coords` / `libs` list to keep in sync with the vals | `AsCoords` / `AsPins` / `AsActions` on each val; `Lib` / `Plugin` share one given |
| Versions copied into `plugins.sbt` by hand | Generate writes `plugins.sbt` and `build.properties` |
| A raw `%` coordinate is invisible | `zipxCheckDeps` fails generate if `libraryDependencies` is not a `Lib` row |
| Each `"zio-test"` line is another string | `.mod("zio-test")` shares the parent version literal |

Bazel locks a *resolved* graph well (`maven_install.json` and friends). The coordinates you type are still strings, and
bumping them is still editing strings or regenerating JSON. zipx's catalog is the same `Lib` / `Plugin` values
`libraryDependencies` selects, plus `Pin` vals for non-Maven pins. That is the bump path Scala has been missing: typed,
one file, mechanically applied, checked.
"""
    ),
    section("Generated plugins.sbt")(
      md"""
`zipxCatalogGenerate` and `zipxWorkflowGenerate` write `project/plugins.sbt` (catalog generate does not write workflow
YAML). The file has three layers:

1. Loaded sbt-zipx (`zipxEmitSelf`, default true). This repo sets `zipxEmitSelf := false` because dogfood loads zipx
   from source. The [`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo) consumer
   also sets it false: CI injects the in-dev plugin via `-Dzipx.version` in `project/zipx.sbt`.
2. Other loaded plugins that emit themselves (`zipxSelfPlugins`). You do **not** copy their version into `ZipxVersions`.
   Plugin authors: **Extending Versions**.
3. Your `Plugin` vals (scalafmt, native-packager, anything that does not emit itself).

If you also write `Plugin("com.acme", "sbt-acme", "…")` in the catalog for a plugin that already emits itself, generate
drops that catalog line and keeps the loaded version.
""",
      exampleValue {
        val acme = Plugin("com.acme", "sbt-acme", "9.9.9")
        ZipxCatalog.renderPlugins(List(scalafmt), self = List(zipx, acme))
      }.assert(text =>
        assertTrue(
          text.startsWith(ZipxCatalog.PluginsHeader),
          text.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
          text.contains("""addSbtPlugin("com.acme" % "sbt-acme" % "9.9.9")"""),
          text.contains("""addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
          text.indexOf("sbt-zipx") < text.indexOf("sbt-acme"),
          text.indexOf("sbt-acme") < text.indexOf("sbt-scalafmt"),
        )
      ),
    ),
    section("Catalog update")(
      md"""
The scheduled companion `.github/workflows/zipx-version-updates.yml` (`zipxVersionUpdates`, default true) applies
`zipxDepUpdate yes`, `zipxActionUpdate yes`, and `zipxPinUpdate yes`, then `zipxCatalogGenerate` (not workflow YAML),
and opens `zipx/version-updates-${'$'}GITHUB_RUN_ID` (labeled `clean`). That PR is these constructor hunks. If
`zipxWorkflowCheck` fails on `ci.yml`, the PR body names that branch and the `sbt zipxWorkflowGenerate` / `git` commands
to push workflow YAML onto it (the bot cannot). You can run the same apply locally:

```text
sbt zipxDepUpdate             # list, then prompt Apply N catalog update(s)? [y/N]
sbt "zipxDepUpdate yes"       # rewrite constructors in zipxVersionsFile
sbt "zipxDepUpdate dry-run"

sbt zipxActionUpdate
sbt "zipxActionUpdate yes"
sbt "zipxActionUpdate dry-run"
```

Lookup is Maven Central metadata (then the sbt plugin repo) for `Lib` / `Plugin`. Pre-releases are skipped unless
`zipxPreRelease := PreRelease.Include`. Actions use the GitHub API plus a SHA peel. `yes` applies **every** listed bump.
With no terminal, a bare command lists and stops.

The catalog file lives under `project/`, so it is part of the build definition. After a local rewrite, `reload` (or a
fresh sbt) before you generate. The scheduled job starts a new sbt for `zipxCatalogGenerate`, so it does not need
`reload`. If a `Plugin`, `zipxSbt`, `zipxScala`, or Action version moved, catalog generate rewrites `plugins.sbt` /
`build.properties` / composites / `zipx-ci.env`. It does not rewrite `.github/workflows/` (`GITHUB_TOKEN` cannot push
those files). Use `zipxWorkflowGenerate` when `ci.yml` itself must move. See **Dependency updates**.

Apply rewrites `Lib("g", "a", "from")` / `Plugin("g", "a", "from")` and
`Action("owner/repo", "from", sha = "…")` so version and git SHA move together. `.mod` copies share the parent version
literal. The PR is that catalog file:
""",
      example {
        catalogDepPrDiff
      }.assert(_ =>
        val src   = """val zio = Lib("dev.zio", "zio", "2.1.26")
val test = zio.mod("zio-test").test
val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
"""
        val bumps = List(
          DepBump(zio, BumpKind.Patch, "2.1.27"),
          DepBump(scalafmt, BumpKind.Patch, "2.6.3"),
        )
        val text = ZipxCatalog.applyBumps(src, bumps).yaml
        assertTrue(
          text.contains("""Lib("dev.zio", "zio", "2.1.27")"""),
          text.contains("""zio.mod("zio-test")"""),
          text.contains("""Plugin("org.scalameta", "sbt-scalafmt", "2.6.3")"""),
          !text.contains("2.1.26"),
          !text.contains("2.6.2"),
        )
      ),
      example {
        catalogActionPrDiff
      }.assert(_ =>
        val src =
          """val checkout = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
"""
        val action = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
        val bump   = ActionBump(action, BumpKind.Minor, "v8.0.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val text   = ZipxCatalog.applyActionBumps(src, List(bump)).yaml
        assertTrue(
          text.contains("""Action("actions/checkout", "v8.0.0", sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")"""),
          !text.contains("v7.0.1"),
        )
      ),
    ),
  )

  private def catalogDepPrDiff =
    DocDiff.panel("project/ZipxVersions.scala")(
      DocDiff.line(DocDiff.Kind.Meta, "@@ object MyVersions extends ZipxVersions"),
      DocDiff.line(DocDiff.Kind.Del, """  val zio      = Lib("dev.zio", "zio", "2.1.26")"""),
      DocDiff.line(DocDiff.Kind.Add, """  val zio      = Lib("dev.zio", "zio", "2.1.27")"""),
      DocDiff.line(DocDiff.Kind.Ctx, """  val zioTest  = zio.mod("zio-test").test"""),
      DocDiff.line(DocDiff.Kind.Del, """  val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")"""),
      DocDiff.line(DocDiff.Kind.Add, """  val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.3")"""),
    )

  private def catalogActionPrDiff =
    DocDiff.panel("project/ZipxVersions.scala")(
      DocDiff.line(DocDiff.Kind.Meta, "@@ object MyVersions extends ZipxVersions"),
      DocDiff.line(
        DocDiff.Kind.Del,
        """  val checkout = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")""",
      ),
      DocDiff.line(
        DocDiff.Kind.Add,
        """  val checkout = Action("actions/checkout", "v8.0.0", sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")""",
      ),
    )
end Versions
