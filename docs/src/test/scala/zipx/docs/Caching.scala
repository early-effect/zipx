package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

object Caching extends DocSpecSuite:

  def doc = page("Caching")(
    md"""
zipx restores sbt's cache on the CI runner so the test job does not start from zero every time. You can ignore the
knobs on this page until CI feels slow.

sbt 2 caches task results **across JVM runs**. zipx restores that cache before the Verify test task, keyed by a
**commit-stable epoch** (`zipxCacheEpoch`: `CacheEpoch.ShipCatalog` when the catalog has `Ship` / `ShipGroup` rows,
else `CacheEpoch.GitTags()`). Every push within a PR reuses prior hits; a row bump or a release tag rolls the epoch. Remote backends share the same hits across
machines, including developer laptops when CI hydrates a shared store (see **Remote cache for teams**).
Hits need a `version` that is the same from commit to commit, such as `<row>-ci` (see **Independent versions**).

```mermaid
flowchart TD
  Push([1 · git push]) --> Restore[2 · restore epoch cache]
  Restore --> Sbt[3 · Verify test task]
  Sbt --> Hits{4 · digest hits?}
  Hits -->|yes| Skip([skip redo when digests match])
  Hits -->|no| Work([run work · write digests])
  Work --> Store[(LocalDir or remote)]
  Skip --> Store
  class Push,Restore,Sbt,Hits warn
  class Work sad
  class Skip,Store happy
```

Miss path (amber → red) pays compile/test and writes digests; hit path (green) reuses them. Both land in the same
backend: `LocalDir` via `actions/cache` inside `zipx-sbt-setup`, or a remote action cache (`grpc`, `grpcs`, `http`, or
`https`). The restore key is the
commit-stable epoch
(`zipxCacheEpoch`), so PR pushes share hits and a release tag rolls a fresh namespace.

zipx wires cache into **generated jobs** (same planner as topology). It is not a standalone acceleration appliance: the
goal is CI-from-graph plus content-addressed reuse, not a second product to configure beside hand-maintained YAML.
""",
    section("Epoch strategies")(
      md"""
```scala
zipxCacheEpoch := CacheEpoch.ShipCatalog               // default with Ship / ShipGroup rows: hash of row numbers
zipxCacheEpoch := CacheEpoch.GitTags()                 // default without rows: resolve from git tags on the runner
zipxCacheEpoch := CacheEpoch.GitTags(tagMatch = "v*")  // same, explicit match glob
zipxCacheEpoch := CacheEpoch.Fixed(version.value)      // bake at generate time
zipxCacheEpoch := CacheEpoch.Script(myEpochShell)      // custom shell; must write epoch= and release=
```

**GitTags (default without rows):** a `Resolve cache epoch` step runs after checkout (`fetch-depth: 0`, `fetch-tags: true`). On a
`v*` tag ref, epoch = release = tag without `v`. Otherwise the latest matching tag becomes release and epoch is
`$${release}-SNAPSHOT`. If local tags lag `origin` (or none match), the step emits an Actions `::warning` titled
`zipx cache epoch` so shallow/missing tags are obvious in the run summary.

**Fixed:** embeds a literal into the workflow at `zipxWorkflowGenerate` (useful for scripted tests or unusual versioning).
Prefer GitTags so post-tag PRs warm from the release cache without a regenerate commit.

**Script:** supply your own shell; write `epoch=` and `release=` to `$$GITHUB_OUTPUT`. Restore-keys use both outputs.

**ShipCatalog (default with rows):** bakes a SHA-256 of sorted row identity and number as the setup composite
`cache-epoch` input (same generate-time path as `Fixed`). The epoch rolls exactly when a row number moves, which is
exactly when `<row>-ci` version strings, and so digests, change. A release does not roll it: the release run
restores and never saves. `GitTags()` would roll one step late, at the tag, after the bump PR already moved the
digests.
""",
      exampleValue {
        val ships = List[PublishedRow](Ship("client", "0.3.0"), ShipGroup("libs", "1.4.2")("models", "coreLib"))
        val hash  = Modver.epochHash(ships)
        val yaml  = DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cacheEpoch = CacheEpoch.ShipCatalog, shipEpochHash = Some(hash)),
        )
        s"hash: $hash\n$yaml"
      }.assert(text =>
        val hash = Modver.epochHash(
          List[PublishedRow](Ship("client", "0.3.0"), ShipGroup("libs", "1.4.2")("models", "coreLib"))
        )
        assertTrue(
          hash.length == 16,
          text.contains(s"hash: $hash"),
          text.contains(s"cache-epoch: \"$hash\"") || text.contains(s"cache-epoch: $hash"),
        )
      ),
    ),
    section("Backends")(
      md"""
```scala
zipxCache := CacheBackend.LocalDir
zipxCache := CacheBackend.BazelRemoteSidecar(RemoteCacheProof.image, RemoteCacheProof.port)
zipxCache := CacheBackend.managedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")
```

- **LocalDir**: persist local cache dirs and `target/` with `actions/cache` inside the generated `zipx-sbt-setup`
  composite. One job owns the **build snapshot** and saves it; every other sbt job restores it and saves nothing (see
  **Who saves** below). Keys are OS + JDK + epoch + `build` + run id + job id; restore-keys prefer this run's build
  saves, then the epoch's latest build save, then (GitTags/Script) the release epoch's
  (`steps.*.outputs.release`) so the first post-tag PR can warm from the tag build, then any older OS+JDK sbt cache.
  No infrastructure. GitHub scopes cache entries to the branch that saved them; other PRs restore from the **default
  branch**. With `zipxSkipMergedPrPush`, Verify does not run on the merge push, so by default a minimal
  `cache-rehydrate` job recreates a main-scoped save (see **Verify**). You cannot copy a PR cache onto main via the
  API.
- **BazelRemoteSidecar**: pinned `buchgr/bazel-remote-cache` as a job service. The published port is gRPC.
  Proof pins live in `RemoteCacheProof` (docs, planner tests, and `RemoteCacheItSpec` share them).
- **ManagedRemote**: any remote URI. `grpc` and `grpcs` use sbt's gRPC store. `http` and `https` use bazel's HTTP API
  (`/ac/<hash>`, `/cas/<hash>`). The URI is written into `ZIPX_REMOTE_CACHE` unchanged. BuildBuddy, EngFlow, and
  NativeLink are the usual `grpcs` endpoints; auth is a named repository secret. Plaintext `grpc://` and `http://`
  warn on sbt 2.1. That warning is the scheme, not a failed cache. `grpcs://` and `https://` do not warn. TLS
  certificate files are for gRPC: `https` plus `remoteCacheTls*` is an sbt error. This is the path for
  **CI-hydrated caches that developers reuse** (see **Remote cache for teams**).

The remote-cache transport is bundled with zipx. For remote backends zipx also sets `Global / cacheVersion` from
`(JDK, OS)` so heterogeneous runners cannot poison the shared cache. **`CacheEpoch.ShipCatalog` does not fold the Ship
hash into that value.** A bump already changes that module's `version`, which is a digest input, so only that module's
remote entries miss. Remote backends pass `cache-mode: off` to `zipx-sbt-setup` (the remote store is the persistence);
LocalDir passes `save` or `restore`, so the composite runs epoch-keyed `actions/cache` or `actions/cache/restore`.
""",
      exampleValue {
        val local = DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cache = CacheBackend.LocalDir),
        )
        val sidecar = DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cache = RemoteCacheProof.sidecar),
        )
        val remote = DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cache = CacheBackend.managedRemote("grpcs://cache.example", "CACHE_KEY")),
        )
        local + "\n---\n" + sidecar + "\n---\n" + remote
      }.assert(yaml =>
        assertTrue(
          yaml.contains("uses: ./.github/actions/zipx-sbt-setup"),
          RemoteCacheProof.sidecarYamlMustContain.forall(yaml.contains),
          yaml.contains(s"${RemoteCacheProof.envUri}: grpcs://cache.example") ||
            yaml.contains(s"${RemoteCacheProof.envUri}: \"grpcs://cache.example\""),
          yaml.split("---").toList match
            case local :: sidecar :: managed :: Nil =>
              local.contains("cache-mode: save") &&
              !local.contains("actions/cache") &&
              // Quoted, since a bare `off` can parse as a YAML boolean.
              sidecar.contains("cache-mode: \"off\"") &&
              managed.contains("cache-mode: \"off\"") &&
              !sidecar.contains("actions/cache") &&
              !managed.contains("actions/cache") &&
              sidecar.contains(RemoteCacheProof.image)
            case _ => false,
        )
      ),
    ),
    section("Who saves")(
      md"""
Every sbt job restores the LocalDir build snapshot. Only its **owner** saves one. The builtin `test` owns it on PRs
and direct pushes, and `cache-rehydrate` owns it on a merge push, where Verify is skipped. `testLayers` saves once per
wave, so each wave warms the next through the same-run key. Graph test jobs, coverage, publish, docker, and deploy
jobs restore through `actions/cache/restore` and never save. The `snapshots` job waits on `verify` (every Verify job,
including the cache owner) and, on a merge push, on `cache-rehydrate`, which owns that push's save. It restores this
run's save and only packages and uploads. Every other sbt Publish job waits on `cache-rehydrate` the same way, unless
it takes `SnapshotSource.Prior`: then it starts beside `cache-rehydrate` and restores the newest earlier save. That
suits a docker image matrix, whose jobs compile their own modules anyway. A `zipx-release.yml` job restores
and never saves: its jars carry release numbers, which no PR build would hit.

A pinned snapshot keeps the cache honest without making it cold. Only projects that depend on a `-SNAPSHOT` re-resolve
each session. Every generated sbt job also sets `COURSIER_TTL: 0s`, which revalidates only changing artifacts, so every
other `update` and every content-addressed compile still hits. See **Snapshots and releases**.

```scala
Capability.test                                                  // LocalCacheMode.Save: the default owner
Capability.testGraph                                             // Restore: its jobs compile disjoint slices
myCheck.withLocalCache(LocalCacheMode.Save)                      // take ownership in place of the builtin test
Capability.dockerGraph.withSnapshotSource(SnapshotSource.Prior)  // on a merge push, start beside cache-rehydrate
```

A capability that replaces the builtin `test` by name decides for itself. Coverage never saves, because an
instrumented snapshot is not the build: generate refuses a coverage capability with `LocalCacheMode.Save` or one named
`test` (see **Verify**, "Coverage"). It also refuses two owners, and an owner that would save once per job
(Graph-scoped, matrixed, or fanned out per target).

**Budget.** GitHub gives a repository 10 GB of `actions/cache` and evicts the least recently used entries past that.
One save per run means the quota holds about `10 GB / snapshot size` runs, and the default branch's snapshot stays
fresh because every PR restores it. When every sbt job saved its own entry, one PR run of a small nine-job build wrote
about 3 GB, and a single wave of four PRs evicted the default branch's snapshot.
""",
      exampleValue {
        DocsRender.jobs("test", "publish")(Capability.test, Capability.publish.copy(gate = Gate.Always))
      }.assert(yaml =>
        yaml.split("(?m)^\\s*publish:").toList match
          case test :: publish :: Nil =>
            assertTrue(test.contains("cache-mode: save"), publish.contains("cache-mode: restore"))
          case _ => assertTrue(false)
      ),
    ),
    section("Skipping a bad restore")(
      md"""
`cleanFull` deletes declared outputs, `target/out`, and the disk action cache (`~/Library/Caches/sbt/v2` on macOS,
`~/.cache/sbt/v2` on Linux). It does not delete `~/.cache/coursier` or `~/.sbt`. Do not chmod that cache: its blobs
are mode `0444`, and `cleanFull` deletes the directory from a writable parent. When a catalog snapshot is pinned, the
`clean` inside `cleanFull` also clears sbt's in-memory resolution cache. A qualifier snapshot is `forceUpdatePeriod`,
not that hook (see **Snapshots and releases**). `actions/cache` still restores `~/.sbt`, `~/.cache/coursier`,
`~/.cache/sbt`, and `target` before sbt starts, and the save owner writes them back. Rolling `zipxCacheEpoch` does
not drop them either:
a baked epoch's last restore-key is the OS+JDK prefix with no epoch in it, so a miss restores the newest entry under
that prefix.

A PR labeled **`purge`** skips that restore for the run. The save owner still saves when the job succeeds, and the
save has no restore-keys: the primary key includes the run id, so the lookup misses, and the cache action's post
step writes the fresh snapshot. Restore-only jobs in the same run skip the cache step, so they do not pull the old
entry while that save is in flight. `cache-mode: off` is the wrong tool for this. That run starts empty, and the next
run restores the same entry, because nothing newer was saved.

```scala
zipxCachePurgeLabel := Some("purge")  // default
```

The label is read from the `pull_request` payload, the same way `clean` is. `ci.yml` does not run on `labeled`, and
a rerun repeats the payload the run started with. A push carries no PR labels, so `cache-rehydrate` on a merge still
restores. The input is on that job anyway, because it is a save owner. A PR cannot replace the default branch's
entry. Other branches keep restoring `main` until a main-scoped save writes a newer one. `None` disables the check.
""",
      exampleValue {
        given PlanConfig = config.copy(
          cachePurgeLabel = PlanConfig.cachePurgeLabel("purge"),
          skipMergedPrPush = true,
        )
        DocsRender.jobs("test", "publish", "cache-rehydrate")(
          Capability.test,
          Capability.publish.copy(gate = Gate.Always),
        )
      }.assert { yaml =>
        val needle = "contains(github.event.pull_request.labels.*.name, 'purge')"
        assertTrue(
          yaml.contains("github.event_name == 'pull_request'"),
          yaml.contains("cache-mode: save"),
          yaml.contains("cache-mode: restore"),
          yaml.contains("cache-rehydrate:"),
          yaml.split(java.util.regex.Pattern.quote(needle), -1).length - 1 == 3,
        )
      },
    ),
    section("Sbt image")(
      md"""
`zipxSbtImage := true` runs every sbt job in one pinned image. Default off. The first run in the image misses every
cache entry that was saved under `/home/runner`.

The pin is the light `sbtscala/scala-sbt` tag for the major in `zipxJavaVersion`. The version string is the tag and
the multi-arch manifest digest, so a JDK patch and a digest move are both updates:

```scala
Pin(
  "sbt-image",
  "sbtscala/scala-sbt",
  "eclipse-temurin-21.0.12_8_2.x@sha256:<manifest>",
)
```

A fat tag (`<jdk>_<sbt>_<scala>`) is not the pin. It warms one Scala compiler into the image, and
`project/build.properties` still picks sbt. The image is the toolchain: JDK, sbt, `curl`, `git`, `tar`. It does not
contain `~/.sbt`, `~/.cache/sbt`, `~/.cache/coursier`, `target`, or a token. Those stay the LocalDir snapshot,
restored into `/home/sbtuser`. Jobs set `HOME` and `-Duser.home` there. Actions overrides `HOME`, so a `~` path is
not that directory and the cache paths are absolute. `setup-sbt`'s own disk cache stays off.

`actions/setup-java`, `sbt/setup-sbt`, `actions/setup-node`, and `coursier/setup-action` are omitted on those jobs.
The composite still restores and saves the snapshot.

When a capability sets `withNodeVersion`, version updates are on (they need `cs`), or a job publishes images (it
needs the Docker client), zipx writes `.github/zipx-env/Dockerfile` `FROM` that pin and bakes only those tools. The
published tag is `ghcr.io/<owner>/<repo>/zipx-env:df-<hash of the Dockerfile>`. A `zipx-env-image` job builds and
pushes only when the tag is missing. The hash moves when the sbt pin, the Node pin, or the `cs` pin moves. `latest`
and `lts` are not baked: pin `Pin("sbt-image", "node", "<x.y.z>")`. Two capabilities asking for two Node versions is
a generate error. A docker job mounts the runner socket. The image has the client, not a daemon. `inContainer` is
refused while this setting is on: the shared image is already the container.
""",
      exampleValue {
        val digest  = "ab" * 32
        val version = s"eclipse-temurin-21.0.12_8_2.x@sha256:$digest"
        val pin     = Pin(
          PinFeedName.unsafeMake("sbt-image"),
          SbtImage.ImageId,
          DepVersion.unsafeMake(version),
          None,
          None,
        )
        SbtImage.resolve(config.javaVersion, Seq(pin), Nil, coursier = false, docker = false) match
          case Left(message) => message
          case Right(plan)   =>
            DocsRender.job("test")(Capability.test)(using libGraph, config.copy(sbtImage = Some(plan)))
      }.assert { yaml =>
        assertTrue(
          yaml.contains("sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_2.x@sha256:"),
          yaml.contains("baked-toolchain: \"true\""),
          yaml.contains("cache-home: /home/sbtuser"),
          yaml.contains("HOME: /home/sbtuser"),
          !yaml.contains("actions/setup-java"),
        )
      },
    ),
    section("Action pins")(
      md"""
Generated workflows use **commit-SHA pins** (not floating `@v4` tags), with `# vX.Y.Z` comments for readability.

Catalog `Action` vals overlay jar defaults. Full guide: **Action pins** (overlay, `zipxActionUpdate`, leftover YAML,
jar defaults from the last zipx compile).
"""
    ),
  )
end Caching
