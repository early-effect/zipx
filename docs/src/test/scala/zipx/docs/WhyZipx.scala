package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Positioning: one sbt graph, generated Actions, no second YAML copy. */
object WhyZipx extends DocSpecSuite:

  def doc = page("Why zipx")(
    md"""
Hand-written GitHub Actions YAML is a second copy of your build: module lists, job order, JDK setup. It drifts. zipx
generates that YAML from `build.sbt` so CI stays honest without you learning a second language.

If you are new to CI, **Quick start** is enough. Come back here when you want the "why," or when you already maintain
a painful workflow.

If your team has lived through hand-maintained job lists, a second BUILD graph, or a cache product that made **tasks**
faster while **humans** still juggled two sources of truth: the rest of this page is the recovery story.

When you are ready for those migration stories, see **From Bazel**, **Caching**, and **Remote cache for teams**.
""",
    section("Three paths, one that heals")(
      md"""
Most teams we meet are somewhere on this triangle. None of the first two are foolish; they were reasonable responses
to real pain. zipx is the path that keeps your Scala mental model: one `build.sbt`, generated CI.

```mermaid
flowchart TD
  Pain([Slow or opaque CI]) --> Disc[Disconnected CI · hand YAML]
  Disc --> DriftD([two sources of truth])
  DriftD -.->|or| Bazel[Bazel second graph · BUILD + CI glue]
  Bazel --> DriftB([edges restated outside sbt])
  DriftB -.->|or| Zipx[zipx · one sbt graph]
  Zipx --> Heal([Derived ci.yml · zipxWorkflowCheck])
  class Disc,DriftD,Bazel,DriftB sad
  class Zipx,Heal happy
  class Pain warn
```

| Approach | Source of truth | What drifts (the bruise) |
|---|---|---|
| Disconnected CI | `build.sbt` **and** hand YAML | Module lists, `needs`, publish order |
| Bazel second graph | BUILD (+ often CI) | Edges restated outside sbt |
| **zipx** | `build.sbt` / `.dependsOn` | CI is derived; `zipxWorkflowCheck` catches drift early |

You do not need a second graph to feel safe. You need one honest graph, and a check that fails when CI lies.
"""
    ),
    section("Versions were the other stringly mess")(
      md"""
CI YAML is not the only second copy. Scala versions usually live as `"org" %% "name" % "1.2.3"` strings, and the bump
tools search the repo with regex. That is how most of the ecosystem still works, and it is why dependency PRs feel
brittle.

zipx keeps `Lib` / `Plugin` / `Pin` / `Action` vals in one object you extend from `ZipxVersions`. Every val is a catalog
row; there is no second list to keep in sync. `MyVersions.settings` is the usual sbt wiring (`scalaVersion`, catalog
keys, `zipxCheckDeps`). Apply rewrites those constructors. Generate owns `plugins.sbt`. A raw coordinate that is not in
the catalog fails generate. Other plugins extend the same trait. See **Versions**. Plugin authors: **Extending
Versions**.

Outbound versions are a fourth collection: `Ship` / `ShipGroup` when a monorepo publishes libraries on different
cadences. Merge to `main` is the release signal. zipx-the-product stays lockstep on a `v*` tag. See **Independent
versions**.

```scala
import zipx.*
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.8")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val zio                 = Lib("dev.zio", "zio", "2.1.26")
```
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

**The recovery:** `build.sbt` and a small typed capability list. Generated `.github/workflows/ci.yml` plus
`.github/actions/zipx-*` composites are **outputs** you commit and drift-gate. Reviewers (and future you) see intent,
not archaeology: short jobs that call local composites, matrix collapse when safe, and named packs instead of pasted
secret/step soup.

Default Aggregate Verify is a handful of **build-wide** jobs (`test`, `fmt`, `workflow-check`, `advisories`), not one
job per module. Reach for Graph when the **workflow** needs isolation, not when you are only trying to make caching
feel less lonely. `MatrixCollapse.Auto` keeps Graph / multi-target fan-out readable when legs are isomorphic.
""",
      exampleValue {
        val aggregate = DocsRender.body(Capability.test, Capability.publish)
        val graph     = DocsRender.body(Capability.testGraph)
        aggregate + "\n---\n" + graph
      }.assert(yaml =>
        val parts = yaml.split("---").toList
        assertTrue(
          parts.length == 2,
          parts(0).contains("test:") || parts(0).contains("\ntest\n") || parts(0).linesIterator
            .exists(_.trim == "test:"),
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
  re-diff. Composites and Auto collapse keep that YAML short enough to actually review.
- **Named paved paths:** packs like `ZipxCentral`, `ZipxDocs`, and AWS login say what you meant, instead of a paste of
  secret/step soup.

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
