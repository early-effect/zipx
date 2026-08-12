package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Cache backends and action pins. */
object Caching extends DocSpecSuite:

  def doc = page("Caching")(
    md"""
sbt 2.x caches task results **across JVM runs** (content-addressed, machine-wide; remote backends also share declared
outputs). That is why Aggregate stays cheap on a cold CI runner: zipx restores the cache before `sbt test`, keyed by a
**commit-stable epoch** (`zipxCacheEpoch`, default `CacheEpoch.GitTags()`). Every push within a PR reuses prior hits;
cutting a release tag rolls the epoch **without regenerating** `ci.yml`. Remote backends make the same story stronger
across machines, including **developer laptops** when CI hydrates a shared store (see **Remote cache for teams**).
This pairs with [`sbt-dynver-ci`](https://github.com/early-effect/sbt-dynver-ci).

```mermaid
flowchart TD
  Push([1 · git push]) --> Restore[2 · restore epoch cache]
  Restore --> Sbt[3 · sbt test]
  Sbt --> Hits{4 · digest hits?}
  Hits -->|yes| Skip([skip compile and suites])
  Hits -->|no| Work([run work · write digests])
  Work --> Store[(LocalDir or remote)]
  Skip --> Store
  class Push,Restore,Sbt,Hits warn
  class Work sad
  class Skip,Store happy
```

Miss path (amber → red) pays compile/test and writes digests; hit path (green) reuses them. Both land in the same
backend: `LocalDir` via `actions/cache` inside `zipx-sbt-setup`, or a remote gRPC store. The restore key is the
commit-stable epoch
(`zipxCacheEpoch`), so PR pushes share hits and a release tag rolls a fresh namespace.

zipx wires cache into **generated jobs** (same planner as topology). It is not a standalone acceleration appliance: the
goal is CI-from-graph plus content-addressed reuse, not a second product to configure beside hand-maintained YAML.
""",
    section("Epoch strategies")(
      md"""
```scala
zipxCacheEpoch := CacheEpoch.GitTags()                 // default: resolve from git tags on the runner
zipxCacheEpoch := CacheEpoch.GitTags(tagMatch = "v*")  // same, explicit match glob
zipxCacheEpoch := CacheEpoch.Fixed(version.value)      // bake at generate time (old behaviour)
zipxCacheEpoch := CacheEpoch.Script(myEpochShell)      // custom shell; must write epoch= and release=
```

**GitTags (default):** a `Resolve cache epoch` step runs after checkout (`fetch-depth: 0`, `fetch-tags: true`). On a
`v*` tag ref, epoch = release = tag without `v`. Otherwise the latest matching tag becomes release and epoch is
`$${release}-ci`. If local tags lag `origin` (or none match), the step emits an Actions `::warning` titled
`zipx cache epoch` so shallow/missing tags are obvious in the run summary.

**Fixed:** embeds a literal into the workflow at `zipxWorkflowGenerate` (useful for scripted tests or unusual versioning).
Prefer GitTags so post-tag PRs warm from the release cache without a regenerate commit.

**Script:** supply your own shell; write `epoch=` and `release=` to `$$GITHUB_OUTPUT`. Restore-keys use both outputs.
"""
    ),
    section("Backends")(
      md"""
```scala
zipxCache := CacheBackend.LocalDir
zipxCache := CacheBackend.BazelRemoteSidecar(RemoteCacheProof.image, RemoteCacheProof.port)
zipxCache := CacheBackend.managedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")
```

- **LocalDir**: persist local cache dirs and `target/` with `actions/cache` inside the generated `zipx-sbt-setup`
  composite. Primary key is OS + JDK + epoch + run id + job id; restore-keys prefer the same run, then the epoch, then
  the prior release epoch (Fixed: strip `-ci` / `-SNAPSHOT`; GitTags/Script: `steps.*.outputs.release`) so the first
  post-tag PR can warm from the tag build, then any older OS+JDK sbt cache. No infrastructure. GitHub scopes cache
  entries to the branch that saved them; other PRs restore from the **default branch**. With `zipxSkipMergedPrPush`,
  Verify does not run on the merge push, so by default a minimal `cache-rehydrate` job recreates a main-scoped save
  (see **Verify**). You cannot copy a PR cache onto main via the API.
- **BazelRemoteSidecar**: pinned `buchgr/bazel-remote-cache` as a job service; shared across the run via Bazel gRPC.
  Proof pins live in `RemoteCacheProof` (docs, planner tests, and `RemoteCacheItSpec` share them).
- **ManagedRemote**: point sbt at BuildBuddy / EngFlow / NativeLink; auth header from a named repository secret.
  This is the path for **CI-hydrated caches that developers reuse** (see **Remote cache for teams**).

The remote-cache transport is bundled with zipx. For remote backends zipx also sets `Global / cacheVersion` from
`(JDK, OS)` so heterogeneous runners cannot poison the shared cache. Remote backends turn off LocalDir cache in
`zipx-sbt-setup` (the gRPC store is the persistence); LocalDir passes `local-cache: true` so the composite runs
epoch-keyed `actions/cache`.
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
              local.contains("local-cache: \"true\"") &&
              !local.contains("actions/cache") &&
              sidecar.contains("local-cache: \"false\"") &&
              managed.contains("local-cache: \"false\"") &&
              !sidecar.contains("actions/cache") &&
              !managed.contains("actions/cache") &&
              sidecar.contains(RemoteCacheProof.image)
            case _ => false,
        )
      ),
    ),
    section("Action pins")(
      md"""
Generated workflows use **commit-SHA pins** (not floating `@v4` tags), with `# vX.Y.Z` comments for readability.

Prefer `.github/zipx/action-pins.yml` (Dependabot-friendly) over pasting SHAs into `build.sbt`. Full guide:
**Action pins** (resolve order, Dependabot, `zipxActionsPull`, sync workflow, jar defaults).
"""
    ),
  )
end Caching
