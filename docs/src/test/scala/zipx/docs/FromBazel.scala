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
If you adopted Bazel because sbt CI felt unsafe or slow, that impulse was understandable. Many teams found peace in
hermeticity talk and remote cache, then discovered a quieter cost: a **second graph** in BUILD files while Scala
engineers still thought in modules and `dependsOn`.

zipx is not Bazel-parity, and it does not ask you to pretend the second graph never happened. It offers a **different
strategy** for teams that already have the truth in sbt: one graph, generated CI, content-addressed reuse. Keep the
vocabulary you learned; leave the duplicate edges behind.

For the broader recovery story (disconnected CI and acceleration layers), see **Why zipx**. For shared digests across
laptops, see **Remote cache for teams**.
""",
    section("Shared vocabulary, kinder boundaries")(
      md"""
We speak Bazel-fluent on purpose. The mapping helps you translate without re-litigating the past:

| Bazel | sbt / zipx |
|---|---|
| Action | Task (executable step with digests) |
| Target | Project / module |
| Remote cache | sbt 2 action cache over Bazel-compat gRPC |
| Remote execution | Out of scope; use Graph for more runners |

In Bazel, many small packages often improve hit rates because the **target** *is* the cache boundary. In sbt 2,
compile and test already invalidate at class and suite digests **inside** a module. You do not need to shatter the
repo into packages just to feel cacheable. Reach for zipx Graph when you need **job** isolation or path-affected PRs,
not when you are only chasing hits.
"""
    ),
    section("What you maintain (before / after)")(
      md"""
**What hurt:** BUILD files restating edges, plus CI glue, plus optional remote cache or RBE config. Adding a library
meant updating more than one world.

**What heals:** modules in `build.sbt`, typed `zipxCapabilities`, regenerate the workflow. One graph again.

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
    section("A gentle migration path")(
      md"""
You do not have to boil the ocean on day one:

1. Add `sbt-zipx`; keep Aggregate defaults (one calm Verify job).
2. `zipxWorkflowGenerate` / `zipxWorkflowCheck` so drift cannot sneak back in.
3. Opt into `ManagedRemote` or `RemoteCacheProof.sidecar` when the team is ready; measure hits (`RemoteCacheItSpec` is
   the in-repo proof).
4. Use Graph only when wall clock or isolation truly needs per-module jobs, matrices, or multi-env deploy.

That is the escape hatch as a ladder: safety first, fan-out when earned.
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
    section("Easier to review, easier to help")(
      md"""
One edit locus (`build.sbt`), checkable contracts (`zipxWorkflowCheck`, Specular docs-as-tests), regeneratable YAML,
and named packs give humans and AI assistants the same gift: they can change the build without keeping a second
graph honest. When the graph *is* the CI, nobody has to babysit a matrix that forgot a module.
"""
    ),
  )
end FromBazel
