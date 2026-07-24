package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Positioning hub: strategy and ergonomics, not Bazel-parity or another cache appliance. */
object WhyZipx extends DocSpecSuite:

  def doc = page("Why zipx")(
    md"""
If your team has lived through hand-maintained CI matrices, a second BUILD graph, or a cache product that made
**tasks** faster while **humans** still juggled two sources of truth: you are in the right place.

zipx is not “almost Bazel,” and it is not another acceleration appliance bolted onto an opaque build. It is a gentler
path home: **one sbt graph** becomes generated GitHub Actions, with sbt 2’s content-addressed cache along for the ride.
Speed follows. The headline is **ergonomics**: fewer things to read, sync, and apologize for in review.

When you are ready for the migration stories, see **From Bazel**, **Caching**, and **Remote cache for teams**. Live
Put/Get is proven by `RemoteCacheItSpec` (same `RemoteCacheProof` pins as the YAML examples here).
""",
    section("Three paths, one that heals")(
      md"""
Most teams we meet are somewhere on this triangle. None of the first two are foolish; they were reasonable responses
to real pain. zipx is the recovery path that keeps your Scala mental model intact.

| Approach | Source of truth | What drifts (the bruise) |
|---|---|---|
| Disconnected CI | `build.sbt` **and** hand YAML | Module lists, `needs`, publish order |
| Bazel second graph | BUILD (+ often CI) | Edges restated outside sbt |
| **zipx** | `build.sbt` / `.dependsOn` | CI is derived; `zipxWorkflowCheck` catches drift early |

You do not need a second graph to feel safe. You need one honest graph, and a check that fails when CI lies.
"""
    ),
    section("Faster tasks are not the same as kinder CI")(
      md"""
Acceleration layers (Develocity-class remote build cache, build scans, predictive test selection) can be wonderful at
making an **existing** build feel snappier. They earn their keep. They are also a different category of tool: they
rarely remove the second maintenance surface of hand YAML or restated edges.

| | Acceleration layer | zipx |
|---|---|---|
| Primary artifact | Agent/plugin + server + scan UI | Planner + generated workflow from the sbt graph |
| What you maintain | Build + CI lists + cache config (often independent) | Modules + typed `zipxCapabilities`; CI is derived |
| How you know you’re safe | Faster greens / scan insights | `zipxWorkflowCheck` + docs-as-tests + live cache IT |
| Scope | Speed / observability of tasks | **CI topology + cache wiring + packs** as one system |

If mornings still start with “did we update the workflow?”, caching alone will not heal that. zipx retires disconnected
CI (and skips restating the graph in BUILD), then leans on sbt 2’s cache so Aggregate stays light to live with.
"""
    ),
    section("What you open on a good day")(
      md"""
**The hard years:** a hand `ci.yml` module matrix, or BUILD files plus CI glue, plus cache product config. Every “add a
module” meant a scavenger hunt.

**The recovery:** `build.sbt` and a small typed capability list. Generated `.github/workflows/ci.yml` is an **output**
you commit and drift-gate. Reviewers (and future you) see intent, not archaeology.

Default Aggregate Verify is **one** calm job (`test`), not one job per module. Reach for Graph when the **workflow**
needs isolation, not when you are only trying to make caching feel less lonely.
""",
      exampleValue {
        val aggregate = DocsRender.body(Capability.test, Capability.publish)
        val graph     = DocsRender.body(Capability.testGraph)
        aggregate + "\n---\n" + graph
      }.assert(yaml =>
        val parts = yaml.split("---").toList
        assertTrue(
          parts.length == 2,
          parts(0).contains("test:") || parts(0).contains("\ntest\n") || parts(0).linesIterator.exists(_.trim == "test:"),
          !parts(0).contains("test-schema:"),
          !parts(0).contains("test-api:"),
          parts(1).contains("test-schema:") || parts(1).contains("test-api:"),
          parts(0).contains("sbt 'test'") || parts(0).contains("sbt \"test\"") || parts(0).contains("run: sbt"),
        )
      ),
    ),
    section("Kind to humans and AI teammates")(
      md"""
A self-documenting, single-graph build is easier to nurture, whether the reviewer is a person or an agent:

- **One place to edit** when adding a module (no “also update workflow / BUILD”).
- **A contract that cares:** `zipxWorkflowCheck` + Specular DocSpecs fail when examples drift from planner output.
- **Narrow diffs:** capability and graph changes are typed Scala; generated YAML is regeneratable when you want a clean
  re-diff.
- **Named paved paths:** packs like `ZipxCentral` and `ZipxDocs` say what you meant, instead of a paste of secret/step
  soup.

The everyday loop: edit `build.sbt` → `zipxWorkflowGenerate` → the PR shows the graph and a regeneratable workflow.
That is the experience we are trying to give you back.
"""
    ),
    section("Cache that travels with the topology")(
      md"""
Remote backends are not a side confessional. The same planner that emits jobs also emits services and env. Shared proof
pins:
""",
      exampleValue {
        DocsRender.job("test")(Capability.test)(using
          libGraph,
          config.copy(cache = RemoteCacheProof.sidecar),
        )
      }.assert(yaml => assertTrue(RemoteCacheProof.sidecarYamlMustContain.forall(yaml.contains))),
    ),
  )
end WhyZipx
