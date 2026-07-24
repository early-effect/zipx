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
zipx is not “almost Bazel,” and it is not another sbt acceleration product. It wins with a **different strategy**:
one sbt graph → generated GitHub Actions + sbt 2 content-addressed cache. Speed follows; the headline is
**ergonomics** (what humans and AIs must read and keep in sync).

See also **From Bazel**, **Caching**, and **Remote cache for teams**. Live Put/Get is `RemoteCacheItSpec` (same
`RemoteCacheProof` pins as the YAML examples here).
""",
    section("Strategy triangle")(
      md"""
| Approach | Source of truth | What drifts |
|---|---|---|
| Disconnected CI | `build.sbt` **and** hand YAML | Module lists, `needs`, publish order |
| Bazel second graph | BUILD (+ often CI) | Edges restated outside sbt |
| **zipx** | `build.sbt` / `.dependsOn` | CI is derived; `zipxWorkflowCheck` fails on drift |

**Message:** refuse the second graph. Still get cross-machine reuse and honest CI topology.
"""
    ),
    section("Not a Develocity-class cache tool")(
      md"""
Develocity-class tools accelerate an **existing opaque build** (remote build cache, build scans, predictive test
selection) while CI YAML and module lists often remain a separate maintenance surface. That is a fine acceleration
layer; it is a different product category.

| | Acceleration layer | zipx |
|---|---|---|
| Primary artifact | Agent/plugin + server + scan UI | Planner + generated workflow from the sbt graph |
| What you maintain | Build + CI lists + cache config (often independent) | Modules + typed `zipxCapabilities`; CI is derived |
| Proof of correctness | Faster greens / scan insights | `zipxWorkflowCheck` + docs-as-tests + live cache IT |
| Scope | Speed / observability of tasks | **CI topology + cache wiring + packs** as one system |

Caching alone does **not** remove the second source of truth. zipx deletes disconnected CI (and refuses a Bazel
restatement), then uses sbt 2’s cache (optionally remote) so Aggregate stays cheap.
"""
    ),
    section("Ergonomics: what you open")(
      md"""
**Before:** hand `ci.yml` module matrices, or BUILD files + CI glue, plus cache product config.

**After:** `build.sbt` + a small typed capability list; generated `.github/workflows/ci.yml` is an **output**
(checked in, drift-gated).

Default Aggregate Verify is **one** job (`test`), not one job per module. Escalate to Graph when the **workflow**
needs isolation, not merely to “make caching work.”
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
    section("Good for AI-assisted development and code review")(
      md"""
Self-documenting, single-graph builds help agents and reviewers because:

- **One place to edit** when adding a module (no “also update workflow / BUILD”).
- **Machine-checkable contract:** `zipxWorkflowCheck` + Specular DocSpecs fail when examples drift from planner output.
- **Narrow diffs:** capability and graph changes are typed Scala; generated YAML is regeneratable.
- **Reviewable intent:** packs (`ZipxCentral`, `ZipxDocs`) name the paved path instead of copy-pasted secret/step soup.

Concrete “add a module” path: edit `build.sbt` → `zipxWorkflowGenerate` → PR shows graph + regeneratable workflow.
Reviewers (human or AI) read the capability list and the graph, not a hand-maintained matrix.
"""
    ),
    section("Cache wired by the same planner")(
      md"""
Remote backends are not a side config file: the planner emits services/env alongside jobs. Proof pins:
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
