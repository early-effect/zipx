package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.{Capability, EnvValue, ModuleGraph, ModuleNode, Planner, PlanConfig, Target}
import zipx.core.EnvValue.secret
import zio.test.*

/** Install and configure zipx. */
object Usage extends DocSpecSuite:

  def doc = page("Usage")(
    md"""
Add the plugin, generate the workflow, and commit it. zipx also checks the committed YAML
in CI so a build change that isn't reflected in the workflow fails the PR.
""",
    section("Install")(
      md"""
```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "<version>")
```

Generate and commit:

```
sbt zipxWorkflowGenerate
git add .github/workflows/ci.yml
```

Inspect what zipx sees: `sbt zipxGraph` and `sbt zipxPublishOrder`.
"""
    ),
    section("Bare settings (sbt 2.0)")(
      md"""
zipx reads build-level settings from the root project's scope, so write plain bare settings —
no `ThisBuild /` prefix. A bare `zipxTestTask := "testFull"` applies to every module; any
module can override it.
"""
    ),
    section("Capabilities & secrets")(
      md"""
Append custom pipeline stages with `zipxCapabilities`. Same-name replaces a built-in
(e.g. multi-registry docker). Attach org publish secrets once via `Capability.env`:
""",
      exampleValue {
        val pub = Capability.publish.copy(env =
          Map(
            "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
            "SONATYPE_USERNAME" -> EnvValue.secret("SONATYPE_USERNAME"),
          )
        )
        val graph = ModuleGraph(List(ModuleNode("lib", publishes = true, crossScalaVersions = List("3.8.4"))))
        val job   = Planner.plan(graph, List(pub), PlanConfig(cacheEpoch = "0.1.0-ci")).jobs("publish-lib")
        (job.env.get("PGP_PASSPHRASE"), job.env.get("SONATYPE_USERNAME"))
      }.assert { case (pgp, user) =>
        assertTrue(
          pgp.contains("${{ secrets.PGP_PASSPHRASE }}"),
          user.contains("${{ secrets.SONATYPE_USERNAME }}"),
        )
      },
    ),
    section("Deploy targets")(
      md"""
Multi-environment deploys are typed Scala — a `List[Target]` in `project/*.scala`, not an
external YAML config. Production approval is a GitHub Environment binding on the job:
""",
      exampleValue {
        val targets = List(
          Target("staging", env = Map("TIER" -> EnvValue.plain("staging"))),
          Target("prod", environment = Some("production"), env = Map("TIER" -> EnvValue.plain("prod"))),
        )
        (targets.map(_.name), targets.flatMap(_.environment))
      }.assert { case (names, envs) =>
        assertTrue(names == List("staging", "prod"), envs == List("production"))
      },
    ),
  )
end Usage
