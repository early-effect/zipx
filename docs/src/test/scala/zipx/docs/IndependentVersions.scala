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

  private val libsRow   = ShipGroup("libs", "1.4.2")("models", "coreLib")
  private val clientRow = Ship("client", "0.3.0")
  private val ships     = List[PublishedRow](libsRow, clientRow)
  private val index     = ShipIndex.from(ships)

  def doc = page("Independent versions")(
    md"""
Skip this page if every artifact in the repo ships together on a `v*` tag. That lockstep path is still the default:
`sbt-dynver-ci`, Aggregate `ZipxCentral.release`, `Gate.OnReleaseTag`. zipx itself stays there.

Use `Ship` / `ShipGroup` rows when a monorepo publishes several libraries on different cadences. Presence of any such
val is the feature flag. The human writes the number in the PR. CI suggests a MiMa-informed edit as a sticky comment
and **fails closed** on a missing or undersized bump. Merge to the default branch is the release signal. Graph publish
uploads only modules whose row moved, and skips a GAV already on the registry.

```mermaid
flowchart TD
  Catalog[ZipxVersions catalog]
  Catalog --> Ships{Ship rows?}
  Ships -->|no| Dynver[dynver-ci]
  Dynver --> Tag[v* tag]
  Tag --> Agg[ZipxCentral.release]
  Ships -->|yes| Row[version from the row]
  Row --> Check[suggest + check on PR]
  Check --> Merge[merge to main]
  Merge --> Graph[ZipxModver publish]
  class Catalog,Ships warn
  class Dynver,Tag,Agg,Row,Check,Merge,Graph happy
```

Inbound catalog rows (`Lib` / `Plugin` / `Pin` / `Action`) stay on **Versions**. This page is outbound versions only.
The [`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo) dogfoods a `ShipGroup` plus
an independent `Ship`.
""",
    section("The everyday loop")(
      md"""
A source change and a version change are different commits' jobs. CI never writes `ZipxVersions.scala` for you.

```mermaid
flowchart TD
  Push([1 · push PR]) --> Suggest[2 · sticky comment]
  Suggest --> Gate[3 · modver-check]
  Gate -->|undersized| Red([fail closed])
  Gate -->|ok| Human[4 · you write the number]
  Human --> Merge([5 · merge to main])
  Merge --> Modver[6 · modver id array]
  Modver --> Pub[7 · publish those ids]
  class Push,Suggest,Gate,Human,Merge,Modver warn
  class Red sad
  class Pub happy
```

1. Push a PR that changes sources. You may not have edited a `Ship` yet.
2. `modver-suggest` posts a sticky comment with the MiMa-informed constructors (best-effort on forks).
3. `modver-check` fails the PR until the catalog number is at least that floor. Over-bump is fine. Skipping is not.
   Bumping less is not.
4. You write the number (`zipxModverBump client`, or by hand) and push.
5. Merge to the default branch. That is the release signal. Nothing here creates a `v*` tag.
6. The synthetic `modver` job diffs catalog rows against `github.event.before` (or registry-only on
   `workflow_dispatch`) and writes a compact module-id array. No `'all'` sentinel.
7. Graph `publish-*` jobs run only for ids in that array, skip-tolerant of a skipped ancestor, and skip a GAV already
   on the registry.

`modver-check` / `modver-suggest` self-compile (`needsCapabilities = Nil`). They do not wait on test topology.
""",
      exampleValue {
        val row = ModverReportRow(
          identity = "client",
          label = "Ship",
          from = "0.3.0",
          written = "0.3.0",
          suggested = "0.3.1",
          constructor = """Ship("client", "0.3.1")""",
          kind = BumpKind.Patch,
          mimaRan = true,
          status = BumpStatus.Missing,
        )
        ModverComment.body(ModverReport(List(row)), Some("""Ship("client", "0.3.1")"""))
      }.assert(body =>
        assertTrue(
          body.contains(ModverComment.Marker),
          body.contains("zipx module versions"),
          body.contains("`client`"),
          body.contains("0.3.1"),
          body.contains("```suggestion"),
          body.contains("""Ship("client", "0.3.1")"""),
        )
      ),
    ),
    section("The monorepo graph")(
      md"""
Same shape as [`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo): two libraries
that always share a number, one library on its own cadence, one unpublished app that still builds an image.

```mermaid
flowchart TD
  subgraph libs["ShipGroup libs 1.4.2"]
    models[models]
    coreLib[core-lib]
  end
  subgraph alone["Ship client 0.3.0"]
    client[client]
  end
  service[service · unpublished · no row]
  models --> coreLib
  coreLib --> client
  coreLib --> service
  class models,coreLib,client happy
  class service warn
```

```scala
// project/ZipxVersions.scala
object MyVersions extends ZipxVersions:
  val zio    = Lib("dev.zio", "zio", "2.1.26")
  val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
  val client = Ship("client", "0.3.0")
  def libraries = library(zio)
```

| Module | Row | Publishes |
|---|---|---|
| `models` | `ShipGroup libs` | yes |
| `coreLib` | `ShipGroup libs` | yes |
| `client` | `Ship client` | yes |
| `service` | none | no (`publishArtifact := false`) |
| root aggregator | none | no (`publish / skip`) |

`Ship("client", "0.3.0")` is one sbt project, including every `projectMatrix` platform row of that root.
`ShipGroup("libs", "1.4.2")("models", "coreLib")` is several projects that always share one number and one release. A
group of one is legal and pointless (it is just `Ship`). Empty members are refused at generate.
""",
      exampleValue {
        Modver.membership(graph, ships) match
          case Left(err)  => err
          case Right(idx) =>
            List("models", "coreLib", "client", "service")
              .map { id =>
                val mid = ModuleId.unsafeMake(id)
                idx.rowFor(mid).fold(s"$id:none")(r => s"$id:${r.label}:${r.identity}")
              }
              .mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("models:ShipGroup:libs"),
          text.contains("coreLib:ShipGroup:libs"),
          text.contains("client:Ship:client"),
          text.contains("service:none"),
        )
      ),
    ),
    section("Catalog rows")(
      md"""
Drop the repo-wide `version := "…"`. Members take `<row>-ci` locally and the catalog number on publish. Aggregators and
unpublished apps keep sbt's default version.

| Where | Number |
|---|---|
| Catalog constructor | release number only (`1.4.2`, never `1.4.2-ci`) |
| Local / PR checkout | `<row>-ci` (`1.4.2-ci`) |
| Default-branch push that **releases** this row | catalog number |
| POM / `publishLocal` sibling revision | catalog number, never `-ci` |

`zipxDepUpdate` / `catalog update` rewrite `Lib` / `Plugin` / `Action` only. They never touch `Ship` / `ShipGroup`.
Bump outbound rows yourself:

```text
sbt "zipxModverBump client"         # patch, default
sbt "zipxModverBump libs minor"
sbt "zipxModverBump client major"
```

The PR is that constructor hunk:
""",
      example {
        catalogBumpDiff
      }.assert(_ =>
        val zio = Lib("dev.zio", "zio", "2.1.26")
        val src =
          """val zio    = Lib("dev.zio", "zio", "2.1.26")
val client = Ship("client", "0.3.0")
"""
        val text = ZipxCatalog.applyBumps(src, List(DepBump(zio, BumpKind.Patch, "2.1.27"))).fold(identity, identity)
        assertTrue(
          text.contains("""Lib("dev.zio", "zio", "2.1.27")"""),
          text.contains("""Ship("client", "0.3.0")"""),
          Modver.bumpVersion("0.3.0", BumpKind.Patch) == Right("0.3.1"),
          Modver.bumpVersion("0.3.1-ci", BumpKind.Patch).isLeft,
        )
      ),
    ),
    section("Three sets: affected, bump, publish")(
      md"""
**Affected** answers "which Verify jobs can we skip." A version manager answers "which coordinates move, to what, and
when is upload legal." Reusing `affectedModules` for that second question is how you fail-open a publish.

```mermaid
flowchart TD
  Files([changed files]) --> Own[owning published roots]
  Own --> Aff[Affected reverse-dep]
  Aff --> Verify([Verify · fail open])
  Own --> Lift[group lift]
  Lift --> MiMa[MiMa min-bump]
  MiMa --> Prop[propagate]
  Prop --> Bump([bump set · fail closed])
  Diff([Ship row diff]) --> Moved[moved rows]
  Moved --> Members[every member]
  Members --> Gav[skip GAV already published]
  Gav --> Publish([publish set · fail closed])
  class Files,Own,Aff,Verify,Diff warn
  class Lift,MiMa,Prop,Bump,Moved,Members,Gav,Publish happy
```

| Set | Inputs | Rule | Failure |
|---|---|---|---|
| Verify (Affected) | graph, files | reverse-dep of owners; `.sbt` / `project/` => all; diff fail => `["all"]` | fail **open** |
| Bump | graph, ships, files | owners ∩ publishes, **no** reverse-dep, **no** build-file explosion, group lift, then MiMa, then propagate | fail **closed** |
| Publish | ships, before SHA, registry | every member of a row whose version **or membership** changed; job skipped only when every binary is 200 | fail **closed** |

A dirty `models` source file reverse-deps into `coreLib`, `client`, and `service` for **test**. For **bump** it lifts
only the `libs` group. `client` does not have to move. See **Affected**.
""",
      exampleValue {
        def refsOf(files: Option[List[String]]): String =
          Modver.liftedBumpSet(graph, index, files) match
            case Left(err)  => err
            case Right(set) =>
              set
                .map {
                  case ShipRef.Group(n) => s"group:$n"
                  case ShipRef.One(id)  => s"ship:$id"
                }
                .toList
                .sorted
                .mkString(",")
        List(
          s"models src -> ${refsOf(Some(List("models/src/Main.scala")))}",
          s"client src -> ${refsOf(Some(List("client/src/Main.scala")))}",
          s"no diff -> ${refsOf(None)}",
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("models src -> group:libs"),
          text.contains("client src -> ship:client"),
          text.contains("no diff -> "),
          text.contains("refusing to guess the bump set"),
        )
      ),
    ),
    section("Library vs image")(
      md"""
Library publish moves off tags. Docker and deploy do not.

```mermaid
flowchart TD
  Merge([merge to main]) --> Lib[library coordinates]
  Lib --> Registry[(registry)]
  Tag([human v* tag]) --> Img[docker image]
  Img --> Dep[deploy]
  class Merge,Lib,Registry happy
  class Tag,Img,Dep warn
```

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
Replace builtin Aggregate `publish` (or `Capability.publishLayers`) with `ZipxModver.publish`. Graph,
`Gate.OnDefaultPush` (push to `zipxPushBranches` **or** `workflow_dispatch`), `MatrixCollapse.Off`. Default command is
`zipxModverPublishSigned`. No Central secrets unless you compose them.

```mermaid
flowchart TD
  Modver[modver id array] --> Models[publish-models]
  Modver --> Core[publish-coreLib]
  Modver --> Client[publish-client]
  Models -.->|skip-tolerant| Core
  Core -.->|skip-tolerant| Client
  class Modver,Models,Core,Client happy
```

```scala
zipxCapabilities += ZipxModver.publish()

// Optional: Central sonaRelease once after Graph publish
zipxCapabilities += ZipxCentral.releaseOnce.copy(gate = Gate.OnDefaultPush)
```

Generate refuses Aggregate / Layer / `OnReleaseTag` **library** publish when ships are present. Docker is not that
refusal. Graph `if:` is `contains(fromJson(needs.modver.outputs.modules), '<id>')` with **no** `'all'` sentinel. A
skipped `publish-coreLib` does not skip `publish-client`. `workflow_dispatch` runs `modver` in registry-only mode
(every catalog GAV not on the registry). `gh run rerun` of the merge SHA is the other recovery path. See **Packs** and
**Job conditions**.
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
          yaml.contains("needs.publish-coreLib.result != 'failure'"),
          !yaml.contains("'all'"),
          yaml.contains("modver-check:"),
          yaml.contains("zipxModverCheck"),
          yaml.contains("pull_request"),
          yaml.contains("workflow_dispatch"),
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
    section("Propagate")(
      md"""
Default is `Never`: only dirty published roots, lifted through groups. Built-ins walk **published reverse-deps across
groups** after MiMa kinds exist. Intra-group `dependsOn` (models → coreLib inside `libs`) is not a propagate edge.

```mermaid
flowchart LR
  Models[models] --> CoreLib[coreLib]
  CoreLib --> Client[client]
  subgraph libs["ShipGroup libs"]
    Models
    CoreLib
  end
  subgraph alone["Ship client"]
    Client
  end
  CoreLib -.->|propagate edge · Never ignores it| Client
  class Models,CoreLib,Client happy
```

```scala
zipxModverPropagate := ModverPropagate.Never            // default
zipxModverPropagate := ModverPropagate.PatchPublished   // patch published reverse-deps
zipxModverPropagate := ModverPropagate.MatchBump        // at least the triggering kind
zipxModverPropagate := ModverPropagate.custom { (kinds, graph, ships) => kinds }
```

| Policy | What a dirty `libs` does to `client` |
|---|---|
| `Never` | nothing |
| `PatchPublished` | patch, if `client` publishes |
| `MatchBump` | at least the `libs` kind (a binary break floors `client` at major too) |
""",
      exampleValue {
        val kinds                    = BumpSet(Map(ShipRef.Group(libsRow.name) -> BumpKind.Minor))
        val never                    = Modver.expand(kinds, graph, index, ModverPropagate.Never)
        val patch                    = Modver.expand(kinds, graph, index, ModverPropagate.PatchPublished)
        val matchB                   = Modver.expand(kinds, graph, index, ModverPropagate.MatchBump)
        def show(b: BumpSet): String =
          b.asMap.toList
            .sortBy(_._1.toString)
            .map {
              case (ShipRef.Group(n), k) => s"group:$n=$k"
              case (ShipRef.One(id), k)  => s"ship:$id=$k"
            }
            .mkString(",")
        List(s"Never ${show(never)}", s"PatchPublished ${show(patch)}", s"MatchBump ${show(matchB)}").mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("Never group:libs=Minor"),
          !text.split("\n").head.contains("ship:client"),
          text.contains("PatchPublished") && text.contains("ship:client=Patch"),
          text.contains("MatchBump") && text.contains("ship:client=Minor"),
        )
      ),
    ),
    section("Cache epoch")(
      md"""
Independent mode does not force `GitTags()`. Set `zipxCacheEpoch := CacheEpoch.ShipCatalog` so LocalDir keys off sorted
Ship identity and version (baked at generate, same path as `Fixed`).

```mermaid
flowchart TD
  Bump([Ship bump]) --> Local[LocalDir · whole-repo epoch]
  Bump --> Remote[remote · that module only]
  class Bump warn
  class Local,Remote happy
```

One row bump rolls the **repo-wide** LocalDir namespace, the same way a `v*` tag does under `GitTags()`. Remote
`cacheVersion` stays JDK/OS only; a bump already changes that module's `version`, so only that module's remote entries
miss. Lockstep OSS keeps `GitTags()`. Full guide: **Caching**.

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
    section("Matrix root")(
      md"""
`Ship` identity is the matrix root. One `Ship("core")` covers `core` and `coreJS`. `Ship("coreJS")` is refused at
generate with a hint to use the root. Other axes set `zipxMatrixRoot` or generate fails.

```mermaid
flowchart LR
  Ship["Ship core 1.4.2"] --> JVM[core]
  Ship --> JS[coreJS]
  class Ship,JVM,JS happy
```
""",
      exampleValue {
        val matrix = GraphFixture(
          List(
            ModuleNode(ModuleId("core"), publishes = true, baseDir = "core"),
            ModuleNode(
              ModuleId("coreJS"),
              publishes = true,
              baseDir = "core",
              matrixRootOpt = Some(ModuleId("core")),
            ),
          )
        )
        val covered = Modver.rowForProject("coreJS", List(Ship("core", "1.4.2"))).map(r => r.identity: String)
        val bad     = Modver.membership(matrix, List(Ship("coreJS", "1.4.2")))
        List(
          s"root covers JS: ${covered.getOrElse("none")}",
          s"platform row: ${bad.fold(identity, _ => "accepted")}",
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("root covers JS: core"),
          text.contains("platform row:"),
          text.contains("names a platform row"),
        )
      ),
    ),
    section("What generate refuses")(
      md"""
Membership and dynver checks run at `zipxWorkflowGenerate` / `zipxWorkflowCheck`, not at sbt load. Topology checks run
in the planner when ships are present.

| When | Error |
|---|---|
| Library `publish` is Aggregate or Layer | `Ship rows require Graph publish` |
| Library `publish` is `OnReleaseTag` | `cannot use Gate.OnReleaseTag as the publish gate` |
| A publishing module has no row | `published module '…' is not in a Ship or ShipGroup` |
| The same root is in two rows | `Each publishes=true module must be in exactly one row` |
| `ShipGroup` with empty members | `has no members` |
| A member that does not publish | `does not publish` |
| Catalog version already ends in `-ci` | `must be the release number, not a -ci suffix` |
| `sbt-dynver-ci` still loaded | `cannot share version with sbt-dynver-ci` |

Docker Aggregate on a tag is **not** this table. `service` in the example is unpublished, so it is not a membership
hole. See **Validation**.
""",
      exampleValue {
        def show(ships: List[PublishedRow]): String =
          Modver.membership(graph, ships).fold(identity, _ => "ok")
        List(
          s"ok: ${show(ships)}",
          s"ci suffix: ${show(List(Ship("client", "0.3.0-ci"), libsRow))}",
          s"unpublished: ${show(ships :+ Ship("service", "1.0.0"))}",
          s"uncovered: ${show(List(libsRow))}",
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("ok: ok"),
          text.contains("must be the release number, not a -ci suffix"),
          text.contains("does not publish"),
          text.contains("published module 'client' is not in a Ship or ShipGroup"),
        )
      ),
    ),
    section("Adopt")(
      md"""
1. Add `Ship` / `ShipGroup` vals. Every `publishes = true` matrix root belongs in exactly one row.
2. Remove repo-wide `version :=`. Remove `sbt-dynver-ci` if it was a catalog plugin.
3. Replace `Capability.publish` / `publishLayers` / `ZipxCentral.release` with `ZipxModver.publish()`. Compose
   `ZipxCentral.releaseOnce.copy(gate = Gate.OnDefaultPush)` only if you actually publish to Maven Central.
4. Set `zipxCacheEpoch := CacheEpoch.ShipCatalog` (LocalDir). Lockstep OSS keeps `GitTags()`.
5. `sbt zipxWorkflowGenerate`, commit `ci.yml` and composites, open a PR.

Human still writes the next number. Settings: **Settings** (`zipxShips`, `zipxModverPropagate`, `zipxModverBump`,
`zipxModverCheck`, `zipxModverSuggest`, `zipxModverPublishSigned`).
"""
    ),
  )

  private def catalogBumpDiff =
    DocDiff.panel("project/ZipxVersions.scala")(
      DocDiff.line(DocDiff.Kind.Meta, "@@ object MyVersions extends ZipxVersions"),
      DocDiff.line(DocDiff.Kind.Ctx, """  val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")"""),
      DocDiff.line(DocDiff.Kind.Del, """  val client = Ship("client", "0.3.0")"""),
      DocDiff.line(DocDiff.Kind.Add, """  val client = Ship("client", "0.3.1")"""),
    )
end IndependentVersions
