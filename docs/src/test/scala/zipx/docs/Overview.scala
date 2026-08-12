package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
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
Scala. Modes are how you schedule work; the graph stays the source of truth.

What you write in Scala is a typed **capability** list (test, publish, docker, deploy, packs, or stages you invent).
What you commit is short, reviewable YAML: in-repo composites for bootstrap / AWS login, `MatrixCollapse.Auto` so
isomorphic Graph and target fan-out collapses when safe, SHA-pinned third-party actions, and drift checks on both the
workflow and the composites. No hand-rolled module matrices.

If you already carry scar tissue from a second copy of the build (disconnected CI, or a Bazel graph that restated the
same edges), you will feel the relief fastest. zipx does not ask you to flee sbt. It makes the build you already write
the source of truth for CI again. Start with **Why zipx** when you want the recovery framing.
""",
    section("What you gain")(
      md"""
### Monorepos that stay honest

In a multi-module repo, edges already live in `build.sbt`. zipx turns them into jobs, `needs`, publish order, and
(when you opt into Graph) affected-only PRs (fail-open when the diff breaks; see **Affected**). Add a module the way
you always do; regenerate; CI tracks the graph instead of a hand-maintained matrix.

### Libraries that skip hand-rolled release YAML

Even a small Aggregate library benefits: one root `test` job, a release-gated publish (or `ZipxCentral.release` /
`ZipxGitHubPackages`), docs Pages when you want them. No separate `release.yml` that drifts from who actually
`publishes`. Fork gates and job conditions are Scala, not pasted `if:` strings.

### Reviewable YAML by default

Generated CI is meant to be read in a PR, not only executed. Two defaults keep the file short:

- **In-repo composites** under `.github/actions/zipx-*` parameterize checkout / JDK / sbt / cache bootstrap and AWS
  OIDC / ECR login. Jobs call `uses: ./.github/actions/…` with inputs; nested third-party `uses:` stay SHA-pinned
  inside the composites (see **Action pins**).
- **`MatrixCollapse.Auto`** folds isomorphic Graph module siblings (and Aggregate / Layer target siblings) into one
  `strategy.matrix` job when safe, including `matrix.include` when GitHub Environments differ from target names. When
  collapse would drop same-capability `needs`, Auto expands instead of failing generate (`Off` / `Strict` / `Coarse`
  remain available; see **Matrix collapse**).

On this repository's dogfood `ci.yml` that cut about **290 → 164** lines; the `examples/monorepo` sample went about
**786 → 307** lines.

### CI as a generated artifact

`zipxWorkflowGenerate` writes `.github/workflows/ci.yml` and `.github/actions/zipx-*/action.yml`. `zipxWorkflowCheck`
fails the PR when committed files no longer match the build. Drift becomes a red check, not a surprise on tag day.
"""
    ),
    section("Especially if you have lived the alternatives")(
      md"""
Many teams arrive tired. Slow or opaque sbt CI made “just put it in YAML” feel rational. Bazel looked like peace
(hermeticity, remote cache) and delivered a **second graph** in BUILD files while CI still needed hand wiring. Others
kept sbt but **re-listed** every module and recipe in workflows (sometimes plus an external config and resolver
script).

```mermaid
flowchart TD
  Tired([Tired of CI drift]) --> YAML[Hand-maintained YAML]
  Tired --> Bazel[BUILD second graph]
  YAML --> Tax[Two sources of truth]
  Bazel --> Tax
  Tax --> Zipx[zipx: one graph]
  class Tired warn
  class YAML,Bazel,Tax sad
  class Zipx happy
```

Those approaches invent another copy of the build. Recognizing that tax is the first step toward something kinder.

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

Typical failure modes (the bruises):

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

zipx’s answer is not “Bazel was wrong.” It is that **one graph in sbt is enough** for CI topology when the build
already knows the truth. That is a recovery strategy, not a parity claim: see **Why zipx** and **From Bazel**.
"""
    ),
    section("One graph, generated CI")(
      md"""
