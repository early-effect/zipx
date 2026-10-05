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

  private def threeIds: String =
    (
      ReleaseVersion.make("1.4.2"),
      GitSha.make("1234abcd56780123456789abcdef0123456789ab"),
      DirtyStamp.from("20140707-1030"),
    ) match
      case (Right(line), Right(full), Right(stamp)) =>
        val commit   = SnapshotRevision.commit(line, full)
        val dirty    = SnapshotRevision.dirty(line, full, stamp)
        val noGit    = SnapshotRevision.noGit(stamp)
        val floating = SnapshotRevision.parse("1.4.2-SNAPSHOT").fold(_ => "floating refused", _ => "floating accepted")
        List(
          s"commit ${commit.id}",
          commit.full.fold("full missing")(sha => s"full $sha"),
          s"stored ${commit.storedId}",
          s"dirty ${dirty.id}",
          s"nogit ${noGit.id}",
          floating,
        ).mkString("\n") + "\n" + PinRefusal.of(Seq(commit.id)).map(_.message).mkString("\n")
      case _ =>
        "example inputs rejected"

  private def run(ref: String, status: PublishedRow => Either[ReleaseError, RowStatus]): String =
    ReleaseRequest
      .fromRef(ref)
      .flatMap(ReleasePlan.plan(_, catalog, graph, status))
      .fold(err => s"$ref -> refused: ${err.message}", p => s"$ref -> ${p.entries.map(_.tag).mkString(", ")}")

  def doc = page("Snapshots and releases")(
    md"""
A row's catalog number is the **next** release. Every build until then compiles `<row>-ci`, on a laptop and in CI
alike, so the version string does not change between commits and cache digests hold (see **Caching**). `isSnapshot`
is true because the build is not the release number. A release is a deliberate run that publishes catalog numbers.

Three strings show up. Only one of them is what you pin, and only one of them is a release.
""",
    illustration {
      ReleaseDiagram.strings
    }.assert(ui =>
      assertTrue(
        ReleaseDiagram.prose(ui).contains("1.4.2-ci · every compile"),
        ReleaseDiagram.prose(ui).contains("1.4.2 · the release"),
        ReleaseDiagram.prose(ui).contains("1.4.2-SNAPSHOT · the pointer"),
      )
    ),
    md"""
| String | What it is | Who resolves it |
|---|---|---|
| `1.4.2-ci` | the compile version. Stable across commits, so caches hold | this build, and only this build |
| `1.4.2-1234abcd5678-SNAPSHOT` | one clean commit, as every registry stores it. The pin | a downstream catalog |
| `1.4.2-1234abcd5678+20140707-1030` | a dirty tree. This machine, this minute | nobody else. Ivy only |
| `1.4.2-SNAPSHOT` | the pointer. Its POM names the latest full sha | `zipxSnapshotStatus`. `update` refuses it |
| `1.4.2` | the release number | everyone, after you release it |

```scala
// project/ZipxVersions.scala
val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
val client = Ship("client", "0.3.0")

// build.sbt
zipxCapabilities += ZipxCentral.snapshots
zipxReleaseWorkflow := Some(ZipxCentral.releases)
```

| Build | Compiles | What a publish stores |
|---|---|---|
| any build | `1.4.2-ci`, `0.3.0-ci` | nowhere until you publish |
| `sbt zipxSnapshotPublish local`, clean commit | those `-ci` versions | `1.4.2-<sha>-SNAPSHOT` and `0.3.0-<sha>-SNAPSHOT` in `~/.ivy2/local` |
| `sbt zipxSnapshotPublish`, clean commit | those `-ci` versions | the same coordinates in the snapshot repository |
| a merge to the default branch | those `-ci` versions | the same coordinates, plus a `<row>-SNAPSHOT` pointer POM that names the full sha |
| a push to PR #42 labeled `snapshots` | those `-ci` versions | that PR commit's `<row>-<sha>-SNAPSHOT`. The pointer stays where the default branch left it |
| a dirty tree, `sbt zipxSnapshotPublish local` | those `-ci` versions | `<row>-<sha>+YYYYMMDD-HHmm` in ivy only |
| a `zipxRelease` session | `1.4.2`, `0.3.0` | the release repository, at the catalog numbers |

The sha is the 12-character abbreviation of the commit. The three ids are next. None of this spends a release.
""",
    section("The cycle")(
      md"""
One line, from the first snapshot to the next number. Nothing here moves the catalog except advance, pin-release,
and the bump. Nothing here spends a release except the release job.
""",
      illustration {
        ReleaseDiagram.cycle
      }.assert(ui =>
        assertTrue(
          ReleaseDiagram.prose(ui).contains("zipxSnapshotPublish"),
          ReleaseDiagram.prose(ui).contains("zipxSnapshotAdvance, then reload"),
          ReleaseDiagram.prose(ui).contains("zipxPinRelease"),
          ReleaseDiagram.prose(ui).contains("compile 1.4.3-ci"),
        )
      ),
      md"""
| You want | Command | Rewrites the catalog | Uploads |
|---|---|---|---|
| keep compiling | nothing | no | no |
| try this commit on this machine | `sbt zipxSnapshotPublish local` | no | ivy. A dirty tree gets `+YYYYMMDD-HHmm` |
| share this commit | `sbt zipxSnapshotPublish` | no | the snapshot repository, and the pointer on the default branch |
| publish a pull request | label `snapshots`, then push | no | that commit's sha. The pointer does not move |
| ask if a newer snapshot exists | `sbt zipxSnapshotStatus` | no | no |
| take that newer sha | `sbt 'zipxSnapshotAdvance widgets'` then `reload` | the pin, same line | no |
| move the pin to a newer line | `sbt 'zipxSnapshotAdvance widgets 1.5.0'` then `reload` | the pin, to that line | no |
| ask if a ship can release | `sbt zipxReleasePlan` | no | no |
| pin a release that already exists | `sbt 'zipxPinRelease widgets'` | the pin, to that same line | no |
| release | **zipx release**, or a GitHub Release tag | no. The tag stays on the release commit | one deployment |
| open the next line | the release job's bump pull request | the next patch, on a new commit | no |

A feature pull request stays on the sha it committed. The weekly version-updates job may open a pull request that
runs advance. It does not commit from a test run.
""",
    ),
    section("The three ids")(
      md"""
A published snapshot names the commit it was built from. The catalog line stays the next release number (`1.4.2`).
Git supplies the rest. `isSnapshot` is true because the build is not the release number.

A registry stores a clean commit as that id plus `-SNAPSHOT`. Central's snapshot repository, and any repository with a
snapshot version policy, refuses a version without it, so zipx writes the one form everywhere: Central, GitHub
Packages, any Maven URL, a `file:` registry, and `~/.ivy2/local`. The stored coordinate is also what a downstream
catalog pins, so nothing translates one into the other.

| Tree | Id | Stored and pinned as | Where it goes |
| --- | --- | --- | --- |
| clean commit `1234abcd5678` | `1.4.2-1234abcd5678` | `1.4.2-1234abcd5678-SNAPSHOT` | the snapshot repository, or ivy with `local` |
| dirty working tree | `1.4.2-1234abcd5678+20140707-1030` | not stored | `zipxSnapshotPublish local` only. The `+YYYYMMDD-HHmm` mark is the minute, not a commit |
| no git | `HEAD+20140707-1030` | not stored | local only |

A dirty id is not a pin another machine can resolve. `1.4.2-SNAPSHOT` is not one of these ids: it is the moving
pointer the status command reads, not a dependency. A pin written as the bare id fails `update` before anything
resolves, and the message names the stored form.
""",
      exampleValue {
        threeIds
      }.assert(text =>
        assertTrue(
          text.contains("commit 1.4.2-1234abcd5678"),
          text.contains("full 1234abcd56780123456789abcdef0123456789ab"),
          text.contains("stored 1.4.2-1234abcd5678-SNAPSHOT"),
          text.contains("dirty 1.4.2-1234abcd5678+20140707-1030"),
          text.contains("nogit HEAD+20140707-1030"),
          text.contains("floating refused"),
          text.contains("1.4.2-1234abcd5678 is not stored anywhere. Pin 1.4.2-1234abcd5678-SNAPSHOT"),
        )
      ),
    ),
    section("Iterate from your machine")(
      md"""
Proving a change across two libraries needs no PR, no CI, and no release. Commit the upstream, then:

```text
sbt zipxSnapshotPublish local     # every unreleased row's <row>-<sha>-SNAPSHOT to ~/.ivy2/local
```

That does not write `<row>-ci`, and it does not write the pointer. Pin the commit downstream, then `reload`:

```scala
val zipxCore = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-1234abcd5678-SNAPSHOT")
```

The registry publish below stores the same coordinate, so the pin does not change when the bits move from a laptop to
CI. The pin is not a changing module: after the first resolve, `update` does not re-read `maven-metadata.xml`.
`<row>-SNAPSHOT` with nothing after the line is the pointer, not a build. Depending on it fails `update` and the
message names `zipxSnapshotStatus`.

A dirty working tree is refused by a registry publish. The message names the id and `zipxSnapshotPublish local`.
That local publish writes `<row>-<sha>+YYYYMMDD-HHmm` on this machine only. Another clone cannot resolve it.

Publishing the same commit again overwrites that coordinate and no other. A new commit is a new id.

To share the same bits with a teammate, or with a downstream PR's CI, publish them to the registry instead:

```text
sbt zipxSnapshotPublish           # the same coordinates, to the registry's snapshot repository
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
released yet, at `<row>-<sha>-SNAPSHOT` of that commit, and a pointer module at `<row>-SNAPSHOT` whose POM records
the full sha. A downstream repo pins the commit, not the pointer. A row that is already released is skipped: a snapshot of that
line sorts before the release.

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
carries the label, at that commit's `<row>-<sha>-SNAPSHOT`. It does not move the pointer. There is no `<row>-pr<N>-SNAPSHOT`:
the sha is the id, so two pull requests cannot overwrite each other. A downstream PR pins that sha while the pull
request is open. A fork's PR never runs it: its run has no publishing secrets.

sbt still compiles and packages at `<row>-ci`, exactly what the PR's `test` built, so the job restores the PR's cache.
The published POM names the sha. It needs `verify`, same as mainline snapshots. Labeling a PR starts no run by itself;
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
A downstream catalog pins the commit, as the registry stores it, not the pointer:

```scala
val zipxCore = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-1234abcd5678-SNAPSHOT")
```

The module is not changing: `update` does not re-read `maven-metadata.xml`, and `forceUpdatePeriod` stays unset.
`<line>-SNAPSHOT` fails `update` and names `zipxSnapshotStatus`. The bare `0.15.0-1234abcd5678` fails `update` too,
and names the stored form.

Every generated sbt job sets `COURSIER_TTL: 0s`. `zipxEnv` cannot lengthen that TTL. A commit pin does not need the
revalidation; a qualifier snapshot still does.

| Where | What |
|---|---|
| `resolvers`, and `project/plugins.sbt` for a `Plugin` pin | the publish registry's snapshot repository, plus any `zipxSnapshotRegistries`, while a commit pin, the pointer, or a qualifier snapshot is in the catalog; Central snapshots when the build has no release workflow |
| a qualifier snapshot such as `1.0.0-RC1-SNAPSHOT` | `forceUpdatePeriod := Some(Duration.Zero)`, so `update` re-resolves every session |
| the `test` job | a warning annotation naming those snapshot pins, without failing the run |
| `reload`, `set`, `clean`, while a snapshot pin is in the catalog | forget sbt's in-memory resolutions |
| `zipxRelease` | refuses while a released project depends on a snapshot, including a commit pin |
| `zipxSnapshotStatus` | reads the pointer and reports a newer sha, a deleted build, or a local pin. It does not rewrite |
| `zipxSnapshotAdvance` | rewrites the pin to the pointer's sha, in the stored form, on the pin's own line. Given a line, it reads that line's pointer instead. A feature pull request does not run it |
| `zipxPinRelease` | rewrites a commit pin to that same line after the release exists. It does not take a newer line |
| catalog update | leaves a commit pin and a `<line>-SNAPSHOT` pointer alone, and names `zipxPinRelease` when that line is released |

An ivy copy of `<line>-ci` is a different revision from the sha pin, so it is not selected for that pin. Central
deletes snapshots after 90 days.
""",
      exampleValue {
        val pin   = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-1234abcd5678-SNAPSHOT")
        val bumps = ZipxCatalog.outdated(List(pin), _ => Right(Some("0.15.1"))).map(_.map(b => s"${b.from} -> ${b.to}"))
        val advice = SnapshotPinAdvice.message("zipx-core", pin.version, Some("0.15.1")).getOrElse("")
        s"$bumps\n$advice"
      }.assert(text =>
        assertTrue(
          text.contains("Right(List())"),
          text.contains("Run sbt 'zipxPinRelease zipx-core'"),
        )
      ),
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
    section("Is there a newer snapshot")(
      md"""
`sbt zipxSnapshotStatus` reads the `<line>-SNAPSHOT` pointer once and prints what it found. It does not change the
pin. `reload` does not either. A feature pull request stays on the sha it committed. The weekly version-updates job
may open a pull request that runs `zipxSnapshotAdvance`. That command rewrites the pin. Reload so the new sha is what the session resolves. It does not commit.

A dirty pin is a local build. Advance refuses it: commit the tree and publish the sha, or drop the pin. Central
deletes a snapshot after 90 days. Status says so and names advance.

Advance stays on the pin's line. When a library needs a newer line, `update` fails and names
`sbt 'zipxSnapshotAdvance widgets 1.5.0'`. That reads the `1.5.0-SNAPSHOT` pointer and moves the row to its sha, a
release row included. Naming the line is the only way a pin changes lines.
""",
      exampleValue {
        PinRewrite.moveTo(
          "1.4.2-1234abcd5678-SNAPSHOT",
          ReleaseVersion("1.5.0"),
          GitSha("9876fedcba09876543210fedcba9876543210abc"),
        )
      }.assert(moved => assertTrue(moved == Right(Some("1.5.0-9876fedcba09-SNAPSHOT")))),
      exampleValue {
        SnapshotStatus
          .report(
            "widgets",
            "1.4.2-1234abcd5678-SNAPSHOT",
            Some(GitSha("9876fedcba09876543210fedcba9876543210abc")),
            true,
          )
          .map(SnapshotStatus.render)
          .fold(identity, identity)
      }.assert(text =>
        assertTrue(
          text ==
            """widgets 1.4.2-1234abcd5678-SNAPSHOT
              |  commit 1234abcd5678
              |  latest snapshot of 1.4.2 is 1.4.2-9876fedcba09-SNAPSHOT
              |  run: sbt 'zipxSnapshotAdvance widgets'""".stripMargin
        )
      ),
    ),
    section("When a ship can release")(
      md"""
`sbt zipxReleasePlan` uploads nothing. It reads the catalog, the graph, and the release repository. A ship that
depends on a commit pin is not ready: a release POM cannot depend on a snapshot build. `all` refuses when any
included ship is blocked. An in-repo unreleased upstream is not a pin. It rides along in the same deployment.
""",
      illustration {
        ReleaseDiagram.shipReady
      }.assert(ui =>
        assertTrue(
          ReleaseDiagram.prose(ui).contains("zipxReleasePlan · Not ready"),
          ReleaseDiagram.prose(ui).contains("zipxReleasePlan · Ready"),
        )
      ),
      md"""
`zipxPinRelease` does not look up a newer line. If `1.4.3` is also published, the pin still becomes `1.4.2`.

`sbt 'zipxPinRelease widgets'` checks the release repository for that line, then rewrites the pin from the sha to the
line. Same number. It refuses a missing release, a dirty pin, and a pin that is already a release. It does not jump
to a newer line. After the pin is the release number, run `zipxReleasePlan` again.
""",
      exampleValue {
        val pin = SnapshotRevision.commit(ReleaseVersion("1.4.2"), GitSha("1234abcd56780123456789abcdef0123456789ab"))
        ReleaseReadiness.render(
          List(
            ShipGate(
              "client",
              ReleaseVersion("0.3.0"),
              List(ReleaseBlocker.CommitPin("com.example", "widgets", pin)),
              Nil,
            ),
            ShipGate("libs", ReleaseVersion("1.4.2"), Nil, Nil),
          ),
          "main",
          None,
        )
      }.assert(text => assertTrue(text.contains("all refuses, because client is included."))),
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
A row stays at its released number until someone moves it. The next build still compiles `<row>-ci`. Publishing that
line again writes a snapshot the release sorts ahead of, so those commits publish nothing a consumer can select.
""",
      illustration {
        ReleaseDiagram.afterRelease
      }.assert(ui =>
        assertTrue(
          ReleaseDiagram.prose(ui).contains("tag libs/v1.4.2 stays put"),
          ReleaseDiagram.prose(ui).contains("main compiles 1.4.3-ci"),
        )
      ),
      md"""
The registry is whatever `zipxReleaseWorkflow` names (Central, GitHub Packages,
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
`modver-check` still floors the PR. The release job does not commit the bump onto the tagged SHA. It opens a pull
request from `zipx/modver-bump-${'$'}GITHUB_RUN_ID` whose commit is `sbt zipxModverBump`. The step summary names that
command and appends `target/zipx-release-tags.txt`. The GitHub Release body stays `--generate-notes`.

- **`versionScheme` is what a module promises its consumers.** zipx sets that key to `early-semver`. A module opts
  into `semver-spec`, `pvp`, `strict`, or `always` by setting it. An empty value fails the publish, and `semver` is
  rejected as ambiguous. It does not decide which artifact this build compiles; the next section does.
""",
      exampleValue {
        val libs = ShipGroup("libs", "1.0.0")("models")
        SnapshotGuard.shadowed(libs, "v1.0.0")
      }.assert(text =>
        assertTrue(
          text ==
            """ShipGroup("libs") 1.0.0 is released and has changes since v1.0.0, so a snapshot of 1.0.0 is shadowed by that release and these commits publish nothing. sbt zipxModverBump"""
        )
      ),
    ),
    section("This build's modules win")(
      md"""
A library from another repo is built against some revision of a module this repo builds. It can be a release, or a
commit snapshot of the line this build is still on. Two repos that depend on each other always meet this way: the
other repo is built against an older commit of yours, because it cannot depend on the commit that depends on it.

When a project here depends on that library and on the in-repo module, sbt compiles the in-repo project, whatever
revision the library asked for. The project keeps the library from bringing the module at all (next section), so that
revision does not reach the graph; where it reaches it another way, it is evicted. Say a docs framework was built
against `client` 0.3.0, or against `client` `0.4.0-1234abcd5678`, and this build compiles `client` `0.4.0-ci`:

| Library asked for | This build compiles | `update` |
|---|---|---|
| `client` 0.3.0, a release | `0.4.0-ci`, the in-repo project | resolves |
| `client` `0.4.0-1234abcd5678`, an older commit of the same line | `0.4.0-ci`, the in-repo project | resolves |

Choosing the in-repo project is not a compatibility claim. It is which artifact this build compiles. sbt's own
eviction check would read `0.4.0-ci` against either revision literally and fail, because early-semver compares a
`0.y.0` or `x.0.0` exactly. zipx stands that check down for the build's own modules only. Their `versionScheme` stays
what they publish.

A project that publishes gets the same treatment as one that does not, and neither gets a warning. The proof that the
library still works against the in-repo module is this build's own compile, link, and tests. When that proof fails,
the library is behind: advance its pin of this repo.

A module this repo pins in the catalog, and does not build, wins the same way. **Versions** covers that half,
including the one case `update` refuses: a library that needs a newer revision than the catalog states.
"""
    ),
    section("What a consumer resolves")(
      md"""
A consumer of a published project meets that project's POM and every library's POM at once. When a library names an
older commit of a module the project states, nothing orders the two commits for the consumer, so it could resolve
either, and a zipx consumer whose catalog states neither refuses the pair.

So a published POM keeps each of its libraries from bringing what the project states itself:

- every in-repo module a consumer inherits through it: compile and runtime dependencies, transitively. A `test`,
  `provided`, or `optional` dependency is not inherited, so it is not excluded.
- every commit pin the project declares in a scope a consumer inherits.

The consumer resolves one revision, the one the project was built with. A release is left to the consumer's own order,
which is meaningful for releases. Resolution in this build reads the same list, so what it compiles and what it
publishes agree. Toolchain dependencies (the Scala, Scala.js, and Native libraries) carry no exclusions.

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>docs-framework_3</artifactId>
  <version>1.1.0</version>
  <exclusions>
    <exclusion>
      <groupId>com.example</groupId>
      <artifactId>models_3</artifactId>
    </exclusion>
    <exclusion>
      <groupId>com.example</groupId>
      <artifactId>widgets_3</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```
""",
      exampleValue {
        val client               = ResolvedModule("com.example", "client_3")
        def module(name: String) = ResolvedModule("com.example", name)
        val inRepo               = Map(
          client -> List(PomEdge(PomScope.Compile, module("models_3")), PomEdge(PomScope.Test, module("testkit_3")))
        )
        val declared = List(
          PomDependency(PomScope.Compile, module("widgets_3"), DepRevision.of("1.4.2-1234abcd5678-SNAPSHOT")),
          PomDependency(PomScope.Compile, module("zio_3"), DepRevision.of("2.1.26")),
          PomDependency(PomScope.Test, module("fixtures_3"), DepRevision.of("0.2.0-9876fedcba09-SNAPSHOT")),
        )
        PomExclusions.of(client, inRepo, declared).map(_.name)
      }.assert(excluded => assertTrue(excluded == List("models_3", "widgets_3"))),
    ),
  )
end SnapshotsAndReleases
