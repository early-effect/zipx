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
sbt 2.x has a machine-wide, content-addressed build cache. zipx persists it in CI. The cache key uses a
**commit-stable epoch** (`zipxCacheEpoch`, defaulting to `version`) so every push within a PR reuses the cache.
Cutting a release tag rolls the epoch. This pairs with
[`sbt-dynver-ci`](https://github.com/early-effect/sbt-dynver-ci).
""",
    section("Backends")(
      md"""
```scala
zipxCache := CacheBackend.LocalDir                                  // default: epoch-keyed actions/cache
zipxCache := CacheBackend.BazelRemoteSidecar("buchgr/bazel-remote:latest", 9092)
zipxCache := CacheBackend.ManagedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")
```

- **LocalDir** — persist local cache dirs and `target/` with `actions/cache`. Primary key is OS + JDK + epoch + run id +
  job id; restore-keys prefer the same run, then the epoch. No infrastructure.
- **BazelRemoteSidecar** — `buchgr/bazel-remote` as a job service; shared across the run via Bazel gRPC.
- **ManagedRemote** — point sbt at BuildBuddy / EngFlow / NativeLink; auth header from a named repository secret.

The remote-cache transport is bundled with zipx. For remote backends zipx also sets `Global / cacheVersion` from
`(JDK, OS)` so heterogeneous runners cannot poison the shared cache.
""",
      exampleValue {
        val local  = Planner.plan(libGraph, List(Capability.test), config.copy(cache = CacheBackend.LocalDir))
        val remote = Planner.plan(
          libGraph,
          List(Capability.test),
          config.copy(cache = CacheBackend.ManagedRemote("grpcs://cache.example", "CACHE_KEY")),
        )
        (
          local.jobs("test").steps.exists(_.uses.exists(_.contains("actions/cache"))),
          remote.jobs("test").env.get("ZIPX_REMOTE_CACHE"),
        )
      }.assert { case (hasCacheAction, remoteUri) =>
        assertTrue(hasCacheAction, remoteUri.contains("grpcs://cache.example"))
      },
    ),
    section("Action pins")(
      md"""
Generated workflows use **commit-SHA pins** (not floating `@v4` tags). Override via `zipxActions` to bump without a
zipx release:

```scala
zipxActions := ActionPins.Defaults.copy(
  checkout = "actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0", // v7.0.0
)
```
"""
    ),
  )
end Caching
