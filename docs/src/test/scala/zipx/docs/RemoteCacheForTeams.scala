package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Why CI-hydrated remote cache is a team win (beyond faster CI alone). */
object RemoteCacheForTeams extends DocSpecSuite:

  def doc = page("Remote cache for teams")(
    md"""
**CI should make local builds faster, not only green checks.** On sbt 2.x, task and test results are content-addressed
and remote-ready. When zipx CI runs `sbt test` (or publish) against a shared remote cache, it **hydrates** digests for
the commits your team already built. The next laptop (or the next PR job) downloads those results instead of
recompiling and retesting the same bytecode.

That reverses the usual monorepo tax: every morning you pull a dozen teammates' changes and spend the first hour
building *their* code. With a CI-hydrated cache, most of that work is already a hit. See
[sbt's caching overview](https://www.scala-sbt.org/2.x/docs/en/concepts/caching.html) for the pure-function mental
model; this page is the zipx / team angle.

Remote cache is **necessary but not sufficient**: topology (jobs, `needs`, gates) still comes from the sbt graph. A
cache product alone leaves disconnected CI as a second source of truth (see **Why zipx**).
""",
    section("What we claim / what we do not")(
      md"""
| Claim | Status |
|---|---|
| Content-addressed reuse of compile/test across machines | Yes (sbt 2 + Bazel-compat gRPC) |
| CI hydrates; developers and later jobs Get | Yes (ManagedRemote / long-lived sidecar) |
| JDK/OS partitioned via `cacheVersion` | Yes (zipx wiring) |
| Sandboxed hermetic builds / remote **execution** | No (use Graph for job fan-out) |

Live Put/Get is proven by `RemoteCacheItSpec` (Testcontainers + the same `RemoteCacheProof` pins as the YAML below).
"""
    ),
    section("What gets shared")(
      md"""
sbt 2 caches **task results** (and, for remote backends, declared file outputs). Incremental `compile` and `test`
already invalidate at fine grain:

- **Compile** follows Zinc's class/API graph inside a subproject.
- **Test** uses hermetic suite digests over transitive bytecode (not timestamps), so a successful suite can be skipped
  across machines when inputs match.

You do **not** need to explode the repo into Bazel-style package-level targets to benefit. In Bazel, the cache unit is
often the `BUILD` target; many small targets improve hit rates because that *is* the boundary. In sbt 2, the boundary
for compile/test is already class/suite digests **inside** your existing modules. Aggregate CI plus a remote cache is
enough for most libraries and multi-service monorepos. Escalate to Graph when you need job-level fan-out or
multi-environment isolation (see **Execution modes**), not merely to "make caching work."
"""
    ),
    section("CI hydrates; developers pull")(
      md"""
Typical loop with a **ManagedRemote** (or long-lived sidecar) backend:

1. A PR or `main` job runs Aggregate `sbt test` with `ZIPX_REMOTE_CACHE` set.
2. Misses compile/test onsite; successes write action-cache entries and outputs to the remote store.
3. Teammates (and later CI jobs) with the same JDK/OS `cacheVersion` and matching digests **Get** those entries.
4. Local `sbt test` after `git pull` shows high cache %: cold JVM, warm digests.

**LocalDir** (default) already helps *within* GitHub Actions via epoch-keyed `actions/cache`. It does not share across
developer laptops. Remote backends are how the win leaves the datacenter.

zipx folds `(JDK, OS)` into `Global / cacheVersion` for remote backends so a heterogeneous runner pool cannot poison
the store. Point laptops at the same endpoint (and credentials) when you want local hits from CI hydration.
"""
    ),
    section("Cache is not remote execution")(
      md"""
Bazel's Remote Execution API covers both **cache** and **execute**. sbt 2 (and zipx) use the **cache** side today:
reuse completed work; when cold, the machine that scheduled the task still runs it.

| | Remote **cache** | Remote **execution** |
|---|---|---|
| Question | Has this digest been done? | Run this action on a worker pool |
| sbt 2 / zipx | Yes (Bazel-compat gRPC) | Not pursued; use Graph for job fan-out |
| Team win | CI hydrates; everyone skips redo | Wall-clock via many workers |

If wall clock is still bound by *many independent misses* on a huge PR, prefer **Graph** (more runners, path-based
affected jobs) plus remote cache, rather than rewriting the build as package targets. True task-level remote
execution would need hermetic action workers inside sbt; that is build-tool work, not a zipx toggle.
"""
    ),
    section("Turn it on")(
      md"""
Backends and generated YAML live on the **Caching** page. The short version:

```scala
zipxCache := CacheBackend.ManagedRemote("grpcs://cache.example", "CACHE_KEY")
// or proof-pinned sidecar:
zipxCache := RemoteCacheProof.sidecar
```

Then regenerate the workflow, add the repository secret, and confirm CI exports `ZIPX_REMOTE_CACHE`. For a
zero-infra start, keep **LocalDir** and graduate to ManagedRemote when the team wants laptop reuse of CI digests.
""",
      exampleValue {
        val managed = DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cache = CacheBackend.ManagedRemote("grpcs://cache.example", "CACHE_KEY")),
        )
        val sidecar = DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cache = RemoteCacheProof.sidecar),
        )
        managed + "\n---\n" + sidecar
      }.assert(yaml =>
        assertTrue(
          yaml.contains(s"${RemoteCacheProof.envUri}: grpcs://cache.example") ||
            yaml.contains(s"${RemoteCacheProof.envUri}: \"grpcs://cache.example\""),
          RemoteCacheProof.sidecarYamlMustContain.forall(yaml.contains),
          yaml.contains(RemoteCacheProof.envHeader) || yaml.contains("secrets.CACHE_KEY"),
        )
      ),
    ),
  )
end RemoteCacheForTeams
