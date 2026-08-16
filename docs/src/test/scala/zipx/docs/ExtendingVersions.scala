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
Skip unless you are writing an **sbt plugin that sits on zipx** (splice, a company catalog, CDN pins). Consumers stay
on **Versions**: they extend `ZipxVersions`, drop `MyVersions.settings`, and write `Lib` / `Plugin` vals.

Two jobs. They are not the same hook.

1. **Emit your plugin line** into generated `project/plugins.sbt` from the version on the classpath (`zipxSelfPlugins` /
   `ZipxSelf.emit`). Consumers should not duplicate that GAV in `ZipxVersions`.
2. **Optionally** put your libraries in the consumer catalog (`Lib` vals on a subtype, or `AsCoords` on a bundle). That
   is the Maven-row story below.
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
Collection is a typeclass, `AsCoords`. `Lib` and `Plugin` share one given (`A <: ZipxCoord`). You add a given for your
own type, or you put `Lib` / `Plugin` vals on a `ZipxVersions` subtype. There is no second `coords` list for the
consumer to keep in sync. Do not put *your* plugin GAV on that subtype if you already emit it; that catalog line is
dropped.
"""
    ),
    section("Two paths")(
      md"""
**Path 1: `Lib` / `Plugin` vals on your subtype.** Inherited fields are collected. The consumer writes
`object MyVersions extends SpliceVersions` and your vals are rows.

```scala
trait SpliceVersions extends ZipxVersions:
  val spliceRuntime = Lib("rocks.earlyeffect", "splice-core", "1.0.0")
```

**Path 2: a bundle type with `given AsCoords[YourType]`.** Put the given on the companion so the catalog file does not
import extra machinery. Collection summons it the same way it summons `Lib`.

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

Keep `Lib("g", "a", "from")` / `Plugin("g", "a", "from")` constructors in `project/ZipxVersions.scala`. `zipxDepUpdate`
rewrites those literals; it does not know about `SpliceLibs("1.0.0")`.
"""
    ),
    section("What collection sees")(
      md"""
`coordsOf` walks vals on the concrete object (`this.type`). A `def` is skipped. `SbtVersion` / `ScalaVersion` have no
given. A parent-trait `Lib` val and a bundle val both become rows:
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
    section("settings stays inline")(
      md"""
`ZipxVersions.settings` is `inline` so `coordsOf` expands against the consumer object, not the trait. If you add
settings, keep the override inline:

```scala
trait SpliceVersions extends ZipxVersions:
  val splice = SpliceLibs(...)
  inline override def settings =
    super.settings ++ spliceSettings
```

The consumer still writes one line in `build.sbt`: `MyVersions.settings`.
"""
    ),
    section("Classpath and package")(
      md"""
The consumer compiles `project/ZipxVersions.scala` with your plugin and sbt-zipx on the meta classpath. Put the trait,
the bundle, and the given in a package they can `import` (`import splice.*`, plus `import zipx.*` for `ZipxVersions` /
`AsCoords`). The given on the bundle companion is found without a further import.

Do not name a package `sbt`. On sbt 2 that shadows `_root_.sbt` and the plugin will not compile. zipx keeps catalog
types in `package zipx` for the same reason.
"""
    ),
    section("Apply still rewrites constructors")(
      md"""
Flattening into `zipxVersions` is what check and `zipxDepUpdate` lookup see. Apply is still a constructor rewrite in
the catalog source. A bundle that stores only a version string will list as stale and then not rewrite.
""",
      exampleValue {
        val src   = """val acme = AcmeBundle(
  Lib("com.acme", "acme-core", "1.2.3"),
  Plugin("com.acme", "sbt-acme", "1.2.3"),
)
"""
        val bumps = List(
          DepBump(Lib("com.acme", "acme-core", "1.2.3"), BumpKind.Patch, "1.2.4")
        )
        ZipxCatalog.applyBumps(src, bumps).yaml
      }.assert(text =>
        assertTrue(
          text.contains("""Lib("com.acme", "acme-core", "1.2.4")"""),
          text.contains("""Plugin("com.acme", "sbt-acme", "1.2.3")"""),
        )
      ),
    ),
  )
end ExtendingVersions
