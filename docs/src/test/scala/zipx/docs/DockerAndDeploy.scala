package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Docker paved path and multi-target deploy. */
object DockerAndDeploy extends DocSpecSuite:

  def doc = page("Docker and deploy")(
    md"""
Services opt into images by enabling [sbt-native-packager](https://github.com/sbt/sbt-native-packager)'s
`DockerPlugin`. Deploying to multiple environments fans out over typed **targets**.
""",
    section("Docker paved path")(
      md"""
```scala
lazy val service = project
  .dependsOn(coreLib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    publishArtifact     := false,
    Compile / mainClass := Some("example.Main"),
    dockerBaseImage     := "eclipse-temurin:21-jre",
    Docker / packageName := "example-service",
  )
```

zipx detects `DockerPlugin` and emits a release-gated Aggregate `docker` job joining `…/Docker/publish` (or use
`dockerGraph`). Multi-registry pushes are a custom capability with targets (see **Custom capabilities**). For
PR-label stage ECR (before merge), see **Job conditions**.
""",
      exampleValue {
        val wf = Planner.plan(libGraph, List(Capability.docker), config)
        wf.jobs("docker").steps.last.run
      }.assert(run => assertTrue(run.exists(_.contains("service/Docker/publish")))),
    ),
    section("Aggregate-by-target deploy")(
      md"""
**Default (`Capability.deploy`):** one job per Target; participating modules' commands are joined. GitHub Environments
stay independent.

**Escape hatch (`Capability.deployGraph`):** one job per (module × target).
""",
      exampleValue {
        val targets = List(
          Target("staging", env = Map("TIER" -> EnvValue.plain("staging"))),
          Target(
            "prod",
            environment = Some("production"),
            env = Map("TIER" -> EnvValue.plain("prod"), "DEPLOY_ROLE" -> secret"PROD_ROLE"),
            condition = Some(JobCondition.refIs("refs/heads/main")),
          ),
        )
        val deploy = Capability.deploy(
          participates = _.id == "service",
          command = n => s"${n.id}/promote",
          targets = _ => targets,
          needsCapabilities = Nil,
        )
        val wf = Planner.plan(libGraph, List(deploy), config)
        (
          wf.jobs.keys.filter(_.startsWith("deploy-")).toList.sorted,
          wf.jobs("deploy-prod").environment,
          wf.jobs("deploy-prod").env.get("DEPLOY_ROLE"),
        )
      }.assert { case (ids, envName, role) =>
        assertTrue(
          ids == List("deploy-prod", "deploy-staging"),
          envName.contains("production"),
          role.contains("${{ secrets.PROD_ROLE }}"),
        )
      },
      md"""
**Approval is enforced by GitHub, not zipx.** zipx emits the `environment:` binding; GitHub pauses for protection
rules. Put deploy config in `project/*.scala` as typed lists (see
[`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo)).
""",
    ),
  )
end DockerAndDeploy
