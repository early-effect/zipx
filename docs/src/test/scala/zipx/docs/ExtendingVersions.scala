package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zio.test.*

/** For sbt plugins that sit on zipx: emit the plugin line, then optionally contribute catalog rows. */
object ExtendingVersions extends DocSpecSuite:

  private final case class AcmeBundle(runtime: Lib, plugin: Plugin)
  private object AcmeBundle:
    given AsCoords[AcmeBundle] with
      def coords(b: AcmeBundle): Seq[ZipxCoord] = Seq(b.runtime, b.plugin)

  private trait AcmeCatalog:
    val fromTrait = Lib("com.acme", "from-trait", "1.0.0")
    val acme      = AcmeBundle(
      Lib("com.acme", "acme-core", "1.2.3"),
      Plugin("com.acme", "sbt-acme", "1.2.3"),
    )
    inline def coords: Seq[ZipxCoord] = ZipxCatalog.coordsOf[this.type](this)

  private object Sample extends AcmeCatalog

  def doc = page("Extending Versions")(
    md"""
Skip unless you are writing an **sbt plugin that sits on zipx** (a company catalog, CDN or vendor pins; sbt-splice is
one). Consumers stay on **Versions**: they extend `ZipxVersions`, drop `MyVersions.settings`, and write `Lib` /
`Plugin` / `Pin` / `Action` vals. Outbound `Ship` / `ShipGroup` rows are **Independent versions**.

Two jobs. They are not the same hook.

1. **Emit your plugin line** into generated `project/plugins.sbt` from the version on the classpath (`zipxSelfPlugins` /
   `ZipxSelf.emit`). Consumers should not duplicate that GAV in `ZipxVersions`.
2. **Optionally** put your libraries, pins, and Actions in the consumer catalog (`Lib` / `Pin` / `Action` vals on a
   subtype, or `AsCoords` / `AsPins` / `AsActions` on a bundle). That is the row story below. A plugin that ships a
   `PinFeed` (lookup plus optional `materialize`) does **not** ship the inventory: the consumer repo owns which version
   is pinned.
""",
    section("Emit your plugin line")(
      md"""
`zipxEmitSelf` / `zipxPluginVersion` are sbt-zipx only (dogfood, scripted). Every other plugin that sits on zipx appends
`zipxSelfPlugins`. A `Plugin` val on `ZipxVersions` is for consumer-owned plugins that do not emit themselves
(scalafmt, native-packager).

Group and artifact are always written out. zipx never scans the session. Missing `Implementation-Version` is a `zipx:`
error naming that GAV. Duplicate group+artifact in the self list fails generate. `ZipxSelf.emit` does not read zipx's
`-Dplugin.version`.

```scala
object SpliceZipxPlugin extends AutoPlugin:
  override def requires = ZipxPlugin
  override def buildSettings = Seq(
    zipxSelfPlugins += ZipxSelf.emit("rocks.earlyeffect", "sbt-splice", getClass)
  )
```

Generate writes zipx first, then your line, then the consumer's catalog plugins:
""",
      exampleValue {
        val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
        val splice   = Plugin("rocks.earlyeffect", "sbt-splice", "1.0.0")
        val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
        ZipxCatalog.renderPlugins(List(scalafmt), self = List(zipx, splice))
      }.assert(text =>
        assertTrue(
          text.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
          text.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-splice" % "1.0.0")"""),
          text.contains("""addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
          text.indexOf("sbt-zipx") < text.indexOf("sbt-splice"),
          text.indexOf("sbt-splice") < text.indexOf("sbt-scalafmt"),
        )
      ),
    ),
    section("Your libraries in that same catalog")(
      md"""
Collection is a typeclass, `AsCoords` / `AsPins` / `AsActions` / `AsShips`. `Lib` and `Plugin` share `AsCoords`
(`A <: ZipxCoord`). `Pin` has `AsPins`. `Action` has `AsActions`. `Ship` / `ShipGroup` have `AsShips`. You add a given
for your own type, or you put `Lib` / `Plugin` / `Pin` / `Action` / `Ship` vals on a `ZipxVersions` subtype. There is no
second `coords` list for the consumer to keep in sync. Do not put *your* plugin GAV on that subtype if you already emit
it; that catalog line is dropped.
"""
    ),
    section("Two paths")(
      md"""
**Path 1: `Lib` / `Plugin` / `Pin` / `Action` vals on your subtype.** Inherited fields are collected. The consumer writes
`object MyVersions extends SpliceVersions` and your vals are rows.

```scala
trait SpliceVersions extends ZipxVersions:
  val spliceRuntime = Lib("rocks.earlyeffect", "splice-core", "1.0.0")
  val preact        = Pin("cdn", "preact", "10.26.4", sha256 = "sha256-abc", purl = "pkg:npm/preact@10.26.4")
  // extra settings: MyVersions.settings ++ spliceCdnFeed in build.sbt
```

The consumer still owns those `Pin` vals (or copies them). You ship the feed, not a second inventory list.

**Path 2: a bundle type with `given AsCoords[YourType]`.** Put the given on the companion so the catalog file does not
import extra machinery. Collection summons it the same way it summons `Lib`. CDN bundles use `given AsPins` the same
way; Action bundles use `given AsActions`.

```scala
final case class SpliceLibs(runtime: Lib)
object SpliceLibs:
  given AsCoords[SpliceLibs] with
    def coords(s: SpliceLibs) = Seq(s.runtime)

trait SpliceVersions extends ZipxVersions:
  val splice = SpliceLibs(
    Lib("rocks.earlyeffect", "splice-core", "1.0.0")
  )
```

Do **not** add `given AsCoords[List[Lib]]`. That would collect every helper list on the consumer object. Own a named
type.

Keep `Lib("g", "a", "from")` / `Plugin("g", "a", "from")` / `Pin("feed", "id", "from", …)` /
`Action("owner/repo", "from", sha = …)` constructors in `project/ZipxVersions.scala`. `zipxDepUpdate`, `zipxPinUpdate`,
and `zipxActionUpdate` rewrite those literals; they do not know about `SpliceLibs("1.0.0")`. Maven, pin, and self-emit
hunks are below.
"""
    ),
    section("What collection sees")(
      md"""
`coordsOf` / `pinsOf` walk vals on the concrete object (`this.type`). A `def` is skipped. `SbtVersion` / `ScalaVersion`
have no given. A parent-trait `Lib` val and a bundle val both become rows:
""",
      exampleValue {
        Sample.coords.map(c => s"${c.group}:${c.artifact}").sorted.mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("com.acme:from-trait"),
          text.contains("com.acme:acme-core"),
          text.contains("com.acme:sbt-acme"),
        )
      ),
      md"""
`zipxCheckDeps` compares `libraryDependencies` to the flattened `Lib` rows. A GAV that came out of your bundle is a
catalog hit, same as a bare `val zio = Lib(...)`.
""",
      exampleValue {
        val extra = ZipxCatalog.extraLibs(
          List(DeclaredGav("com.acme", "acme-core", "1.2.3")),
          Sample.coords,
        )
        if extra.isEmpty then "(none)" else extra.map(_.render).mkString("\n")
      }.assert(text => assertTrue(text == "(none)")),
    ),
    section("settings stays on the plugin")(
      md"""
`coords` / `pins` / `actions` live on the core trait so a process that is not the target sbt can compile the catalog
file. `settings` is an inline extension on the plugin (`MyVersions.settings` in `build.sbt`). Extra settings belong next
to that call (`MyVersions.settings ++ spliceSettings`), not as `inline override def settings` on a subtype.

The consumer still writes one line in `build.sbt`: `MyVersions.settings`.
"""
    ),
    section("Classpath and package")(
      md"""
The consumer compiles `project/ZipxVersions.scala` with your plugin and sbt-zipx on the meta classpath. Put the trait,
the bundle, and the given in a package they can `import` (`import splice.*`, plus `import zipx.*` for `ZipxVersions` /
`AsCoords` / `AsPins` / `AsActions` / `Pin` / `Action`). The given on the bundle companion is found without a further
import.

Do not name a package `sbt`. On sbt 2 that shadows `_root_.sbt` and the plugin will not compile. zipx keeps catalog
types in `package zipx` for the same reason.
"""
    ),
    section("Apply still rewrites constructors")(
      md"""
Flattening into `zipxVersions` / `zipxPins` is what check and lookup see. Apply is still a constructor rewrite in the
catalog source. Nested `Lib(...)` / `Plugin(...)` / `Pin(...)` literals inside a bundle are what move. A bundle that
stores only a version string will list as stale and then not rewrite.

A Path 1 `Lib` / `Plugin` val on your subtype looks like **Versions**. Path 2 is the same rewrite, with your type
around the constructor. Sibling constructors in that bundle stay put. Pins are the next section.
""",
      example {
        bundleCatalogPrDiff
      }.assert(_ =>
        val src   = """val acme = AcmeBundle(
  Lib("com.acme", "acme-core", "1.2.3"),
  Plugin("com.acme", "sbt-acme", "1.2.3"),
)
"""
        val bumps = List(
          DepBump(Lib("com.acme", "acme-core", "1.2.3"), BumpKind.Patch, "1.2.4")
        )
        val text = ZipxCatalog.applyBumps(src, bumps).yaml
        assertTrue(
          text.contains("""Lib("com.acme", "acme-core", "1.2.4")"""),
          text.contains("""Plugin("com.acme", "sbt-acme", "1.2.3")"""),
        )
      ),
    ),
    section("Custom pins")(
      md"""
You ship the `PinFeed` (lookup, policy, optional `materialize`). The consumer writes the `Pin` vals, on your
`ZipxVersions` subtype or inside an `AsPins` bundle. Apply still rewrites those `Pin(...)` constructors: version,
sha256, and purl together. Maven rows stay put. Your own `addSbtPlugin` line stays put.

A `given AsPins` bundle is the same rewrite: the `Pin(...)` literal inside the case class, not `CdnPins("1.2.4")`.

If `materialize` writes a vendored file, that second path is in the same PR. zipx does not regex that file; your feed
wrote it. Policy and local `zipxPinUpdate` are **Pin feeds**.
""",
      example {
        DocDiff.stack(customPinCatalogDiff, customPinVendorDiff)
      }.assert(_ =>
        val src   = """val widget = Pin("cdn", "widget", "1.2.3", sha256 = "abc", purl = "pkg:npm/widget@1.2.3")
val acme   = Lib("com.acme", "acme-core", "1.2.3")
"""
        val pin   = Pin("cdn", "widget", "1.2.3", sha256 = "abc", purl = "pkg:npm/widget@1.2.3")
        val bumps = List(
          PinBump(pin, BumpKind.Patch, PinCandidate("1.2.4", Some("def"), Some(Purl("pkg:npm/widget@1.2.4"))))
        )
        val text = ZipxCatalog.applyPinBumps(src, bumps).yaml
        assertTrue(
          text.contains("""Pin("cdn", "widget", "1.2.4", sha256 = "def", purl = "pkg:npm/widget@1.2.4")"""),
          text.contains("""Lib("com.acme", "acme-core", "1.2.3")"""),
          !text.contains("pkg:npm/widget@1.2.3"),
        )
      ),
    ),
    section("Your plugin line is a different file")(
      md"""
`ZipxSelf.emit` writes *your* GAV into generated `project/plugins.sbt` from the JAR on the classpath. That version is
not a `Plugin` val. `zipxDepUpdate` will not rewrite it. When you publish `sbt-splice` 1.1.0, the consumer loads that
plugin and generate rewrites the `addSbtPlugin` line. `project/ZipxVersions.scala` does not change.

If they also wrote `Plugin("rocks.earlyeffect", "sbt-splice", …)` on `ZipxVersions`, generate drops that catalog line
and keeps the loaded version. The PR they open is still `plugins.sbt`:
""",
      example {
        selfEmitPrDiff
      }.assert(_ =>
        val zipx     = Plugin("rocks.earlyeffect", "sbt-zipx", "0.5.1")
        val scalafmt = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
        val before   = ZipxCatalog.renderPlugins(
          List(scalafmt),
          self = List(zipx, Plugin("rocks.earlyeffect", "sbt-splice", "1.0.0")),
        )
        val after = ZipxCatalog.renderPlugins(
          List(scalafmt),
          self = List(zipx, Plugin("rocks.earlyeffect", "sbt-splice", "1.1.0")),
        )
        assertTrue(
          before.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-splice" % "1.0.0")"""),
          after.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-splice" % "1.1.0")"""),
          after.contains("""addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
          after.contains("""addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
          !after.contains("""% "sbt-splice" % "1.0.0""""),
        )
      ),
    ),
    section("Nested example on the catalog PR")(
      md"""
If this plugin repo has a nested consumer example whose generated `ci.yml` must track Action peels, set
`zipxVersionUpdatesExtraSteps`: `publishLocal` the in-dev plugin, then `zipxWorkflowGenerate` in that tree. Nested
`.github/workflows/` is not repo-root, so the bot can commit it. Root `ci.yml` still needs a human generate. Full
recipe: **Dependency updates**.
"""
    ),
  )

  private def bundleCatalogPrDiff =
    DocDiff.panel("project/ZipxVersions.scala")(
      DocDiff.line(DocDiff.Kind.Meta, "@@ object MyVersions extends AcmeVersions"),
      DocDiff.line(DocDiff.Kind.Ctx, "  val acme = AcmeBundle("),
      DocDiff.line(DocDiff.Kind.Del, """    Lib("com.acme", "acme-core", "1.2.3"),"""),
      DocDiff.line(DocDiff.Kind.Add, """    Lib("com.acme", "acme-core", "1.2.4"),"""),
      DocDiff.line(DocDiff.Kind.Ctx, """    Plugin("com.acme", "sbt-acme", "1.2.3"),"""),
      DocDiff.line(DocDiff.Kind.Ctx, "  )"),
    )

  private def customPinCatalogDiff =
    DocDiff.panel("project/ZipxVersions.scala")(
      DocDiff.line(DocDiff.Kind.Meta, "@@ object MyVersions extends CdnVersions"),
      DocDiff.line(DocDiff.Kind.Ctx, "  val widget = Pin("),
      DocDiff.line(DocDiff.Kind.Ctx, "    \"cdn\","),
      DocDiff.line(DocDiff.Kind.Ctx, "    \"widget\","),
      DocDiff.line(DocDiff.Kind.Del, "    \"1.2.3\","),
      DocDiff.line(DocDiff.Kind.Add, "    \"1.2.4\","),
      DocDiff.line(DocDiff.Kind.Del, "    sha256 = \"abc\","),
      DocDiff.line(DocDiff.Kind.Add, "    sha256 = \"def\","),
      DocDiff.line(DocDiff.Kind.Del, "    purl = \"pkg:npm/widget@1.2.3\","),
      DocDiff.line(DocDiff.Kind.Add, "    purl = \"pkg:npm/widget@1.2.4\","),
      DocDiff.line(DocDiff.Kind.Ctx, "  )"),
      DocDiff.line(DocDiff.Kind.Ctx, """  val acme   = Lib("com.acme", "acme-core", "1.2.3")"""),
    )

  private def customPinVendorDiff =
    DocDiff.panel("vendor/widget.js")(
      DocDiff.line(DocDiff.Kind.Meta, "@@ materialize wrote this file"),
      DocDiff.line(DocDiff.Kind.Del, "/*! widget 1.2.3 */"),
      DocDiff.line(DocDiff.Kind.Add, "/*! widget 1.2.4 */"),
      DocDiff.line(DocDiff.Kind.Ctx, "(function(){ /* vendored bytes */ })();"),
    )

  private def selfEmitPrDiff =
    DocDiff.panel("project/plugins.sbt")(
      DocDiff.line(DocDiff.Kind.Meta, "@@ generated plugins.sbt"),
      DocDiff.line(DocDiff.Kind.Ctx, """addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.5.1")"""),
      DocDiff.line(DocDiff.Kind.Del, """addSbtPlugin("rocks.earlyeffect" % "sbt-splice" % "1.0.0")"""),
      DocDiff.line(DocDiff.Kind.Add, """addSbtPlugin("rocks.earlyeffect" % "sbt-splice" % "1.1.0")"""),
      DocDiff.line(DocDiff.Kind.Ctx, """addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")"""),
    )
end ExtendingVersions