```mermaid
flowchart TD
  Build[build.sbt · dependsOn and aggregate] --> Zipx[sbt-zipx]
  Zipx --> Gen[zipxWorkflowGenerate]
  Gen --> Yaml[ci.yml committed]
  Zipx --> Check[zipxWorkflowCheck]
  Check -->|drift?| Fail([PR fails])
  Yaml --> GHA[GitHub Actions]
  class Fail sad
  class Check warn
  class Build,Zipx,Gen,Yaml,GHA happy
```

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
    zipxJavaVersion      := JdkVersion("25"),
    zipxWorkflowDispatch := true,
  )

// Then: sbt zipxWorkflowGenerate && git add .github/workflows/ci.yml .github/actions/
// CI runs zipxWorkflowCheck so a graph change without regenerating fails the PR.
```

One graph. Generated CI. Drift fails the build.
"""
    ),
    section("What it derives")(
      md"""
From the loaded sbt build, zipx emits a full CI surface, not only a test job:

| Surface | What you get |
|---|---|
| **Execution modes** | **Aggregate** Verify by default (one root `sbt test` matching `.aggregate`); **Layer** waves and **Graph** per-module jobs when you opt in |
| **Matrix collapse** | **`Auto`** by default: collapse safe isomorphic fan-out (simple matrix or `matrix.include`); expand when unsafe; `Off` / `Strict` / `Coarse` as escapes |
| **Composites** | `.github/actions/zipx-sbt-setup` and `zipx-aws-login` (and drift-checked with `ci.yml`) |
| **Capabilities** | Built-in test / publish / docker / deploy; packs (`ZipxCentral`, `ZipxGitHubPackages`, `ZipxDocs`, AWS); custom stages in Scala |
| **Ordering & gates** | Dependency-ordered publish under Layer/Graph; release-tag publish; Deploy destinations never path-affected |
| **Affected** | Graph path gating on PR / push (`zipxAffectedOnPR` / `zipxAffectedOnPush`); fail-open when the diff breaks |
| **Caching** | Commit-stable local cache keys (pairs with `sbt-dynver-ci`); optional Bazel-gRPC remote cache transport |
| **Action pins** | SHA-pinned third-party `uses:` (jar defaults, pin file, Dependabot + `zipxActionsPull`) |
| **Job conditions** | Typed `if:` (fork repo, PR label, vars) ANDed with Gate |
| **Validation** | Generate-time checks for cycles, never-true conditions, invalid job ids, unreadable pin files |

Topology is derived. *What* to run stays your tasks, expressed as typed capabilities.
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
    zipxJavaVersion  := JdkVersion("25"),
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
- common settings: a bare `zipxTestTask := zipxTasks.of(testFull)` is the plugin default; any module can override

You keep the ergonomics Scala teams already know. CI stops being a second language for the same edges.
"""
    ),
    section("Architecture")(
      md"""
Three layers:

```mermaid
flowchart TD
  Plugin[sbt-zipx · AutoPlugin]
  Graph[ModuleGraph · from dependsOn]
  Core[zipx-core · Planner]
  Workflow[zipx-workflow · YAML AST]
  Ci([committed ci.yml])
  Plugin --> Graph
  Plugin --> Core
  Graph --> Core
  Core --> Workflow
  Workflow --> Ci
```

- **zipx-workflow**: GitHub Actions AST + deterministic YAML printer (including composite `action.yml`)
- **zipx-core**: pure planner (`ModuleGraph` → `Workflow`)
- **sbt-zipx**: AutoPlugin; the only layer that touches `sbt.*`

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
        Render
          .renderMapping(
            ListMap(
              "PGP_PASSPHRASE" -> EnvValue.secret("PGP_PASSPHRASE").render,
              "AWS_REGION"     -> EnvValue.plain("us-west-2").render,
              "DEPLOY_ROLE"    -> EnvValue.env("DEPLOY_ROLE").render,
            )
          )
          .yaml
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
