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
        DocsRender.job("docker")(Capability.docker)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("docker:"),
          yaml.contains("service/Docker/publish"),
          yaml.contains("refs/tags/v"),
        )
      ),
    ),
    section("Aggregate-by-target deploy")(
      md"""
**Default (`Capability.deploy` / `zipxTasks.deploy`):** one job per Target; participating modules' commands are joined.
GitHub Environments stay independent.

**Escape hatch (`Capability.deployGraph` / `zipxTasks.deployGraph`):** one job per (module × target).

```scala
val promote = taskKey[Unit]("promote the image")

zipxCapabilities += zipxTasks.deploy(
  participates = _.id == "service",
  command = promote,
  targets = _ => List(
    Target("staging", env = Map("TIER" -> EnvValue.plain("staging"))),
    Target(
      "prod",
      environment = Some("production"),
      env = Map("TIER" -> EnvValue.plain("prod"), "DEPLOY_ROLE" -> secret"PROD_ROLE"),
      condition = Some(JobCondition.refIs("refs/heads/main")),
    ),
  ),
  needsCapabilities = List("docker"),
  permissions = Map("id-token" -> "write", "contents" -> "read"),
)
```
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
        DocsRender.jobs("deploy-staging", "deploy-prod")(
          Capability.deploy(
            participates = _.id == "service",
            command = n => s"${n.id}/promote",
            targets = _ => targets,
            needsCapabilities = Nil,
          )
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("deploy-staging:"),
          yaml.contains("deploy-prod:"),
          yaml.contains("environment: production"),
          yaml.contains("DEPLOY_ROLE: ${{ secrets.PROD_ROLE }}"),
          yaml.contains("github.ref == 'refs/heads/main'"),
        )
      ),
      md"""
**Approval is enforced by GitHub, not zipx.** zipx emits the `environment:` binding; GitHub pauses for protection
rules. Put deploy config in `project/*.scala` as typed lists (see
[`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo)).
""",
    ),
  )
end DockerAndDeploy
