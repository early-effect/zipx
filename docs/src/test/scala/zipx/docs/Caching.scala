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
**commit-stable epoch** (`zipxCacheEpoch`, defaulting to `version`), so every push within a PR reuses prior hits.
Cutting a release tag rolls the epoch. Remote backends make the same story stronger across machines, including
**developer laptops** when CI hydrates a shared store (see **Remote cache for teams**). This pairs with
[`sbt-dynver-ci`](https://github.com/early-effect/sbt-dynver-ci).

zipx wires cache into **generated jobs** (same planner as topology). It is not a standalone acceleration appliance: the
goal is CI-from-graph plus content-addressed reuse, not a second product to configure beside hand-maintained YAML.
""",
    section("Backends")(
      md"""
```scala
zipxCache := CacheBackend.LocalDir
zipxCache := CacheBackend.BazelRemoteSidecar(RemoteCacheProof.image, RemoteCacheProof.port)
zipxCache := CacheBackend.ManagedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")
```

- **LocalDir** — persist local cache dirs and `target/` with `actions/cache`. Primary key is OS + JDK + epoch + run id +
  job id; restore-keys prefer the same run, then the epoch, then (for dynver-ci style `*-ci` / `*-SNAPSHOT` epochs) the
  prior release epoch so the first post-tag PR can warm from the tag build, then any older OS+JDK sbt cache. No
  infrastructure.
- **BazelRemoteSidecar** — pinned `buchgr/bazel-remote-cache` as a job service; shared across the run via Bazel gRPC.
  Proof pins live in `RemoteCacheProof` (docs, planner tests, and `RemoteCacheItSpec` share them).
- **ManagedRemote** — point sbt at BuildBuddy / EngFlow / NativeLink; auth header from a named repository secret.
  This is the path for **CI-hydrated caches that developers reuse** (see **Remote cache for teams**).

The remote-cache transport is bundled with zipx. For remote backends zipx also sets `Global / cacheVersion` from
`(JDK, OS)` so heterogeneous runners cannot poison the shared cache. Remote backends omit `actions/cache` (the gRPC
store is the persistence); LocalDir uses epoch-keyed `actions/cache` instead.
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
          config.copy(cache = CacheBackend.ManagedRemote("grpcs://cache.example", "CACHE_KEY")),
        )
        local + "\n---\n" + sidecar + "\n---\n" + remote
      }.assert(yaml =>
        assertTrue(
          yaml.contains("actions/cache"),
          RemoteCacheProof.sidecarYamlMustContain.forall(yaml.contains),
          yaml.contains(s"${RemoteCacheProof.envUri}: grpcs://cache.example") ||
            yaml.contains(s"${RemoteCacheProof.envUri}: \"grpcs://cache.example\""),
          // Sidecar / managed sections must not rely on actions/cache for the remote half: managed block has no cache step
          yaml.split("---").toList match
            case local :: sidecar :: managed :: Nil =>
              local.contains("actions/cache") &&
              !sidecar.contains("actions/cache") &&
              !managed.contains("actions/cache") &&
              sidecar.contains(RemoteCacheProof.image)
            case _ => false
          ,
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
