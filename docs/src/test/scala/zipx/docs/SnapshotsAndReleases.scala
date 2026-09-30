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
uploads: for Central, `SONATYPE_USERNAME` / `SONATYPE_PASSWORD`, or a credentials file for `central.sonatype.com`. A
missing token never leaves some modules published and the rest not.

A build writes no `publishTo` for any of this. While zipx publishes a row, it routes the upload from
`zipxReleaseWorkflow`'s registry; a development session keeps whatever `publishTo` the build sets. A `file:` registry
rehearses snapshots and releases entirely on one machine.
""",
      exampleValue {
        List(
          ArtifactRegistry.MavenCentral,
          ArtifactRegistry.GitHubPackages("early-effect", "zipx"),
          ArtifactRegistry.Url("file:///tmp/zipx-repo"),
        ).map(r =>
          s"$r: snapshots -> ${r.snapshotRepository}; releases -> ${r.releaseRepository.getOrElse("localStaging, then sonaRelease")}"
        ).mkString("\n")
      }.assert(routes =>
        assertTrue(
          routes.contains("MavenCentral: snapshots -> https://central.sonatype.com/repository/maven-snapshots/"),
          routes.contains("releases -> localStaging, then sonaRelease"),
          routes.contains("snapshots -> file:///tmp/zipx-repo/; releases -> file:///tmp/zipx-repo/"),
        )
      ),
    ),
    section("Mainline snapshots")(
      md"""
With `ZipxCentral.snapshots`, every push to the default branch publishes each row whose catalog number is not
released yet, at `<row>-SNAPSHOT`. A downstream repo can pin that coordinate in CI before the release exists. A row
that is already released is skipped: its snapshot would sort before the release.

The `snapshots` job waits on this run's cache owner (`test`, or `cache-rehydrate` on a merge push that skipped
Verify), restores that save, and never saves one, so it packages and uploads without recompiling. It skips scaladoc,
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
          yml.contains("needs.test.result != 'failure'"),
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
`-pr<N>-SNAPSHOT` coordinate. Labeling a PR starts no run by itself; push to publish.

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
          yml.contains("cache-mode: restore"),
        )
      ),
    ),
    section("Release")(
      md"""
`zipx-release.yml` has one job, started two ways:

| Start | Releases |
|---|---|
| a GitHub Release whose tag is `client/v0.3.0` (`v0.3.0` when the catalog has one row) | that row, plus its unreleased in-repo upstream rows |
| Actions → **zipx release** → Run workflow, on the default branch | every row whose number is not on the registry; then creates the tags and GitHub Releases |

Either way it is one sbt session and one registry deployment, however many rows it carries. A catalog with several
rows listens only to `<row>/v*` tags, leaving a bare `v*` tag to the image and deploy jobs in `ci.yml`. With `Ship`
rows, `ci.yml` has no publish job. The job restores the build cache and never saves one: its jars carry release
numbers, which no PR build would hit.
""",
      exampleValue {
        ReleaseWorkflow.render(ZipxCentral.releases, config, TagScheme.of(catalog)).yaml
      }.assert(yml =>
        assertTrue(
          yml.contains("- \"*/v*\""),
          !yml.contains("- v*"),
          yml.contains("workflow_dispatch"),
          yml.contains("environment: zipx-release"),
          yml.contains("cache-mode: restore"),
          !yml.contains("cache-mode: save"),
          yml.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""),
          yml.contains("gh release create"),
        )
      ),
    ),
    section("What a run releases")(
      md"""
A released POM names its in-repo dependencies at their catalog numbers, so a row always releases with every unreleased
row it depends on. A row that depends on it never rides along.
""",
      exampleValue {
        List(
          run("refs/tags/client/v0.3.0", released()),
          run("refs/tags/client/v0.3.0", released(libs)),
          run("refs/tags/libs/v1.4.2", released()),
          run("refs/heads/main", released(libs)),
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("refs/tags/client/v0.3.0 -> libs/v1.4.2, client/v0.3.0"),
          text.contains("refs/tags/client/v0.3.0 -> client/v0.3.0"),
          text.contains("refs/tags/libs/v1.4.2 -> libs/v1.4.2"),
          text.contains("refs/heads/main -> client/v0.3.0"),
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
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("""tag client/v0.3.1 does not match Ship("client") 0.3.0; tag client/v0.3.0"""),
          text.contains("tag v0.3.0 names no catalog row; this catalog releases libs/v1.4.2, client/v0.3.0"),
          text.contains("""Ship("client") 0.3.0 is already released; move the row to release again"""),
          text.contains("every row's catalog number is already released"),
          text.contains("""cannot tell whether ShipGroup("libs") 1.4.2 is released: connect timed out"""),
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
| `resolvers`, and `project/plugins.sbt` when a `Plugin` is pinned | add `central-snapshots`; nothing when no row is pinned |
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
    ),
    section("Setup")(
      md"""
1. `zipxReleaseWorkflow := Some(ZipxCentral.releases)`, then `sbt zipxWorkflowGenerate`.
2. Create the GitHub Environment `zipx-release` (Settings → Environments). Add required reviewers there if a release
   should wait for a human; scope the signing secrets to it if you want them nowhere else.
3. Release: draft a GitHub Release with the row's tag, or run **zipx release** from the Actions tab.

A tag pushed on a commit the default branch has not reached is refused before sbt starts. A dispatch from any other
branch does not run.
"""
    ),
  )
end SnapshotsAndReleases
