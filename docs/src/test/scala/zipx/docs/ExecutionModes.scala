package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Aggregate, Layer, and Graph: the main CI-cost lever. */
object ExecutionModes extends DocSpecSuite:

  def doc = page("Execution modes")(
    md"""
**This is the main lever for CI cost.** sbt already batches work through root `.aggregate` in one JVM. Graph mode pays
for many JVMs to get per-module `needs`, affected gating, and Scala matrix isolation. Pick the mode that matches the
cost/isolation tradeoff — not "always fan out."

| Mode | Test / publish / docker | Deploy | Best for |
|---|---|---|---|
| **Aggregate** (default) | 1 per stage (Verify = root `sbt test`) | **1 per Target** (modules batched) | Cost; libraries; dogfood |
| **Layer** | 1 per toposort wave | Same as Aggregate-by-target | Ordered waves without N JVMs |
| **Graph** | 1 per module (± matrix / targets) | 1 per module × Target | Affected PRs; matrix isolation |
""",
    section("When to use which")(
      md"""
- **Aggregate** — libraries and this repo's dogfood. One root `test` job, one `publish` / `ZipxCentral.release`.
- **Layer** — dependency-ordered waves (L0 → L1 → L2) with fewer sbt starts than Graph. Inspect with `zipxGraph` /
  `zipxPublishOrder`.
- **Graph** — affected-only PRs, per-module Scala matrix, or max concurrency with a warm action cache. See
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
    section("Planner shape")(
      md"""
Aggregate Verify is one Once job. Graph Verify fans out per module:
""",
      exampleValue {
        val agg   = Planner.plan(libGraph, List(Capability.test), config)
        val graph = Planner.plan(libGraph, List(Capability.testGraph), config)
        (agg.jobs.keySet.toList, graph.jobs.keys.filter(_.startsWith("test-")).toList.sorted)
      }.assert { case (aggIds, graphIds) =>
        assertTrue(aggIds == List("test"), graphIds == List("test-api", "test-schema", "test-service"))
      },
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

- **Aggregate:** roughly 2 sbt starts on PR (`test` + optional gates), plus one publish/release job on tags
- **Graph:** one test job per module × Scala matrix, plus affected setup, plus per-module publish on tags
"""
    ),
  )
end ExecutionModes
