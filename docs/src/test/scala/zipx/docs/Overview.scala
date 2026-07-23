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
**zipx** is an [sbt 2](https://www.scala-sbt.org/) plugin (Scala 3) that generates a GitHub Actions workflow
from your real `dependsOn` graph.

Hand-written `.github/workflows/*.yml` tends to re-declare the module set, edges, and recipes — then drift from
`build.sbt`. zipx makes the build the single source of truth: the graph *is* the CI topology. How aggressively that
topology fans out into jobs is an explicit **execution mode** (Aggregate by default for cost; Layer or Graph when you
need waves or per-module isolation).
""",
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
```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "<version>")

// build.sbt — defaults are enough for a library; optional paved path:
zipxCapabilities += ZipxCentral.release
```
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
    section("Why sbt 2.0")(
      md"""
zipx leans on sbt 2.0:

- a machine-wide, content-addressed action cache (what makes Graph fan-out practical)
- Bazel-gRPC remote cache transport (`sbt-remote-cache`, bundled)
- Scala 3 plugins, so `zipx-core` / `zipx-workflow` are ordinary unit-tested libraries with no sbt on the classpath
- common settings: a bare `zipxTestTask := "testFull"` applies to every module; any module can override
"""
    ),
    section("Architecture")(
      md"""
Three layers:

- **`zipx-workflow`** — GitHub Actions AST + deterministic YAML printer
- **`zipx-core`** — pure planner (`ModuleGraph` → `Workflow`)
- **`zipx-sbt`** — AutoPlugin; the only layer that touches `sbt.*`

The plugin owns topology. The build owns *what* to run (capabilities).
"""
    ),
    section("Typed secrets")(
      md"""
Secret *references* are first-class Scala. zipx never stores secret values — only names that render to GitHub Actions
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
