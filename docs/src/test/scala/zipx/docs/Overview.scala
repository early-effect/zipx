package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.workflow.Render
import zio.test.*

import scala.collection.immutable.ListMap

/** Why the build should own CI topology. */
object Overview extends DocSpecSuite:

  def doc = page("Overview")(
    md"""
If your team has been burned by sbt, you are not alone. Slow CI, opaque failures, and “just put it in YAML”
muscle memory make it rational to look for an exit. Many orgs try Bazel next, hoping hermetic builds and remote
cache will buy peace. Others keep sbt but **hand-maintain GitHub Actions** that re-list every module, edge, and
recipe in a second place.

Both escapes invent another copy of the build. That second copy is where the pain moves.

**zipx** is an [sbt 2](https://www.scala-sbt.org/) plugin (Scala 3) that refuses the second copy. It reads the
`dependsOn` / `.aggregate` graph you already maintain and **generates** (then **check-verifies**) your GitHub Actions
workflow. The graph *is* the CI topology. How aggressively that topology fans out is an explicit **execution mode**
(Aggregate by default for cost; Layer or Graph when you need waves or per-module isolation).
""",
    section("The pain this solves")(
      md"""
### Disconnected CI (YAML that redeclares the build)

A common monorepo pattern: a hand-maintained workflow (sometimes plus an external config and a small resolver script)
that lists modules, string commands, and publish steps **again**, separate from `build.sbt`.

Sketch of the “before”:

```yaml
# .github/workflows/ci.yml (hand-maintained)
jobs:
  test-schema:
    runs-on: ubuntu-latest
    steps:
      - run: sbt 'schema/test'
  test-api:
    runs-on: ubuntu-latest
    # api depends on schema in build.sbt; CI forgot needs:
    steps:
      - run: sbt 'api/test'
  publish:
    # every library in parallel; order lives only in sbt
    strategy:
      matrix:
        module: [schema, api, client]
    steps:
      - run: sbt '$${{{ matrix.module }}}/publish'
```

What goes wrong, over and over:

- **Two sources of truth drift**: add, rename, or re-wire a module in sbt; CI silently keeps the old list.
- **Publish order is not modeled**: the real graph exists only in sbt, so release jobs fan out flat and hope the
  registry already has upstreams (or recompile everything).
- **No honest affected model**: every PR builds the world; cache is the only mitigation.
- **Stringly module ids**: a typo is a green no-op, not a failed load.

### Bazel as a second graph

Leaving sbt for Bazel is often well intentioned: clearer caching, hermeticity, a serious remote-build story. The tax
shows up later. Scala engineers still think in modules and `dependsOn`, but the org now owns **BUILD files, macros,
and CI glue** that restate the same edges. Graph maintenance becomes the new full-time job; CI is still something you
wire by hand on top.

Sketch of the “before” (edges restated outside sbt):

```python
# BUILD (illustrative): a second place that must stay in sync with build.sbt
scala_library(name = "schema", srcs = [...])
scala_library(name = "api", deps = [":schema"], srcs = [...])
scala_library(name = "service", deps = [":api"], srcs = [...])
```

You did not escape graph pain. You moved it into a language fewer application engineers live in every day, while CI
still needs its own wiring.
"""
    ),
    section("Why zipx instead")(
      md"""
| Approach | When you add a module you… |
|---|---|
| Disconnected CI | Edit workflow YAML (and often an external config / script) |
| Bazel second graph | Edit BUILD (and usually CI) |
| **zipx** | Edit `build.sbt`; run `zipxWorkflowGenerate` / `zipxWorkflowCheck` |

zipx keeps you on sbt 2: the graph your team already writes. Topology (jobs, `needs`, gates, targets, cache keys) is
**derived**. What to run is still your tasks, expressed as typed **capabilities** in Scala, not another YAML dialect
for the same edges.

Fuller sketch of the “after”:

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "<version>")

// build.sbt
lazy val schema  = project.settings(/* … */)
lazy val api     = project.dependsOn(schema)
lazy val service = project.dependsOn(api).enablePlugins(DockerPlugin)

lazy val root = (project in file("."))
  .aggregate(schema, api, service)
  .settings(
    // Built-in Aggregate test + publish; paved Central path when you need it:
    zipxCapabilities ++= {
      val upstream = JobCondition.repositoryIs("acme/libs")
      Seq(
        ZipxCentral.release.withCondition(upstream),
        ZipxDocs.pages().withCondition(upstream),
      )
    },
    zipxJavaVersion      := "25",
    zipxWorkflowDispatch := true,
  )

// Then: sbt zipxWorkflowGenerate && git add .github/workflows/ci.yml
// CI runs zipxWorkflowCheck so a graph change without regenerating fails the PR.
```

One graph. Generated CI. Drift fails the build.
"""
    ),
    section("What it derives")(
      md"""
From the loaded sbt build, zipx emits:

- **Aggregate** Verify by default: one root `sbt test` (matching `.aggregate`), plus Aggregate publish/docker when needed
- **Layer** and **Graph** modes when you opt in (waves or one job per module, affected-only PRs, Scala matrix)
- dependency-ordered publish under Layer/Graph, gated on release tags
- commit-stable caching keyed by a version epoch (pairs with `sbt-dynver-ci`)
- pluggable **capabilities** (test, publish, docker, deploy, or stages you invent in Scala)
- typed **job conditions** (fork repo, PR label, vars) ANDed with Gate; see **Job conditions**
- **SHA-pinned** GitHub Actions (`uses:`), with an optional pin file + Dependabot sync path
"""
    ),
    section("Default Aggregate shape")(
      md"""
For a typical library, defaults are enough: Aggregate `test` + `publish`. Optional packs replace the built-in publish
job with a paved Central release.

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "<version>")

// build.sbt
lazy val lib = project.settings(publishMavenStyle := true)

lazy val root = (project in file("."))
  .aggregate(lib)
  .settings(
    zipxCapabilities += ZipxCentral.release, // optional paved path
    zipxJavaVersion  := "25",
  )
```

Generated Aggregate jobs (live output from the planner):
""",
      exampleValue {
        DocsRender.jobs("test", "publish")(Capability.test, Capability.publish)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("test:"),
          yaml.contains("publish:"),
          yaml.contains("run: sbt 'test'"),
          yaml.contains("startsWith(github.ref, 'refs/tags/v')"),
        )
      ),
    ),
    section("Why stay on sbt 2")(
      md"""
Fleeing sbt was rational on older toolchains. sbt 2 is a different substrate, and zipx is built for it:

- a machine-wide, content-addressed action cache (what makes Graph fan-out practical when you need it)
- optional Bazel-**gRPC remote cache** transport (`sbt-remote-cache`, bundled). That is cache plumbing, not “adopt
  Bazel as your build”
- Scala 3 plugins, so `zipx-core` / `zipx-workflow` are ordinary unit-tested libraries with no sbt on the classpath
- common settings: a bare `zipxTestTask := "testFull"` applies to every module; any module can override

You keep the ergonomics Scala teams already know. You stop maintaining a second graph for CI.
"""
    ),
    section("Architecture")(
      md"""
Three layers:

- **`zipx-workflow`**: GitHub Actions AST + deterministic YAML printer
- **`zipx-core`**: pure planner (`ModuleGraph` → `Workflow`)
- **`zipx-sbt`**: AutoPlugin; the only layer that touches `sbt.*`

The plugin owns topology. The build owns *what* to run (capabilities).
"""
    ),
    section("Typed secrets")(
      md"""
Secret *references* are first-class Scala. zipx never stores secret values; only names that render to GitHub Actions
expressions:

```scala
env = Map(
  "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
  "AWS_REGION"        -> EnvValue.plain("us-west-2"),
  "DEPLOY_ROLE"       -> EnvValue.env("DEPLOY_ROLE"),
)
```
""",
      exampleValue {
        Render.renderMapping(
          ListMap(
            "PGP_PASSPHRASE" -> EnvValue.secret("PGP_PASSPHRASE").render,
            "AWS_REGION"     -> EnvValue.plain("us-west-2").render,
            "DEPLOY_ROLE"    -> EnvValue.env("DEPLOY_ROLE").render,
          )
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("PGP_PASSPHRASE: ${{ secrets.PGP_PASSPHRASE }}"),
          yaml.contains("AWS_REGION: us-west-2"),
          yaml.contains("DEPLOY_ROLE: ${{ env.DEPLOY_ROLE }}"),
        )
      ),
    ),
  )
end Overview
