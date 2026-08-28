package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.config
import zio.test.*

/** Outbound Ship / ShipGroup rows: independent library versions, merge-to-main publish. */
object IndependentVersions extends DocSpecSuite:

  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("models"), publishes = true, crossScalaVersions = List("3.8.4"), baseDir = "models"),
      ModuleNode(
        ModuleId("coreLib"),
        dependsOn = List("models"),
        publishes = true,
        crossScalaVersions = List("3.8.4"),
        baseDir = "core-lib",
      ),
      ModuleNode(
        ModuleId("client"),
        dependsOn = List("coreLib"),
        publishes = true,
        crossScalaVersions = List("3.8.4"),
        baseDir = "client",
      ),
      ModuleNode(
        ModuleId("service"),
        dependsOn = List("coreLib"),
        docker = true,
        publishes = false,
        crossScalaVersions = List("3.8.4"),
        baseDir = "service",
      ),
    )
  )

  private val independent = config.copy(modverPublish = true)

  private val publishCmd = SbtCommand.unsafeTask("zipxModverPublishSigned")

  private val ships: List[PublishedRow] =
    List(ShipGroup("libs", "1.4.2")("models", "coreLib"), Ship("client", "0.3.0"))

  def doc = page("Independent versions")(
    md"""
Skip this page if every artifact in the repo ships together on a `v*` tag. That lockstep path is still the default:
`sbt-dynver-ci`, Aggregate `ZipxCentral.release`, `Gate.OnReleaseTag`. zipx itself stays there.

Use `Ship` / `ShipGroup` rows when a monorepo publishes several libraries on different cadences. Presence of any such
val is the feature flag. The human writes the number in the PR. CI suggests a MiMa-informed edit as a sticky comment
and **fails closed** on a missing or undersized bump. Merge to the default branch is the release signal.

```mermaid
flowchart TD
  Catalog[project/ZipxVersions.scala]
  Catalog --> Ships{any Ship or ShipGroup val?}
  Ships -->|no| Dynver[sbt-dynver-ci owns version]
  Dynver --> Tag[Gate.OnReleaseTag]
  Tag --> Agg[Aggregate ZipxCentral.release]
  Ships -->|yes| Row["version := row or row-ci"]
  Row --> Check[modver-suggest + modver-check on PR]
  Check --> Merge[merge to default branch]
  Merge --> Graph[ZipxModver Graph publish of version-moved modules]
  class Catalog,Ships warn
  class Dynver,Tag,Agg,Row,Check,Merge,Graph happy
```

Inbound catalog rows (`Lib` / `Plugin` / `Pin` / `Action`) stay on **Versions**. This page is outbound versions only.
The [`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo) dogfoods a `ShipGroup` plus
an independent `Ship`.
""",
    section("Catalog rows")(
      md"""
```scala
// project/ZipxVersions.scala
object MyVersions extends ZipxVersions:
  val zio    = Lib("dev.zio", "zio", "2.1.26")
  val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
  val client = Ship("client", "0.3.0")
  def libraries = library(zio)
```

`Ship("client", "0.3.0")` is one sbt project (including every `projectMatrix` platform row of that root).
`ShipGroup("libs", "1.4.2")("models", "coreLib")` is several projects that always share one number and one release.
`service` in the example is unpublished, so it has no row.

Drop the repo-wide `version := "…"`. Members take `<row>-ci` locally and the catalog number on publish. Aggregators and
unpublished apps keep sbt's default version.

`zipxDepUpdate` / `catalog update` rewrite `Lib` / `Plugin` / `Action` only. They never touch `Ship` / `ShipGroup`.
""",
      exampleValue {
        ships
          .map(r => s"${r.label} ${r.identity} ${r.version} members=${r.memberRoots.mkString(",")}")
          .mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("ShipGroup libs 1.4.2 members=models,coreLib"),
          text.contains("Ship client 0.3.0 members=client"),
        )
      ),
    ),
    section("Library vs image")(
      md"""
| What | Signal | Pack |
|---|---|---|
| Library coordinates | merge to `main` when a `Ship` / `ShipGroup` row moved | `ZipxModver.publish` |
| Docker image / deploy | a **human** `v*` tag (docs/docker only, not the library version) | `ZipxAws.dockerPublishAll`, deploy |

Nothing in independent mode creates `v*` tags. A library-only release does not push an image. Do not retarget docker to
`Gate.OnDefaultPush`. Keep docker and deploy in the build so they still teach AWS wiring; the comment is the contract.

```scala
zipxCapabilities ++= Seq(
  Capability.testLayers,
  ZipxModver.publish(),
)
zipxCapabilities += ZipxAws.dockerPublishAll(Registry.destinations) // still OnReleaseTag
```
""",
      exampleValue {
        DocsRender.jobs("publish-client", "docker")(
          ZipxModver.publish(publishCmd),
          Capability.docker,
        )(using graph, independent)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("publish-client:"),
          yaml.contains("workflow_dispatch"),
          yaml.contains("needs.modver.outputs.modules"),
          yaml.contains("docker:"),
          yaml.contains("refs/tags/v"),
          yaml.contains("service/Docker/publish"),
        )
      ),
    ),
    section("ZipxModver.publish")(
      md"""
Replace builtin Aggregate `publish` (or `Capability.publishLayers`) with `ZipxModver.publish`. Graph, `Gate.OnDefaultPush`
(push to `zipxPushBranches` **or** `workflow_dispatch`), `MatrixCollapse.Off`. Default command is
`zipxModverPublishSigned`. No Central secrets unless you compose them.

```scala
zipxCapabilities += ZipxModver.publish()

// Optional: Central sonaRelease once after Graph publish
zipxCapabilities += ZipxCentral.releaseOnce.copy(gate = Gate.OnDefaultPush)
```

Generate refuses Aggregate / Layer / `OnReleaseTag` **library** publish when ships are present. Docker is not that
refusal. The synthetic `modver` job writes the compact module-id array; Graph publish jobs `contains(...)` that array
with no `'all'` sentinel, and skip-tolerate a skipped ancestor `publish-*`. `modver-check` / `modver-suggest` are injected
Once jobs on `pull_request`. See **Packs** and **Job conditions**.
""",
      exampleValue {
        DocsRender.jobs("modver", "publish-client", "modver-check")(
          ZipxModver.publish(publishCmd),
          Capability.modverCheck(),
        )(using graph, independent)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("modver:"),
          yaml.contains("zipxModverPublishModules"),
          yaml.contains("publish-client:"),
          yaml.contains("contains(fromJson(needs.modver.outputs.modules), 'client')"),
          !yaml.contains("'all'"),
          yaml.contains("modver-check:"),
          yaml.contains("zipxModverCheck"),
          yaml.contains("pull_request"),
        )
      ),
      exampleValue {
        scala.util
          .Try(DocsRender.plan(Capability.publish)(using graph, independent))
          .fold(_.getMessage, _ => "planned (no error)")
      }.assert(err =>
        assertTrue(
          err.contains("Ship rows require Graph publish"),
          err.contains("ZipxModver.publish"),
        )
      ),
    ),
    section("Cache epoch")(
      md"""
Independent mode does not force `GitTags()`. Set `zipxCacheEpoch := CacheEpoch.ShipCatalog` so LocalDir keys off sorted
Ship identity and version (baked at generate, same path as `Fixed`). One row bump rolls the **repo-wide** LocalDir
namespace. Remote `cacheVersion` stays JDK/OS only; a bump already changes that module's `version`, so only that
module's remote entries miss. Full guide: **Caching**.

```scala
zipxCacheEpoch := CacheEpoch.ShipCatalog
```
""",
      exampleValue {
        val hash = Modver.epochHash(ships)
        val yaml = DocsRender.job("test")(Capability.test)(using
          graph,
          independent.copy(cacheEpoch = CacheEpoch.ShipCatalog, shipEpochHash = Some(hash)),
        )
        s"hash: $hash\n$yaml"
      }.assert(text =>
        val hash = Modver.epochHash(ships)
        assertTrue(
          text.contains(s"hash: $hash"),
          text.contains(s"cache-epoch: \"$hash\"") || text.contains(s"cache-epoch: $hash"),
        )
      ),
    ),
  )
end IndependentVersions
