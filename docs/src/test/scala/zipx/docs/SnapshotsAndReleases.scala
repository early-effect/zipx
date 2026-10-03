package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.docs.DocsFixtures.config
import zipx.docs.DocsRender.yaml
import zio.test.*

/** Snapshots for iteration, releases on purpose. */
object SnapshotsAndReleases extends DocSpecSuite:

  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("models"), publishes = true),
      ModuleNode(ModuleId("coreLib"), dependsOn = List("models"), publishes = true),
      ModuleNode(ModuleId("client"), dependsOn = List("coreLib"), publishes = true),
    )
  )

  private val libs    = ShipGroup("libs", "1.4.2")("models", "coreLib")
  private val client  = Ship("client", "0.3.0")
  private val catalog = ShipIndex.from(List(libs, client))

  private def released(rows: PublishedRow*): PublishedRow => Either[ReleaseError, RowStatus] =
    row => Right(if rows.contains(row) then RowStatus.Released else RowStatus.Unreleased)

  private def run(ref: String, status: PublishedRow => Either[ReleaseError, RowStatus]): String =
    ReleaseRequest
      .fromRef(ref)
      .flatMap(ReleasePlan.plan(_, catalog, graph, status))
      .fold(err => s"$ref -> refused: ${err.message}", p => s"$ref -> ${p.entries.map(_.tag).mkString(", ")}")

  def doc = page("Snapshots and releases")(
    md"""
A row's catalog number is the **next** release. Every build until then is `<row>-SNAPSHOT`, on a laptop and in CI
alike. A release is a deliberate run that publishes catalog numbers.

```scala
// project/ZipxVersions.scala
val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
val client = Ship("client", "0.3.0")

// build.sbt
zipxCapabilities += ZipxCentral.snapshots
zipxReleaseWorkflow := Some(ZipxCentral.releases)
```

| Build | `models` | `client` | Published to |
|---|---|---|---|
| any build | `1.4.2-SNAPSHOT` | `0.3.0-SNAPSHOT` | nowhere until you publish |
| `sbt zipxSnapshotPublish local`, on a laptop | `1.4.2-SNAPSHOT` | `0.3.0-SNAPSHOT` | `~/.ivy2/local` |
| `sbt zipxSnapshotPublish`, on a laptop | `1.4.2-SNAPSHOT` | `0.3.0-SNAPSHOT` | Central snapshots |
| a merge to the default branch | `1.4.2-SNAPSHOT` | `0.3.0-SNAPSHOT` | Central snapshots |
| a push to PR #42 labeled `snapshots` | `1.4.2-pr42-SNAPSHOT` | `0.3.0-pr42-SNAPSHOT` | Central snapshots |
| a `zipxRelease` session | `1.4.2` | `0.3.0` | Central |

`-SNAPSHOT` is what sbt overwrites on republish, so a republish after every edit reaches a sibling build. It is also
the same string on every commit, so cache digests hold (see **Caching**). None of it spends a release.
""",
    section("Iterate from your machine")(
      md"""
Proving a change across two libraries needs no PR, no CI, and no release. In the upstream repo:

```text
sbt zipxSnapshotPublish local     # every unreleased row to ~/.ivy2/local, which sbt and cs resolve
```

In the downstream repo, pin the coordinate like any other row, then `reload` a running shell:

```scala
val zipxCore = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-SNAPSHOT")
```

Edit upstream, publish again, compile downstream: a `-SNAPSHOT` overwrites, and a project that depends on one
re-resolves every session. When the upstream change adds or changes a dependency, `reload` the downstream shell;
zipx forgets sbt's in-memory resolutions on `reload` and `clean` while a snapshot is pinned.

To share the same bits with a teammate, or with a downstream PR's CI, publish them to the registry instead:

```text
sbt zipxSnapshotPublish           # the same rows to the registry's snapshot repository
```

Both forms publish exactly the rows a merge would (every row whose number is not released yet), skip scaladoc, and
return the shell to a development session when they finish. The registry form checks credentials before anything
uploads: the secrets that registry declared (for Central, `SONATYPE_USERNAME` / `SONATYPE_PASSWORD`), or a credentials
file for its host. A `file:` registry needs neither. A missing credential never leaves some modules published and the
rest not.

A build writes no `publishTo` for any of this. While zipx publishes a row, it routes the upload from
`zipxReleaseWorkflow`'s registry; a development session keeps whatever `publishTo` the build sets. A `file:` registry
rehearses snapshots and releases entirely on one machine.
""",
      exampleValue {
        List(
          ArtifactRegistry.MavenCentral,
          ArtifactRegistry.GitHubPackages("early-effect", "zipx"),
          ArtifactRegistry.Url("file:///tmp/zipx-repo"),
          ArtifactRegistry.Maven(
            "https://acme.artifactory.example/maven-snapshots",
            "https://acme.artifactory.example/maven-releases",
          ),
        ).map(r =>
          s"$r: snapshots -> ${r.snapshotRepository}; releases -> ${r.releaseRepository.getOrElse("localStaging, then sonaRelease")}"
        ).mkString("\n")
      }.assert(routes =>
        assertTrue(
          routes.contains("MavenCentral: snapshots -> https://central.sonatype.com/repository/maven-snapshots/"),
          routes.contains("releases -> localStaging, then sonaRelease"),
          routes.contains("snapshots -> file:///tmp/zipx-repo/; releases -> file:///tmp/zipx-repo/"),
          routes.contains(
            "snapshots -> https://acme.artifactory.example/maven-snapshots/; releases -> https://acme.artifactory.example/maven-releases/"
          ),
        )
      ),
    ),
    section("Mainline snapshots")(
      md"""
With `ZipxCentral.snapshots`, every push to the default branch publishes each row whose catalog number is not
released yet, at `<row>-SNAPSHOT`. A downstream repo can pin that coordinate in CI before the release exists. A row
that is already released is skipped: its snapshot would sort before the release.

The `snapshots` job needs `verify`, so it publishes only after every Verify job has passed or skipped. On a merge
push that skipped Verify it also needs `cache-rehydrate`, which owns that push's build snapshot. It restores that
save and never saves one, so it packages and uploads without recompiling. It skips scaladoc,
which Central checks only on a release, and signs nothing. It needs `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` and
SNAPSHOTs enabled for the namespace in the Central Portal (Namespaces). Central deletes snapshots after 90 days. It is
the same `zipxSnapshotPublish` you run from a laptop.
""",
      exampleValue {
        DocsRender.job("snapshots")(Capability.test, ZipxCentral.snapshots)(using graph)
      }.assert(yml =>
        assertTrue(
          yml.contains("sbt zipxSnapshotPublish") || yml.contains("sbt 'zipxSnapshotPublish'"),
          yml.contains("github.ref == 'refs/heads/main'"),
          yml.contains("needs.verify.result != 'failure'"),
          yml.contains("- verify"),
          !yml.contains("needs.test.result"),
          yml.contains("cache-mode: restore"),
          yml.contains("SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}"),
          !yml.contains("PGP_"),
        )
      ),
    ),
    section("PR snapshots")(
      md"""
`ZipxCentral.pullRequestSnapshots("snapshots")` publishes the same rows from a pull request, on each push once the PR
carries the label, at `<row>-pr<N>-SNAPSHOT`. A downstream PR can pin `0.3.0-pr42-SNAPSHOT` and prove the pair works
before either merges. A fork's PR never runs it: its run has no publishing secrets.

Only the published coordinate moves. sbt still compiles and packages at `<row>-SNAPSHOT`, exactly what the PR's `test`
built, so the job restores the PR's cache and recompiles nothing; each POM names in-repo dependencies at their
`-pr<N>-SNAPSHOT` coordinate. It needs `verify`, same as mainline snapshots. Labeling a PR starts no run by itself;
push to publish.

```scala
zipxCapabilities ++= Seq(ZipxCentral.snapshots, ZipxCentral.pullRequestSnapshots("snapshots"))
```
""",
      exampleValue {
        DocsRender.job("snapshots-pr")(Capability.test, ZipxCentral.pullRequestSnapshots("snapshots"))(using graph)
      }.assert(yml =>
        assertTrue(
          yml.contains("zipxSnapshotPublish pr"),
          yml.contains("contains(github.event.pull_request.labels.*.name, 'snapshots')"),
          yml.contains("github.event.pull_request.head.repo.full_name == github.repository"),
          yml.contains("needs.verify.result != 'failure'"),
          yml.contains("cache-mode: restore"),
        )
      ),
    ),
    section("Release")(
      md"""
`zipx-release.yml` has one job, started two ways:

| Start | Releases |
|---|---|
| a GitHub Release whose tag is `client/v0.3.0` (`v0.3.0` when the catalog has one ship) | that ship, plus its unreleased in-repo upstream ships |
| Actions → **zipx release** → Run workflow, on the default branch | the `ships` field. The form opens on `all`: every ship whose number is not on the registry. Then it creates the tags and GitHub Releases |

`client,libs` is those ships, plus any unreleased in-repo upstream they depend on. A name the catalog does not have
fails before anything uploads. An empty field fails the same way: a cleared box does not mean every ship.

Either way it is one sbt session and one registry deployment, however many ships it carries. The registry is whatever
`zipxReleaseWorkflow` names: Central, GitHub Packages, Artifactory, CodeArtifact, Nexus, or any other Maven repository.
Tagging each finished ship is a separate run, so a separate deployment. One run that carries every finished ship spends
one deployment. A second run spends another. Central's monthly allowance is one reason that second run is expensive.
GitHub Packages, Artifactory, and CodeArtifact make a second run waste too. A ship left out of the list stays a snapshot.

A catalog with several ships listens only to `<ship>/v*` tags, leaving a bare `v*` tag to the image and deploy jobs in
`ci.yml`. With `Ship` rows, `ci.yml` has no publish job. The job restores the build cache and never saves one: its jars
carry release numbers, which no PR build would hit.
""",
      exampleValue {
        ReleaseWorkflow.render(ZipxCentral.releases, config, TagScheme.of(catalog)).yaml
      }.assert(yml =>
        assertTrue(
          yml.contains("- \"*/v*\""),
          !yml.contains("- v*"),
          yml.contains("workflow_dispatch"),
          yml.contains("ships:"),
          yml.contains("default: all"),
          yml.contains("all, or comma-separated ship names (client, libs)"),
          yml.contains("environment: zipx-release"),
          yml.contains("cache-mode: restore"),
          !yml.contains("cache-mode: save"),
          yml.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""),
          yml.contains("ZIPX_RELEASE_REF=$ZIPX_SHIPS"),
          yml.contains("ZIPX_RELEASE_REF=$GITHUB_REF"),
          yml.contains("gh release create"),
          yml.contains("Open the next snapshot"),
          yml.contains("GITHUB_STEP_SUMMARY"),
          yml.contains("target/zipx-release-tags.txt"),
          yml.contains("sbt zipxModverBump"),
        )
      ),
    ),
    section("What a run releases")(
      md"""
A released POM names its in-repo dependencies at their catalog numbers, so a ship always releases with every unreleased
ship it depends on. A ship that depends on it never rides along.

`all` releases every unreleased ship. `client,libs` releases those ships and their unreleased upstreams. `tools` is not
in that list, so it stays a snapshot. That list is still one deployment. Tagging each finished ship instead is one
deployment per tag.
""",
      exampleValue {
        val tools          = Ship("tools", "0.1.0")
        val withTools      = ShipIndex.from(List(libs, client, tools))
        val withToolsGraph = GraphFixture(
          List(
            ModuleNode(ModuleId("models"), publishes = true),
            ModuleNode(ModuleId("coreLib"), dependsOn = List("models"), publishes = true),
            ModuleNode(ModuleId("client"), dependsOn = List("coreLib"), publishes = true),
            ModuleNode(ModuleId("tools"), publishes = true),
          )
        )
        def named(ref: String): String =
          ReleaseRequest
            .fromRef(ref)
            .flatMap(ReleasePlan.plan(_, withTools, withToolsGraph, released()))
            .fold(err => s"$ref -> refused: ${err.message}", p => s"$ref -> ${p.entries.map(_.tag).mkString(", ")}")
        List(
          run("refs/tags/client/v0.3.0", released()),
          run("refs/tags/client/v0.3.0", released(libs)),
          run("refs/tags/libs/v1.4.2", released()),
          run("refs/heads/main", released(libs)),
          named("all"),
          named("client,libs"),
          named("client"),
        ).mkString("\n")
      }.assert(text =>
        def has(prefix: String)(p: String => Boolean): Boolean =
          text.linesIterator.find(_.startsWith(prefix)).exists(p)
        assertTrue(
          text.contains("refs/tags/client/v0.3.0 -> libs/v1.4.2, client/v0.3.0"),
          text.contains("refs/tags/client/v0.3.0 -> client/v0.3.0"),
          text.contains("refs/tags/libs/v1.4.2 -> libs/v1.4.2"),
          text.contains("refs/heads/main -> client/v0.3.0"),
          has("all ->")(line =>
            line.contains("libs/v1.4.2") && line.contains("client/v0.3.0") && line.contains("tools/v0.1.0")
          ),
          has("client,libs ->")(line =>
            line.contains("libs/v1.4.2") && line.contains("client/v0.3.0") && !line.contains("tools")
          ),
          has("client ->")(line =>
            line.contains("libs/v1.4.2") && line.contains("client/v0.3.0") && !line.contains("tools")
          ),
        )
      ),
    ),
    section("Refusals")(
      md"""
Nothing uploads until the plan is sound. Each refusal says what to do next.
""",
      exampleValue {
        List(
          run("refs/tags/client/v0.3.1", released()),
          run("refs/tags/v0.3.0", released()),
          run("refs/tags/client/v0.3.0", released(client)),
          run("refs/heads/main", released(libs, client)),
          run("refs/heads/main", row => Left(ReleaseError.RegistryUnreachable(row, "connect timed out"))),
          run("nope", released()),
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("""tag client/v0.3.1 does not match Ship("client") 0.3.0; tag client/v0.3.0"""),
          text.contains("tag v0.3.0 names no catalog row; this catalog releases libs/v1.4.2, client/v0.3.0"),
          text.contains("""Ship("client") 0.3.0 is already released; move the row to release again"""),
          text.contains("every row's catalog number is already released"),
          text.contains("""cannot tell whether ShipGroup("libs") 1.4.2 is released: connect timed out"""),
          text.contains("ship 'nope' is not in the catalog; this catalog releases libs, client"),
        )
      ),
    ),
    section("Pin a snapshot downstream")(
      md"""
A downstream catalog names the snapshot like any other row:

```scala
val zipxCore = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-SNAPSHOT")
```

While any row is a snapshot, zipx:

| Where | What |
|---|---|
| `resolvers`, and `project/plugins.sbt` when a `Plugin` is pinned | the publish registry's snapshot repository, plus any `zipxSnapshotRegistries`; Central snapshots when the build has no release workflow; nothing when no ship is pinned |
| each project that depends on a `-SNAPSHOT` | `forceUpdatePeriod := Some(Duration.Zero)`, so `update` re-resolves every session; other projects keep their cached `update` |
| every `ci.yml` job | `COURSIER_TTL: 0s`, so Coursier revalidates a changing artifact instead of trusting it for 24 hours; releases stay cached forever |
| the `test` job | a warning annotation naming the pins, without failing the run |
| `reload`, `set`, `clean` | forget sbt's in-memory resolutions, so a republish with new dependencies is seen |
| `zipxRelease` | refuses while a released project depends on a snapshot |
| catalog update | rewrites the pin to the latest release once one reaches it |

On a laptop, `publishLocal` of the upstream wins over Central snapshots until you delete it from `~/.ivy2/local`. A
long-lived sbt shell keeps every resolution in memory (sbt/sbt#6512), so while a snapshot is pinned zipx forgets them
on `reload`, `set`, and `clean` (which `cleanFull` runs): after a republished snapshot adds or changes a dependency, run
`reload`. New code in the same jar is seen at once. Run sbt with `COURSIER_TTL=0s` to revalidate remote snapshots locally too. Central deletes snapshots after 90 days, which promotion normally beats.
""",
      exampleValue {
        val pin = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-SNAPSHOT")
        ZipxCatalog.outdated(List(pin), _ => Right(Some("0.15.1"))).map(_.map(b => s"${b.from} -> ${b.to}"))
      }.assert(promoted => assertTrue(promoted == Right(List("0.15.0-SNAPSHOT -> 0.15.1")))),
      exampleValue {
        val packages = ArtifactRegistry.GitHubPackages("iterable", "maven-packages")
        List(
          SnapshotPins.resolverLine(packages),
          SnapshotPins.pluginsSbtLines(SnapshotPins.registries(None, Nil)).mkString("\n"),
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains(
            """resolvers += "zipx-github-packages-iterable-maven-packages" at "https://maven.pkg.github.com/iterable/maven-packages/""""
          ),
          text.contains(
            """resolvers += "central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/""""
          ),
        )
      ),
    ),
    section("Setup")(
      md"""
1. `zipxReleaseWorkflow := Some(ZipxCentral.releases)`, or `ZipxGitHubPackages.releases` / `ZipxMaven.releases` (see
   **Packs**), then `sbt zipxWorkflowGenerate`.
2. Create the GitHub Environment `zipx-release` (Settings → Environments). Add required reviewers there if a release
   should wait for a human; scope the signing secrets to it if you want them nowhere else.
3. With several ships and docs on GitHub Pages, let the `github-pages` environment deploy from tags matching `*/v*`
   (Settings → Environments → github-pages → Deployment branches and tags). A release tag's docs deploy otherwise
   fails its environment rule, even though the release itself succeeds.
4. Release: draft a GitHub Release with the ship's tag, or run **zipx release** from the Actions tab. The form opens
   with `ships` = `all`. `client,libs` releases those ships plus unreleased in-repo upstreams, in one deployment. A
   name the catalog does not have fails before anything uploads.

A tag pushed on a commit the default branch has not reached is refused before sbt starts. A dispatch from any other
branch does not run. A Central deployment is named for what it carries, `<organization> <ship> <n>, ...`; the root
project keeps version `0.0.0` when it is in no ship, because `sonaRelease` refuses a root at `-SNAPSHOT`.
"""
    ),
    section("After a release")(
      md"""
A row stays at its released number until someone moves it, so the next build is `<row>-SNAPSHOT` of a number the
release registry already has. Maven sorts `1.0.0-SNAPSHOT` before `1.0.0`, so that snapshot is shadowed: the commits
publish nothing a consumer can select. The registry is whatever `zipxReleaseWorkflow` names (Central, GitHub Packages,
CodeArtifact, Artifactory, Nexus, or any other Maven release URL), read at its release root, not its snapshot
repository. A pre-signed 302 on the POM counts as published. A 401, a missing token, or a registry that cannot be
reached fails the publish. It is not read as "not released".

`zipxDriftGate` defaults to `Fail`:

- `Compile / compile` and `Test / compile` of a module in that row do not start. The message names the row, its
  number, and its tag, and ends with `sbt zipxModverBump`. On GitHub Actions, `Fail` emits `::error` and `Warn` emits
  `::warning`.
- `zipxSnapshotPublish` (including `local` and `pr`) still publishes rows that are not released, restores the session,
  then fails.
- `Warn` logs the same sentence, compile succeeds, and the snapshot publish of the open rows exits 0. Use it when a
  green compile of a shadowed row is what you want.
- `sbt zipxReleaseDrift` lists changed rows and does not fail the shell.
- A row with no tag in this clone stays quiet at compile (a laptop without the release tag still builds). Snapshot
  publish still refuses it when the registry says the number is released, because the clone cannot prove the tree is
  clean.
- An unreleased row's nightly still publishes. A docs-only push whose released rows are unchanged stays green.

`sbt zipxModverBump` rewrites every released row to the next patch. A kind word (`minor`, `major`) does that for every
released row. A ship id rewrites that one row even when it is not released. The command does not run MiMa.
`modver-check` still floors the PR. The release job does not commit the catalog. Its last step appends the tags in
`target/zipx-release-tags.txt` and `sbt zipxModverBump` to the Actions step summary. The GitHub Release body stays
`--generate-notes`.

- **A library built against the release meets the in-repo copy.** sbt always uses the in-repo project, and its
  eviction check reads `0.10.0-SNAPSHOT` against `0.10.0` literally; early-semver compares `0.y.0` and `x.0.0`
  exactly, tag included. zipx exempts the build's own artifacts from that check and checks them itself after `update`:
  the row's next number against the release the library needs, under the module's own `versionScheme`. zipx sets that
  key to `early-semver`. A module opts into `semver-spec`, `pvp`, `strict`, or `always` by setting it. An empty value
  fails the publish, and `semver` is rejected as ambiguous. `0.10.0-SNAPSHOT`
  over `0.10.0` resolves; `0.11.0-SNAPSHOT` over `0.10.0` is a conflict, naming both.
""",
      exampleValue {
        val libs = ShipGroup("libs", "1.0.0")("models")
        SnapshotGuard.shadowed(libs, "v1.0.0")
      }.assert(text =>
        assertTrue(
          text ==
            """ShipGroup("libs") 1.0.0 is released and has changes since v1.0.0, so 1.0.0-SNAPSHOT is shadowed and these commits publish nothing. sbt zipxModverBump"""
        )
      ),
    ),
    section("A conflict's severity follows what ships")(
      md"""
When zipx finds that conflict, what it does depends on whether the project publishes:

| Project | Conflict | Why |
|---|---|---|
| publishes (a `Ship` / `ShipGroup` member) | fails `update` | its POM would ship the mix to every consumer |
| does not publish (`publish / skip := true`: docs, examples) | loud warning; `update` resolves | nothing downstream sees it; its own compile, link, and tests are the proof |

That is what lets a repo release its libraries on their own schedule, even when its docs depend on something built
against those libraries. Say the docs site uses a docs framework, and the framework's released version was built
against `client` 0.3.0. When this repo moves `client` to 0.4.0, the docs project meets the in-repo `0.4.0-SNAPSHOT`
against a library that needs 0.3.0:

```text
[warn] zipx: the build's own artifacts conflict with a release:
  * com.example:client_3:0.4.0-SNAPSHOT (early-semver) is selected over 0.3.0: 0.4.0 is not early-semver-compatible with it
  (a warning: this project does not publish)
```

The docs project publishes nothing, so `client` 0.4.0 releases anyway. If the framework really cannot run on the new
`client`, the docs fail to compile, link, or test, which is the real signal; if they pass, the old framework is fine
until it catches up. A published row in the same position fails instead, and should: its POM would carry the mix to
every consumer.
"""
    ),
  )
end SnapshotsAndReleases
