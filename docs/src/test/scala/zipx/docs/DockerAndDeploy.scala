package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.core.EnvValue.secret
import zio.test.*

/** Docker paved path and multi-target deploy. */
object DockerAndDeploy extends DocSpecSuite:

  def doc = page("Docker and deploy")(
    md"""
Skip until you ship a service image or more than one environment. Enable
[sbt-native-packager](https://github.com/sbt/sbt-native-packager)'s `DockerPlugin` on the module; zipx adds the docker
job. **Targets** are named environments (staging, production) with separate GitHub Environment approvals.

```mermaid
flowchart TD
  Svc([1 · service + DockerPlugin]) --> DockerJob[2 · docker job · Docker publish]
  DockerJob --> Staging[3a · deploy-staging]
  DockerJob --> Prod[3b · deploy-prod]
  Staging --> EnvS[(GitHub Environment · staging)]
  Prod --> EnvP[(GitHub Environment · production)]
  class Svc,DockerJob happy
  class Staging,Prod,EnvS,EnvP warn
```

Green is the image path (plugin → Aggregate `Docker/publish`). Amber is the target fan-out: one deploy job per
`Target`, each wired to its own GitHub Environment (approvals stay independent).
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
`dockerGraph`). Pushing one image to several registries stays **one** job; see *Registries are destinations, targets are
environments* below. For PR-label stage ECR (before merge), see **Job conditions**. Independent library versions
(`Ship` / `ShipGroup`) do **not** move docker onto `Gate.OnDefaultPush`; image and deploy still wait on a human `v*`
tag. See **Independent versions**.
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
    section("Registries are destinations, targets are environments")(
      md"""
The rule of thumb, because getting it backwards is expensive:

| The destinations are | Shape | Why |
| --- | --- | --- |
| Registries for **one** image | `withSharedTargets` (`TargetFanOut.SharedJob`) | `Docker / publish` builds once and pushes every `dockerAliases` entry, so N registries is one job |
| Deploy **environments** | `withTargets` (`TargetFanOut.JobPerTarget`, the default) | Each really is a separate job: its own approval, its own `environment:`, its own `if:` |

`targets` multiplies jobs. That is right for the second row and wrong for the first: 6 registries across 8 images is
**48** jobs under `JobPerTarget` and 8 under `SharedJob`, and the 48 each rebuild the same image, so nothing guarantees
the registries hold identical bytes. One build pushed N times does guarantee it.

```scala
zipxCapabilities += Capability.docker.withSharedTargets(
  List(
    Target(TargetName("us"), env = Map("AWS_REGION" -> EnvValue.plain("us-east-1"), "AWS_ROLE_TO_ASSUME" -> secret"US_ROLE")),
    Target(TargetName("eu"), env = Map("AWS_REGION" -> EnvValue.plain("eu-west-1"), "AWS_ROLE_TO_ASSUME" -> secret"EU_ROLE")),
  )
).copy(extraSteps = ZipxAws.sharedLoginSteps)
```

One job, one image, OIDC then ECR login per destination. On AWS, `ZipxAws.dockerPublishAll(registries)` is that whole
expression (see **Packs**).
""",
      exampleValue {
        DocsRender.job("docker")(
          Capability.docker.withSharedTargets(
            List(
              Target(
                TargetName("us"),
                env = Map("AWS_REGION" -> EnvValue.plain("us-east-1"), "AWS_ROLE_TO_ASSUME" -> secret"US_ROLE"),
              ),
              Target(
                TargetName("eu"),
                env = Map("AWS_REGION" -> EnvValue.plain("eu-west-1"), "AWS_ROLE_TO_ASSUME" -> secret"EU_ROLE"),
              ),
            )
          )
        )
      }.assert(yaml =>
        assertTrue(
          // One `docker:` job, and both destinations' values in its env under their own prefix.
          yaml.contains("ZIPX_US_AWS_REGION: us-east-1"),
          yaml.contains("ZIPX_EU_AWS_REGION: eu-west-1"),
          yaml.contains("ZIPX_US_AWS_ROLE_TO_ASSUME: ${{ secrets.US_ROLE }}"),
          yaml.contains("service/Docker/publish"),
          // Not `docker-us:` / `docker-eu:`: the ids are the ones the capability would have had with no targets, so a
          // `needs:` edge onto `docker` keeps working when a registry is added.
          !yaml.contains("docker-us:"),
        )
      ),
      md"""
### Why the env keys are prefixed

Both destinations want `AWS_ROLE_TO_ASSUME`. Merging unprefixed would keep whichever one came last, and the job would
push twice to one account while silently skipping the other, so a shared job puts each destination's `env` under
`Target.envKey`: `ZIPX_<TARGET>_<KEY>`. `Target.envName(name)` is how a step reads it back, and the fixed `ZIPX_`
anchor is what makes that total: a target named `github` would otherwise derive a `GITHUB_…` name, which `EnvName`
refuses because GitHub reserves the prefix.

`extraSteps` receives every destination as `StepContext.destinations` (and `StepContext.target` is `None`, since there
is no single target a shared job belongs to), which is how one bundle emits one login per registry.

### What a shared job refuses

A `Target.condition` or `Target.environment` under `SharedJob` is a **generate-time error**, not a silently dropped
field:

```
zipx: capability 'docker' target 'us' sets a condition, which one shared job cannot honor per destination.
Use TargetFanOut.JobPerTarget (the default) when destinations need their own condition, or drop it and gate
the whole job with Capability.condition.
```

Dropping it would push to a registry the author said to skip; applying it job-wide would skip the ones that were fine.
Both are wrong answers arrived at quietly, so zipx declines to pick one. Per-destination approval is the second row of
the table: that is what `JobPerTarget` is for.
""",
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
    Target(TargetName("staging"), env = Map("TIER" -> EnvValue.plain("staging"))),
    Target(
      TargetName("prod"),
      environment = Some("production"),
      env = Map("TIER" -> EnvValue.plain("prod"), "DEPLOY_ROLE" -> secret"PROD_ROLE"),
      condition = Some(JobCondition.varNonEmpty("DEPLOY_PROD_ENABLED")),
    ),
  ),
  needsCapabilities = List(Capability.DockerName),
  permissions = Map("id-token" -> "write", "contents" -> "read"),
)
```

Note what the prod condition is **not**: `refIs("refs/heads/main")`. `Capability.deploy` gates `OnReleaseTag`, the two are
ANDed, and no ref is both a `v*` tag and `refs/heads/main`, so zipx refuses to generate that pair outright (see
[[JobConditions]]). Pass `gate = Gate.Always` if deploy-from-main is what you want.
""",
      exampleValue {
        val targets = List(
          Target(TargetName("staging"), env = Map("TIER" -> EnvValue.plain("staging"))),
          Target(
            TargetName("prod"),
            environment = Some("production"),
            env = Map("TIER" -> EnvValue.plain("prod"), "DEPLOY_ROLE" -> secret"PROD_ROLE"),
            condition = Some(JobCondition.varNonEmpty("DEPLOY_PROD_ENABLED")),
          ),
        )
        DocsRender.jobs("deploy-staging", "deploy-prod")(
          Capability.deploy(
            participates = _.id == "service",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
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
          yaml.contains("vars.DEPLOY_PROD_ENABLED != ''"),
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
