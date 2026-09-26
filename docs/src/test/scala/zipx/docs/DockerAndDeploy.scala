package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.docs.DocsFixtures.*
import zipx.docs.DocsRender.yaml
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

**Where they run is one setting.** By default (`DeployTrigger.OnMerge`) images and deploys are jobs in `ci.yml`, gated
like any other job: on a release tag, or on a condition you add. `DeployTrigger.Manual` moves them into a dispatched
`zipx-deploy.yml` that ships only what changed since each Environment's last deploy, so a merge ships nothing and a
pending production approval never holds up the next merge. The sections below apply to both; the last one covers
Manual. **CI for a busy monorepo** shows it beside the other settings a large repo turns on.
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
    section("Deploy by hand (DeployTrigger.Manual)")(
      md"""
By default images and deploys run in `ci.yml`, on whatever their gates select. On a busy main branch that means every
merge builds images, and a production approval that nobody answers holds `ci.yml`'s concurrency group, so later merges
queue behind it. `DeployTrigger.Manual` moves them into their own workflow that someone runs:

```scala
zipxDeployTrigger := DeployTrigger.Manual()

// on each image module: what the tag check looks up
zipxImageRefs := (Docker / dockerAliases).value.map(_.toString)

Target(TargetName("stg"), environment = Some("staging"), group = Some(TargetGroup("pre-prod")))
```

`ci.yml` keeps Verify and library publish. `.github/workflows/zipx-deploy.yml` takes the image capability
(`Capability.DockerName`), every Deploy capability, and everything that needs one. It runs from **Actions → Run
workflow** with three inputs:

| Input | Values |
| --- | --- |
| `modules` | `changed` (default), `all`, or one module |
| `target` | a target name, or a `Target.group` that deploys every target in it |
| `sha` | a commit to deploy, for a rollback; empty deploys the branch head |

**`changed` is per Environment.** The `resolve` job reads each Environment's deployments from GitHub and diffs the last
successful deploy of each module against the commit being deployed. A module never deployed there is deployed, and so
is every module when the diff cannot run: an over-deploy costs minutes, an under-deploy ships a stale service. GitHub
records each job that binds an Environment as a deployment of the *run's* commit, so every deploy job sets its
environment url to `…/commit/<sha>#<module>`, and that url is what `resolve` reads back. A rollback is recorded as the
commit it deployed.

**An image is pushed once per commit.** Image jobs bind the `zipx-images` Environment so each push is recorded too, and
run `<module>/zipxImageMissing` first: it checks every `zipxImageRefs` entry with `docker manifest inspect` and the push
runs only when one is missing. A rebuild is not byte-identical, and an immutable-tag registry rejects a second push.

Every job checks out the plan's commit and exports it as `ZIPX_DEPLOY_SHA`; a build that tags images by commit should
read it before `GITHUB_SHA`. Deploys to one target queue behind each other and are never cancelled.

Generate refuses what the deploy workflow could not run as declared: a capability that is not Graph-scoped, a Verify
capability that needs an image, a need on a capability left in `ci.yml`, a condition that requires a push (a dispatch
never is one), a deploy target with no Environment, and a group named like a target. Release gates do not apply there,
since the dispatch is the gate. Jobs are always one per module and target: a collapsed matrix binds its Environment on
every leg, so a skipped leg would still wait for approval and record a deploy that never happened.
""",
      exampleValue {
        val targets = List(
          Target(TargetName("stg"), environment = Some("staging"), group = Some(TargetGroup("pre-prod"))),
          Target(TargetName("prod"), environment = Some("production")),
        )
        val deploys = List(
          Capability.dockerGraph.copy(gate = Gate.Always),
          Capability.deployGraph(
            participates = _.id == "service",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
            targets = _ => targets,
            gate = Gate.Always,
          ),
        )
        DeployWorkflow.render(libGraph, deploys, config, DeployWorkflow.ImagesEnvironment).yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("workflow_dispatch:"),
          yaml.contains("- pre-prod"),
          yaml.contains("sbt zipxDeployPlan"),
          yaml.contains("sbt \"service/zipxImageMissing\""),
          yaml.contains("steps.image-tags.outputs.missing == 'true'"),
          yaml.contains("contains(fromJson(needs.resolve.outputs.targets)['prod'], 'service')"),
          yaml.contains("name: production"),
          yaml.contains("/commit/${{ needs.resolve.outputs.sha }}#service"),
          yaml.contains("cancel-in-progress: false"),
        )
      ),
    ),
  )
end DockerAndDeploy
