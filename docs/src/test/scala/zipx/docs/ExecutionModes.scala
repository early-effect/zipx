package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zio.test.*

/** Aggregate, Layer, and Graph: the main CI-cost lever. */
object ExecutionModes extends DocSpecSuite:

  def doc = page("Execution modes")(
    md"""
**This is the main CI cost and isolation lever.** Aggregate is not "rebuild the world in one job." On sbt 2.x, a root
`.aggregate` `sbt test` already parallelizes independent subprojects, incrementally recompiles only invalidated
sources, and reruns only suites that failed, are new, or transitively depend on recompiled code (including code in
another project). Task results are content-addressed and **survive JVM restarts**: sbt's machine-wide cache, plus
zipx's **epoch-keyed** CI restore (`zipxCacheEpoch`), means a cold runner still hits prior task/test results across
pushes in the same epoch. Remote cache backends push that reuse across machines. You get a large share of "don't redo
unaffected work" from **one Aggregate job**, without paying for N runners. Pair that with a CI-hydrated remote cache
so teammate laptops share the same digests (see **Remote cache for teams**).

Graph mode buys a different kind of selectivity: path-based **affected** gating, per-module `needs`, Scala matrix
isolation, and independent logs/statuses. Pick Graph when the **workflow** needs those boundaries, not merely to avoid
compile/test work that sbt (and the restored/remote cache) can already skip.

| Mode | Test / publish / docker | Deploy | Best for |
|---|---|---|---|
| **Aggregate** (default) | 1 per stage (Verify = root `sbt test`) | **1 per Target** (modules batched) | Most repos, including multi-service monorepos: one stage, still incremental |
| **Layer** | 1 per toposort wave | Same as Aggregate-by-target | Ordered waves without N JVMs |
| **Graph** | 1 per module (± matrix / targets) | 1 per module × Target | Per-module / multi-env boundaries; path gating; matrices |
""",
    section("Two kinds of affected")(
      md"""
sbt and zipx answer different questions:

| Layer | Question | Who decides |
|---|---|---|
| **sbt 2 (inside Aggregate)** | Which sources and test suites need work, given cached task digests? | Incremental compiler + incremental `test` + cross-run task cache |
| **zipx Graph** | Which GitHub jobs should run at all for *this* PR diff? | `git diff` → owning module → reverse-dep closure |

Aggregate always starts the stage command (one root `test` job). That is fine: after zipx restores the epoch cache
(or a remote cache hits), sbt may compile almost nothing and rerun almost no suites even on a cold JVM. Graph can
skip entire module jobs when their reverse-dep closure is untouched, and it can show a green check per module. Use
[`testFull`](https://www.scala-sbt.org/2.x/docs/en/reference/sbt-test.html) (`zipxTestTask := "testFull"`) when CI must
run every suite every time, uncached. See **Caching** for LocalDir vs remote backends.
"""
    ),
    section("When to use which")(
      md"""
zipx is built for **all sorts of sbt repos**, and especially **monorepos**: the same typed capabilities scale from a
single library to many services. Modes choose *how* work is scheduled in GitHub Actions, not whether zipx understands
your graph.

- **Aggregate** — the default, and often enough even for multi-service monorepos. One root `test` job, one
  `publish` / `ZipxCentral.release` (modules batched where that makes sense). Lean on sbt 2 incrementality and
  epoch/remote caching; escalate only when the **workflow** needs Graph's boundaries.
- **Layer** — dependency-ordered waves (L0 → L1 → L2) with fewer sbt starts than Graph. Inspect with `zipxGraph` /
  `zipxPublishOrder`.
- **Graph** — when CI itself needs per-module or per-destination isolation: multi-environment deploys, independent
  approvals/logs/status, per-module Scala matrices, or path-based affected gating at the job level. See
  [`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo).
"""
    ),
    section("API cheat sheet")(
      md"""
```scala
Capability.test          // Once: root zipxTestTask (default "test")
Capability.testJoined    // Aggregate escape hatch: join module/<testTask>
Capability.publish
Capability.docker
Capability.deploy(participates, command, targets)

Capability.testLayers / publishLayers / dockerLayers
Capability.testGraph / publishGraph / dockerGraph
Capability.deployGraph(participates, command, targets)

ZipxCentral.release                              // Aggregate Central
ZipxCentral.publishSigned + ZipxCentral.releaseOnce  // Graph + staging
```

Same-name override: a user capability whose `name` matches a built-in **replaces** it.
"""
    ),
    section("Aggregate vs Graph job shape")(
      md"""
```scala
zipxCapabilities += Capability.test        // one root job
// or
zipxCapabilities += Capability.testGraph   // one job per module
```
""",
      exampleValue {
        DocsRender.jobs("test")(Capability.test) + "\n---\n" +
          DocsRender.jobs("test-schema", "test-api", "test-service")(Capability.testGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("test:"),
          yaml.contains("test-schema:"),
          yaml.contains("test-api:"),
          yaml.contains("test-service:"),
          yaml.contains("run: sbt 'test'"),
          yaml.contains("schema/test"),
        )
      ),
    ),
    section("Modules batch; targets do not")(
      md"""
For docker/deploy, participants still come from the graph. **Targets** (GitHub `environment:` and per-destination
`env:`) always fan out: you cannot merge staging and prod into one job without losing independent approval.

- Aggregate deploy → `deploy-staging`, `deploy-prod` (modules joined inside each)
- Graph deploy → `deploy-service-staging`, `deploy-service-prod`
"""
    ),
    section("Cost intuition")(
      md"""
For a 4-module library with cross-Scala and release publish:

- **Aggregate:** roughly 2 sbt starts on PR (`test` + optional gates), plus one publish/release job on tags. Each
  start can still skip unaffected compile/test work when the epoch (or remote) task cache hits, including after a
  cold JVM.
- **Graph:** one test job per module × Scala matrix, plus affected setup, plus per-module publish on tags. You pay
  for more runners and JVM starts to get per-module skip/status/matrix isolation.
"""
    ),
  )
end ExecutionModes
