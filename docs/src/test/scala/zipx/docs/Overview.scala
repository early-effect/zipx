package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.workflow.Render
import zio.test.*

import scala.collection.immutable.ListMap

/** Why the build should own CI topology. */
object Overview extends DocSpecSuite:

  def doc = page("Overview")(
    md"""
**zipx** is an [sbt 2](https://www.scala-sbt.org/) plugin (Scala 3) that turns your real `dependsOn` / `.aggregate`
graph into GitHub Actions CI. Generate the workflow from the build, commit it, and let `zipxWorkflowCheck` keep it
honest. The graph *is* the CI topology. How aggressively that topology fans out is an explicit **execution mode**
(Aggregate by default; Layer or Graph when you need waves, per-module isolation, or multi-environment deploys).

That power is for **everyone** who ships Scala on GitHub Actions: a single-library publish path, a multi-module
monorepo with several services, Central and GitHub Packages side by side, docker and deploy stages you describe in
Scala. Modes are how you schedule work; the graph stays the source of truth. You get typed capabilities, commit-stable
caching, and SHA-pinned actions without hand-rolling YAML module lists.

It is *extra* compelling if you have already paid the tax of a second copy of the build (disconnected CI, or a Bazel
graph that restates the same edges). zipx does not ask you to flee sbt. It makes the build you already write the
source of truth for CI.
""",
    section("What you gain")(
      md"""
### Monorepos that stay honest

In a multi-module repo, edges already live in `build.sbt`. zipx turns them into jobs, `needs`, publish order, and
(when you opt into Graph) affected-only PRs. Add a module the way you always do; regenerate; CI tracks the graph
instead of a hand-maintained matrix.

### Libraries that skip hand-rolled release YAML

Even a small Aggregate library benefits: one root `test` job, a release-gated publish (or `ZipxCentral.release` /
`ZipxGitHubPackages`), docs Pages when you want them. No separate `release.yml` that drifts from who actually
`publishes`. Fork gates and job conditions are Scala, not pasted `if:` strings.

### CI as a generated artifact

`zipxWorkflowGenerate` writes `.github/workflows/ci.yml`. `zipxWorkflowCheck` fails the PR when the committed file
no longer matches the build. Drift becomes a red check, not a surprise on tag day.
"""
    ),
    section("Especially if you have lived the alternatives")(
      md"""
Many teams arrive here with scar tissue. Slow or opaque sbt CI made “just put it in YAML” feel rational. Bazel looked
like peace (hermeticity, remote cache) and delivered a **second graph** in BUILD files while CI still needed hand
wiring. Others kept sbt but **re-listed** every module and recipe in workflows (sometimes plus an external config and
resolver script).

Those approaches invent another copy of the build. The wins of zipx are clearest when you recognize that tax.

### Disconnected CI (YAML that redeclares the build)

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

Typical failure modes:

- **Two sources of truth drift**: add, rename, or re-wire a module in sbt; CI silently keeps the old list.
- **Publish order is not modeled**: the real graph exists only in sbt, so release jobs fan out flat and hope the
  registry already has upstreams (or recompile everything).
- **No honest affected model**: every PR builds the world; cache is the only mitigation.
- **Stringly module ids**: a typo is a green no-op, not a failed load.

### Bazel as a second graph

Leaving sbt for Bazel is often well intentioned. The tax shows up later: Scala engineers still think in modules and
`dependsOn`, but the org owns BUILD files, macros, and CI glue that restate the same edges.

Sketch of the “before” (edges restated outside sbt):

```python
# BUILD (illustrative): a second place that must stay in sync with build.sbt
scala_library(name = "schema", srcs = [...])
scala_library(name = "api", deps = [":schema"], srcs = [...])
scala_library(name = "service", deps = [":api"], srcs = [...])
```

zipx’s answer is not “Bazel is wrong.” It is that **one graph in sbt is enough** for CI topology when the build already
knows the truth. That is a **different strategy**, not Bazel-parity: see **Why zipx** and **From Bazel**.
"""
    ),
    section("One graph, generated CI")(
      md"""
| Approach | When you add a module you… |
|---|---|
| Disconnected CI | Edit workflow YAML (and often an external config / script) |
| Bazel second graph | Edit BUILD (and usually CI) |
| **zipx** | Edit `build.sbt`; run `zipxWorkflowGenerate` / `zipxWorkflowCheck` |

Topology (jobs, `needs`, gates, targets, cache keys) is **derived**. What to run is still your tasks, expressed as
typed **capabilities** in Scala: test, Central, GitHub Packages, docker, deploy, or stages you invent.

Fuller sketch:

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "<version>")

// build.sbt
lazy val schema  = project.settings(/* … */)
lazy val api     = project.dependsOn(schema)
lazy val service = project.dependsOn(api).enablePlugins(DockerPlugin)

lazy val root = (project in file("."))
  .aggregate(schema, api, service)
  .settings(
    // Built-in Aggregate test + publish; paved Central (and/or Packages) when you need it:
    zipxCapabilities ++= {
      val upstream = JobCondition.repositoryIs("acme/libs")
      Seq(
        ZipxCentral.release.withCondition(upstream),
        ZipxDocs.pages().andCondition(upstream),
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
job with a paved Central release (or add GitHub Packages alongside it).

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "<version>")

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
zipx is built for sbt 2 as a capable substrate, not a compromise:

- a machine-wide, content-addressed action cache (what makes Graph fan-out practical when you need it)
- optional Bazel-**gRPC remote cache** transport (`sbt-remote-cache`, bundled). That is cache plumbing, not “adopt
  Bazel as your build”
- Scala 3 plugins, so `zipx-core` / `zipx-workflow` are ordinary unit-tested libraries with no sbt on the classpath
- common settings: a bare `zipxTestTask := "testFull"` applies to every module; any module can override

You keep the ergonomics Scala teams already know. CI stops being a second language for the same edges.
"""
    ),
    section("Architecture")(
      md"""
Three layers:

- **`zipx-workflow`**: GitHub Actions AST + deterministic YAML printer
- **`zipx-core`**: pure planner (`ModuleGraph` → `Workflow`)
- **`sbt-zipx`**: AutoPlugin; the only layer that touches `sbt.*`

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
