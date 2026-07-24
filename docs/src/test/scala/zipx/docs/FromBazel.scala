package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Migrating from Bazel: different strategy that fits sbt better. */
object FromBazel extends DocSpecSuite:

  def doc = page("From Bazel")(
    md"""
This page is **not** Bazel-parity. zipx uses a **different strategy** that fits Scala teams who already have the truth
in sbt: one graph, generated CI, content-addressed reuse. We agree with Bazel-fluent vocabulary, then refuse the
second BUILD graph.

For positioning vs disconnected CI and acceleration layers, see **Why zipx**. For team hydration, see **Remote cache
for teams**.
""",
    section("Vocabulary")(
      md"""
| Bazel | sbt / zipx |
|---|---|
| Action | Task (executable step with digests) |
| Target | Project / module |
| Remote cache | sbt 2 action cache over Bazel-compat gRPC |
| Remote execution | Out of scope; use Graph for more runners |

In Bazel, many small packages often improve hit rates because the **target** is the cache boundary. In sbt 2,
compile/test already invalidate at class/suite digests **inside** a module. Exploding the graph for cache hit rate
is usually the wrong lever; escalate to zipx Graph when you need **job** isolation or path-affected PRs.
"""
    ),
    section("What you maintain (before / after)")(
      md"""
**Before (typical):** BUILD files restating edges, plus CI glue, plus optional remote cache/RBE config.

**After:** modules in `build.sbt`, typed `zipxCapabilities`, regenerate workflow. No second graph.

| Maintenance surface | Disconnected / Bazel-shaped | zipx Aggregate |
|---|---|---|
| Add a library module | Edit BUILD (and usually CI) | Edit `build.sbt`; regenerate |
| Verify shape | Often N jobs or a matrix you list | One `test` job |
| Cache | Separate product or RBE | Same planner (`RemoteCacheProof` / ManagedRemote) |
""",
      exampleValue {
        val agg = DocsRender.body(Capability.test)(using libGraph, config)
        val pub = DocsRender.job("publish")(Capability.publish)(using libGraph, config)
        agg + "\n---\n" + pub
      }.assert(yaml =>
        assertTrue(
          yaml.contains("test:"),
          !yaml.contains("test-schema:"),
          !yaml.contains("test-api:"),
          !yaml.contains("test-service:"),
          yaml.contains("publish:"),
          yaml.contains("schema") && yaml.contains("api"), // publish joins publishing modules
        )
      ),
    ),
    section("Sidecar and Graph escape hatch")(
      md"""
Migration checklist:

1. Add `sbt-zipx`; keep Aggregate defaults.
2. `zipxWorkflowGenerate` / `zipxWorkflowCheck`.
3. Opt into `ManagedRemote` or `RemoteCacheProof.sidecar`; measure hit rates (`RemoteCacheItSpec` is the in-repo proof).
4. Use Graph only if wall clock or isolation needs per-module jobs / matrices / multi-env deploy.
""",
      exampleValue {
        val sidecar = DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cache = RemoteCacheProof.sidecar),
        )
        val graph = DocsRender.jobs("test-schema", "test-api", "test-service")(Capability.testGraph)
        sidecar + "\n---\n" + graph
      }.assert(yaml =>
        assertTrue(
          RemoteCacheProof.sidecarYamlMustContain.forall(yaml.contains),
          yaml.contains("test-schema:"),
          yaml.contains("test-api:"),
          yaml.contains("test-service:"),
        )
      ),
    ),
    section("Why this shape works for AI and humans")(
      md"""
One edit locus (`build.sbt`), checkable contracts (`zipxWorkflowCheck`, Specular docs-as-tests), regeneratable YAML,
and named packs beat a second BUILD graph for both human review and AI-assisted changes. Agents do not need to keep
CI matrices in sync when the graph is the CI.
"""
    ),
  )
end FromBazel
