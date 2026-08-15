package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zipx.workflow.Render
import zio.test.*

import scala.collection.immutable.ListMap

/** What zipx is, in the order a newcomer needs it. */
object Overview extends DocSpecSuite:

  def doc = page("Overview")(
    md"""
zipx is an [sbt 2](https://www.scala-sbt.org/) plugin. You already describe your Scala project in `build.sbt`. zipx
reads that and writes a GitHub Actions workflow, so you do not maintain a second copy in YAML.

GitHub Actions is GitHub's CI: a workflow file under `.github/workflows/` that runs jobs (test, publish, and so on)
when you push or open a pull request. Hand-writing that file means listing modules, job order, and JDK setup again.
zipx generates it from the build you already have.

**Day one:** add the plugin, run `zipxWorkflowGenerate`, commit the files, open a PR. Defaults give you one test job
and a publish job that runs when you push a version tag. You do not need YAML, job matrices, or GitHub's `needs:`
graph. See **Quick start**.
""",
    section("The everyday loop")(
      md"""
```mermaid
flowchart TD
  Build[build.sbt] --> Zipx[sbt-zipx]
  Zipx --> Gen[zipxWorkflowGenerate]
  Gen --> Yaml[commit ci.yml]
  Yaml --> GHA[GitHub Actions runs it]
  Zipx --> Check[zipxWorkflowCheck]
  Check -->|forgot to regenerate?| Fail([PR fails])
  class Fail sad
  class Check warn
  class Build,Zipx,Gen,Yaml,GHA happy
```

1. Edit `build.sbt` the way you already do (add a module, change `dependsOn`).
2. `sbt zipxWorkflowGenerate` writes `.github/workflows/ci.yml` and `.github/actions/zipx-*`.
3. Commit those files and open a pull request. GitHub runs the workflow.

`zipxWorkflowCheck` in CI regenerates and diffs. If you changed the build and forgot to regenerate, the PR goes red
instead of shipping a stale workflow. That is the whole honesty story.
"""
    ),
    section("Default Aggregate shape")(
      md"""
For a typical library, defaults are enough: one **test** job and one **publish** job. Optional packs replace the
built-in publish with a paved Central release (or add GitHub Packages alongside it).

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
    section("What you gain")(
      md"""
### You keep writing Scala, not YAML

Add a module the way you always do; regenerate; CI tracks the graph. No hand-maintained list of project ids, no
forgotten `needs:` between jobs.

### Libraries skip a separate release workflow

Even a small library gets one root test job and a publish job gated on a version tag (or `ZipxCentral.release` /
`ZipxGitHubPackages`). Docs Pages when you want them. Fork gates are Scala, not pasted `if:` strings.

### Versions you can actually bump

The usual Scala bump is regex on `"org" %% "name" % "1.2.3"` sprinkled through the build. zipx keeps `Lib` / `Plugin`
values in one file, rewrites those constructors, generates `plugins.sbt`, and fails generate if you sneak a raw
coordinate in. That is stronger than a string lock file. See **Versions**.

### The YAML is short enough to read in a PR

Generated CI is meant to be reviewed, not only executed. Two defaults keep the file short:

- **In-repo composites** under `.github/actions/zipx-*` hold JDK / sbt / cache setup (and AWS login when you use those
  packs). Jobs call `uses: ./.github/actions/…`. You do not copy bootstrap steps into every job.
- **`MatrixCollapse.Auto`** folds look-alike jobs into one GitHub matrix when that is safe. Stay on Aggregate and you
  may never notice this; see **Matrix collapse** if Graph makes the Actions UI noisy.

On this repository's dogfood `ci.yml` that cut about **290 → 164** lines; the `examples/monorepo` sample went about
**786 → 307** lines.
"""
    ),
    section("One graph, generated CI")(
      md"""
| Approach | When you add a module you… |
|---|---|
| Hand-written Actions YAML | Edit the workflow (and often a second config) |
| A second build graph (Bazel BUILD, …) | Edit that graph (and usually CI too) |
| **zipx** | Edit `build.sbt`; run `zipxWorkflowGenerate` |

What to run is still your tasks, listed as typed **capabilities** in Scala: test, publish, docker, deploy, or stages
you invent. Job order and gates are derived.

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
"""
    ),
    section("If you already maintain CI by hand")(
      md"""
Skip this section if you are new to GitHub Actions. **Quick start** is enough. This is the recovery story for teams
who already have a painful second copy of the build.

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

Typical bruises with a hand-written `ci.yml`:

- Add, rename, or re-wire a module in sbt; CI silently keeps the old list.
- Publish jobs fan out flat and hope the registry already has upstreams.
- Every PR builds the world; cache is the only mitigation.
- A typo'd module id is a green no-op, not a failed load.

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
```

Fuller recovery framing (including Bazel as a second graph): **Why zipx** and **From Bazel**.
"""
    ),
    section("What it derives")(
      md"""
A map of later pages. On day one you can ignore everything except Aggregate test + publish.

| Surface | What you get |
|---|---|
| **Execution modes** | **Aggregate** (default): one root test job. **Layer** / **Graph** only when you need waves or per-module jobs |
| **Matrix collapse** | **Auto** by default; skip until Graph makes the Actions UI noisy |
| **Composites** | `.github/actions/zipx-sbt-setup` (and `zipx-aws-login` if you use AWS packs) |
| **Capabilities** | Built-in test / publish / docker / deploy; packs for Central, Packages, docs, AWS |
| **Ordering and gates** | Publish on a version tag; deploy destinations are never skipped by path |
| **Affected** | Graph only: skip jobs this PR did not touch |
| **Caching** | Restore sbt's cache on the runner so test does not start from zero |
| **Action pins** | Exact Action commits in the generated YAML; skip until you bump them yourself |
| **Pin feeds** | Pins that are not Maven and not Actions; see **Pin feeds** |
| **Versions** | Typed catalog; bump locally with `zipxDepUpdate`, then open a PR |
| **Job conditions** | Optional extra `if:` (fork, label, …). Skip until you need one |
| **Validation** | Generate fails instead of emitting a broken workflow |

Topology is derived. *What* to run stays your tasks, expressed as typed capabilities.
"""
    ),
    section("Why stay on sbt 2")(
      md"""
zipx is built for sbt 2 because that is where the cache and the plugin model are:

- a machine-wide cache of task results (why one Aggregate job stays cheap on a cold CI runner)
- optional remote cache over the same protocol Bazel uses for cache only. That is plumbing, not "switch to Bazel"
- Scala 3 plugins, so zipx's planner is an ordinary tested library
- common settings: a bare `zipxTestTask := zipxTasks.of(testFull)` is the plugin default; any module can override

You keep writing sbt. CI stops being a second language for the same modules.
"""
    ),
    section("Architecture")(
      md"""
How zipx is built. Skip unless you are contributing or curious.

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

- **zipx-workflow**: GitHub Actions AST + deterministic YAML printer
- **zipx-core**: pure planner (`ModuleGraph` → `Workflow`)
- **sbt-zipx**: AutoPlugin; the only layer that touches `sbt.*`

The plugin owns topology. The build owns *what* to run (capabilities).
"""
    ),
    section("Typed secrets")(
      md"""
zipx never stores secret *values*. You name GitHub secrets in Scala; they render to Actions expressions:

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
