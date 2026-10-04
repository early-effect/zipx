package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.config
import zio.test.*

/** Outbound Ship / ShipGroup rows: the next release number, snapshots until a deliberate release. */
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

  private val libsRow   = ShipGroup("libs", "1.4.2")("models", "coreLib")
  private val clientRow = Ship("client", "0.3.0")
  private val ships     = List[PublishedRow](libsRow, clientRow)
  private val index     = ShipIndex.from(ships)

  def doc = page("Independent versions")(
    md"""
A repo whose artifacts always ship together is one `ShipGroup` over every published module, as zipx itself is. A tag-driven
lockstep build (`sbt-dynver-ci`, Aggregate `ZipxCentral.release`, `Gate.OnReleaseTag`) still generates, but the rows
below are the model the org is moving to.

Use `Ship` / `ShipGroup` rows when a monorepo publishes several libraries on different cadences. Presence of any such
val is the feature flag. A row holds the **next** release number, every build compiles `<row>-ci`, and a release is a
deliberate run of `zipx-release.yml`. The human writes the number in a PR; CI suggests a MiMa-informed edit as a sticky
comment and **fails closed** when a changed row is still at a released number, or below the MiMa floor. Merges release
nothing.

```mermaid
flowchart TD
  Catalog[ZipxVersions catalog]
  Catalog --> Ships{Ship rows?}
  Ships -->|no| Dynver[dynver-ci]
  Dynver --> Tag[v* tag]
  Tag --> Agg[ZipxCentral.release]
  Ships -->|yes| Row[row-ci everywhere]
  Row --> Check[suggest + check on PR]
  Check --> Merge[merge to main]
  Merge --> Release[zipx-release.yml]
  class Catalog,Ships warn
  class Dynver,Tag,Agg,Row,Check,Merge,Release happy
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
  Gate -->|row still released, or undersized| Red([fail closed])
  Gate -->|ok| Human[4 · you write the number]
  Human --> Merge([5 · merge to main])
  Merge --> Release([6 · GitHub Release or dispatch])
  class Push,Suggest,Gate,Human,Merge warn
  class Red sad
  class Release happy
```

1. Push a PR that changes sources. You may not have edited a `Ship` yet.
2. `modver-suggest` posts a sticky comment with the MiMa-informed constructors (best-effort on forks).
3. `modver-check` reads each changed row's last release from the registry's `maven-metadata.xml`. A row still at that
   number must move; a moved row must clear the MiMa floor measured against that release's jar. Over-bump is fine. An
   unreadable registry or jar fails the check; it never reads as a first release. When the release registry is GitHub
   Packages, `modver-check` and `modver-suggest` also get `packages: read` and `GITHUB_TOKEN`: a `permissions` block
   drops every scope it does not name, and the metadata request sends that token. Packages answers 401 to either
   omission.
4. You write the number (`sbt zipxModverBump`, or one row by hand) and push. The bump does not run MiMa.
   `modver-check` does, against the opened number.
5. Before merging, prove the change downstream without a release: commit, `sbt zipxSnapshotPublish local` here, and
   pin `<row>-<sha>` there (or `sbt zipxSnapshotPublish` for another machine's CI). See **Snapshots and releases**.
6. Merge. The default branch still compiles `<row>-ci` and publishes that commit's `<row>-<sha>`. Later PRs in the
   same cycle pass without another bump unless MiMa says their change is bigger than the row already declares.
7. Release when ready: a GitHub Release tagged `client/v0.3.1`, or Run workflow on **zipx release**. See **Snapshots
   and releases** for the picture: status, advance, release plan, pin-release, then the release.
8. The release job opens a pull request that runs `sbt zipxModverBump`. That commit is not the tagged SHA. Until the
   number moves, a snapshot of the released line is shadowed and `zipxDriftGate` (default `Fail`) stops the row's
   compile and fails the snapshot publish. The check reads the registry `zipxReleaseWorkflow` names.

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
Drop the repo-wide `version := "…"`. A member's `version` is a pure function of its row: `<row>-ci` in every
build, on a laptop and in CI alike, and the catalog number only inside a `zipxRelease` session. Aggregators and
unpublished apps keep sbt's default version.

| Where | Number | Why |
|---|---|---|
| Catalog constructor | release number only (`1.4.2`, never `1.4.2-SNAPSHOT`) | the human writes the next release |
| Any build: PR, merge, a snapshot publish | `<row>-ci` (`1.4.2-ci`) | the same from commit to commit, so caches hold |
| `sbt zipxSnapshotPublish` of a clean commit | `<row>-<sha>` in the repository | the pin another build resolves. Central stores that id plus `-SNAPSHOT` |
| A `zipxRelease` session | catalog number, for every row member | its POMs name in-repo dependencies at release numbers |

A cache needs a version that does not change between commits. `<row>-ci` is that version. A per-commit version, such
as dynver's hash, changes jar names on the classpath and busts the digest. The published snapshot is a different
string, the commit id, and the test job does not compile it. `publishLocal` of `<row>-ci` is not how another build
tries the change: that command is `sbt zipxSnapshotPublish local`, and its revision is the sha or the dirty id.

`zipxDepUpdate` / `catalog update` rewrite `Lib` / `Plugin` / `Action` only. They never touch `Ship` / `ShipGroup`.
Bump outbound rows yourself:

```text
sbt zipxModverBump                  # every released row, patch
sbt "zipxModverBump minor"          # every released row, minor
sbt "zipxModverBump client"         # that row, patch, released or not
sbt "zipxModverBump libs minor"
sbt "zipxModverBump client major"
```

No arguments, or a kind alone, never bumps a row the release registry does not already have: that would skip a planned
release. Naming the row does. A ship whose name is `patch` is that ship, not the kind. `modver-check` still owns MiMa.
""",
      exampleValue {
        val ids                                                          = Set("client", "libs")
        def show(tokens: List[String], known: Set[String] = ids): String =
          ShipBumpRequest.parse(tokens, known).fold(_.message, _.toString)
        List(
          show(Nil),
          show(List("minor")),
          show(List("client")),
          show(List("client", "major")),
          show(List("patch"), Set("patch", "libs")),
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("Released(Patch)"),
          text.contains("Released(Minor)"),
          text.contains("One(client,Patch)"),
          text.contains("One(client,Major)"),
          text.contains("One(patch,Patch)"),
        )
      ),
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
          ReleaseVersion("0.3.0").bump(ReleaseBump.Patch) == ReleaseVersion("0.3.1"),
        )
      ),
    ),
    section("Affected is not the bump set")(
      md"""
**Affected** answers "which Verify jobs can we skip." Modver answers "which rows must move, and how far." Reusing
`affectedModules` for the second question is how a check fails open.

```mermaid
flowchart TD
  Files([changed files]) --> Own[owning published roots]
  Own --> Aff[Affected reverse-dep]
  Aff --> Verify([Verify · fail open])
  Own --> Lift[group lift]
  Lift --> MiMa[MiMa vs last release]
  MiMa --> Prop[propagate]
  Prop --> Bump([bump set · fail closed])
  class Files,Own,Aff,Verify warn
  class Lift,MiMa,Prop,Bump happy
```

| Set | Inputs | Rule | Failure |
|---|---|---|---|
| Verify (Affected) | graph, files | reverse-dep of owners; `.sbt` / `project/` => all; diff fail => `["all"]` | fail **open** |
| Bump | graph, ships, files, registry | owners ∩ publishes, **no** reverse-dep, **no** build-file explosion, group lift, then MiMa against the last release, then propagate | fail **closed** |

What a release carries is a third question with its own rule (the requested rows plus their unreleased in-repo
upstream rows); see **Snapshots and releases**.

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
Libraries release from `zipx-release.yml`. Docker and deploy stay in `ci.yml` on a human `v*` tag.

```mermaid
flowchart TD
  Release([GitHub Release or dispatch]) --> Lib[zipx-release.yml]
  Lib --> Registry[(registry)]
  Tag([human v* tag]) --> Img[ci.yml docker image]
  Img --> Dep[deploy]
  class Release,Lib,Registry happy
  class Tag,Img,Dep warn
```

| What | Signal | Where |
|---|---|---|
| Library coordinates | a GitHub Release tagged `<row>/v<n>`, or a dispatch (`ships`, default `all`) | `zipx-release.yml` (`zipxReleaseWorkflow`) |
| Docker image / deploy | a **human** `v*` tag | `ci.yml` (`ZipxAws.dockerPublishAll`, deploy) |

With rows, `ci.yml` has no library publish job, and generate refuses one. A library-only release does not push an
image.

```scala
zipxReleaseWorkflow := Some(ZipxCentral.releases)
zipxCapabilities += Capability.testLayers
zipxCapabilities += ZipxAws.dockerPublishAll(Registry.destinations) // still OnReleaseTag
```
""",
      exampleValue {
        DocsRender.job("docker")(Capability.docker)(using graph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("refs/tags/v"),
          yaml.contains("service/Docker/publish"),
          !yaml.contains("workflow_dispatch"),
        )
      ),
    ),
    section("Propagate")(
      md"""
Propagation answers one question: when a row moves, which of the rows built on it must move too? Default is
`MatchBump`. A published row whose in-repo upstream takes a bump must take at least the same kind, so `modver-check`
fails the PR until it does. Built-ins walk **published reverse-deps across rows** after MiMa kinds exist. Intra-group
`dependsOn` (models → coreLib inside `libs`) is not a propagate edge: a group already moves as one.

The reason is what a release carries. A row's POM names its in-repo dependencies at the numbers they had when it
released. Say `libs` breaks and releases 1.5.0 while `client` stays at its released 0.3.0: `client` 0.3.0 on the
registry still names `libs` 1.4.2. A consumer that takes the new `libs` and any `client` now resolves a mix sbt
rejects (`libs 1.5.0 is selected over 1.4.2`), and nothing in *this* repo fails, because in-repo builds always use the
current `libs`. Only a consumer finds out, one release later. `MatchBump` makes the PR that breaks `libs` also move
`client`, so one release carries both and every released POM agrees.

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
  CoreLib -.->|propagate edge · MatchBump moves client| Client
  class Models,CoreLib,Client happy
```

```scala
zipxModverPropagate := ModverPropagate.MatchBump        // default: at least the triggering kind
zipxModverPropagate := ModverPropagate.PatchPublished   // patch published reverse-deps
zipxModverPropagate := ModverPropagate.Never            // opt out: only the rows the diff touches
zipxModverPropagate := ModverPropagate.custom { (kinds, graph, ships) => kinds }
```

| Policy | What a dirty `libs` does to `client` | Released POMs afterwards |
|---|---|---|
| `MatchBump` (default) | at least the `libs` kind (a binary break floors `client` at major too) | agree |
| `PatchPublished` | patch, if `client` publishes | agree, but a patch may carry a break |
| `Never` | nothing | `client` keeps naming the old `libs` |

Propagation acts when a PR moves the upstream row. A repo whose rows are already out of step (released under `Never`,
say) catches up with one PR that moves each stale dependent itself; after that, `MatchBump` keeps them in step.
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
          !text.linesIterator.nextOption().exists(_.contains("ship:client")),
          text.contains("PatchPublished") && text.contains("ship:client=Patch"),
          text.contains("MatchBump") && text.contains("ship:client=Minor"),
        )
      ),
    ),
    section("Cache epoch")(
      md"""
With rows, `zipxCacheEpoch` defaults to `CacheEpoch.ShipCatalog`: LocalDir keys off sorted row identity and number
(baked at generate, same path as `Fixed`).

```mermaid
flowchart TD
  Bump([Ship bump]) --> Local[LocalDir · whole-repo epoch]
  Bump --> Remote[remote · that module only]
  class Bump warn
  class Local,Remote happy
```

One row bump rolls the **repo-wide** LocalDir namespace in the same PR that moves the `<row>-ci` strings. A
release rolls nothing: the release run restores the cache and never saves. Remote `cacheVersion` stays JDK/OS only; a
bump already changes that module's `version`, so only that module's remote entries miss. Full guide: **Caching**.
""",
      exampleValue {
        val hash = Modver.epochHash(ships)
        val yaml = DocsRender.job("test")(Capability.test)(using
          graph,
          config.copy(cacheEpoch = CacheEpoch.ShipCatalog, shipEpochHash = Some(hash)),
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
These run at `zipxWorkflowGenerate` / `zipxWorkflowCheck`, not at sbt load.

| When | Error |
|---|---|
| `zipxCapabilities` has a `publish` | `Ship rows release from zipx-release.yml (zipxReleaseWorkflow), so ci.yml has no publish job` |
| `zipxReleaseWorkflow` is `None` | `set zipxReleaseWorkflow (ZipxCentral.releases, ZipxGitHubPackages.releases, or ZipxMaven.releases)` |
| A publishing module has no row | `published module '…' is not in a Ship or ShipGroup` |
| The same root is in two rows | `Each publishes=true module must be in exactly one row` |
| `ShipGroup` with empty members | `has no members` |
| A member that does not publish | `does not publish` |
| `sbt-dynver-ci` still loaded | `cannot share version with sbt-dynver-ci` |

A row version that is not `major.minor.patch`, such as `Ship("client", "0.3.0-SNAPSHOT")`, never reaches generate:
the catalog does not compile (`a release number is major.minor.patch`).

Docker Aggregate on a tag is **not** this table. `service` in the example is unpublished, so it is not a membership
hole. See **Validation**.
""",
      exampleValue {
        def show(ships: List[PublishedRow]): String =
          Modver.membership(graph, ships).fold(identity, _ => "ok")
        List(
          s"ok: ${show(ships)}",
          s"unpublished: ${show(ships :+ Ship("service", "1.0.0"))}",
          s"uncovered: ${show(List(libsRow))}",
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("ok: ok"),
          scala.compiletime.testing
            .typeCheckErrors("""Ship("client", "0.3.0-SNAPSHOT")""")
            .exists(_.message.contains("a release number is major.minor.patch")),
          text.contains("does not publish"),
          text.contains("published module 'client' is not in a Ship or ShipGroup"),
        )
      ),
    ),
    section("Adopt")(
      md"""
1. Add `Ship` / `ShipGroup` vals holding each row's **next** release. Every `publishes = true` matrix root belongs in
   exactly one row.
2. Remove repo-wide `version :=`. Remove `sbt-dynver-ci` if it was a catalog plugin.
3. Remove `Capability.publish` / `publishLayers` / `ZipxCentral.release` from `zipxCapabilities`, and set
   `zipxReleaseWorkflow := Some(ZipxCentral.releases)` (or a `ReleaseWorkflow` for your registry).
4. `sbt zipxWorkflowGenerate`, commit `ci.yml`, `zipx-release.yml`, and composites, open a PR.
5. Create the `zipx-release` GitHub Environment. Release with a GitHub Release or Run workflow.

Human still writes the next number. Settings: **Settings** (`zipxShips`, `zipxReleaseWorkflow`, `zipxDriftGate`,
`zipxModverPropagate`, `zipxModverBump`, `zipxModverCheck`, `zipxModverSuggest`).
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
