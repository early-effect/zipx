package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.github.ZipxGitHubPackages
import zipx.workflow.Render
import zio.test.*

import scala.collection.immutable.ListMap

/** Typed job `if:` predicates and concrete recipes. */
object JobConditions extends DocSpecSuite:

  def doc = page("Job conditions")(
    md"""
[[JobCondition]] is a typed AST for optional job `if:` filters (fork repo, PR label, branch, repo var, …).
[[Gate]] is still the timeline (`Always` vs `OnReleaseTag`). The planner **ANDs** Gate clauses with capability and
target conditions.

Default on every capability and target: `condition = None` (no extra filter). Prefer `withCondition(...)` on
built-ins and pack vals.
""",
    section("Defaults and Gate vs condition")(
      md"""
| Capability | Default `Gate` | Default `JobCondition` |
|---|---|---|
| test / testJoined / Layers / Graph | `Always` | `None` |
| publish / docker / deploy | `OnReleaseTag` | `None` |
| ZipxCentral / ZipxGitHubPackages | `OnReleaseTag` | `None` (unless you pass one) |

**Important:** Gate and JobCondition are ANDed. A capability with `Gate.OnReleaseTag` will **not** run on a PR even if
a Target has `HasPrLabel`. For stage-on-PR + prod-on-tag, use `Gate.Always` with per-Target conditions, or two
capabilities.

```scala
// Footgun: OnReleaseTag ∧ HasPrLabel still requires a v* tag
Capability.dockerGraph.copy(
  gate = Gate.OnReleaseTag,
  targets = _ => List(Target("stg", condition = Some(JobCondition.hasPrLabel("deploy-stg")))),
)
```
""",
      exampleValue {
        DocsRender.job("docker-service-stg")(
          Capability.dockerGraph.copy(
            gate = Gate.OnReleaseTag,
            targets = _ => List(Target("stg", condition = Some(JobCondition.hasPrLabel("deploy-stg")))),
          )
        )(using dockerLibGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("refs/tags/v"),
          yaml.contains("deploy-stg"),
        )
      ),
    ),
    section("Fork / upstream publish gate")(
      md"""
```scala
zipxCapabilities += Capability.publish.withCondition(
  JobCondition.repositoryIs("acme/my-fork"),
)
```
""",
      exampleValue {
        DocsRender.job("publish")(
          Capability.publish.withCondition(JobCondition.repositoryIs("acme/my-fork"))
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("github.repository == 'acme/my-fork'"),
          yaml.contains("refs/tags/v"),
        )
      ),
    ),
    section("Repo-variable opt-in")(
      md"""
Mechanoid-style: only publish when a repo variable is set.

```scala
zipxCapabilities += ZipxGitHubPackages.sameRepo(
  condition = Some(JobCondition.varNonEmpty("PUBLISH_PACKAGES_REPO")),
)
```
""",
      exampleValue {
        DocsRender.job("github-packages")(
          ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.varNonEmpty("PUBLISH_PACKAGES_REPO")))
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("vars.PUBLISH_PACKAGES_REPO != ''"),
          yaml.contains("PUBLISH_GITHUB_PACKAGES: \"true\"") || yaml.contains("PUBLISH_GITHUB_PACKAGES: true"),
        )
      ),
    ),
    section("Multi-publish: Central + GitHub Packages")(
      md"""
Distinct capability names coexist. zipx wires permissions + token env; **sbt** owns `publishTo` / Credentials when
`PUBLISH_GITHUB_PACKAGES=true`.

```scala
zipxCapabilities ++= Seq(
  ZipxCentral.release,
  ZipxGitHubPackages.sameRepo(repository = Some("acme/my-fork")),
)
```
""",
      exampleValue {
        DocsRender.jobs("publish", "github-packages")(
          ZipxCentral.release,
          ZipxGitHubPackages.sameRepo(repository = Some("acme/fork")),
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("publish:"),
          yaml.contains("github-packages:"),
          yaml.contains("PUBLISH_GITHUB_PACKAGES"),
          yaml.contains("packages: write"),
          yaml.contains("acme/fork"),
        )
      ),
    ),
    section("PR → stage/dev ECR before merge")(
      md"""
Publish container images to stg/dev ECR from a labeled PR **without** waiting for merge or a release tag.

1. Ensure `pull_request` triggers fire (zipx default). If labels are added after open, also allow
   `types: [opened, synchronize, reopened, labeled]` (zipx does not auto-emit that yet — set triggers in a companion
   workflow or extend PlanConfig later).
2. Use a custom docker capability with **`Gate.Always`** and **per-Target** conditions.
3. Point Target env at the ECR registry + OIDC role; keep `Docker/publish` as the command (native-packager / `REGISTRY`
   still choose the repository URL).

```scala
zipxCapabilities += Capability
  .custom(
    name = "docker",
    command = cmd"$${Docker / publish}",
    participates = _.docker,
    phase = Phase.Publish,
    gate = Gate.Always,
    targets = _ => List(
      Target(
        name = "stg",
        env = Map(
          "REGISTRY"    -> EnvValue.plain("111.dkr.ecr.us-east-1.amazonaws.com/stg"),
          "DEPLOY_ROLE" -> secret"STG_REGISTRY_ROLE",
        ),
        condition = Some(JobCondition.hasPrLabel("deploy-stg")),
      ),
      Target(
        name = "prod",
        env = Map(
          "REGISTRY"    -> EnvValue.plain("111.dkr.ecr.us-east-1.amazonaws.com/prod"),
          "DEPLOY_ROLE" -> secret"PROD_REGISTRY_ROLE",
        ),
        condition = Some(JobCondition.refStartsWith("refs/tags/v")),
      ),
    ),
    permissions = Map("id-token" -> "write", "contents" -> "read"),
  )
  .copy(
    extraSteps = _ => List(
      Step(
        name = Some("Login to registry"),
        uses = Some("aws-actions/configure-aws-credentials@v6"),
        `with` = Map("role-to-assume" -> "$${{ env.DEPLOY_ROLE }}"),
      )
    )
  )
```

Add label `deploy-stg` on the PR → only the stg job's `if` is true; prod still waits for a `v*` tag.
""",
      exampleValue {
        val cap = Capability
          .custom(
            name = "docker",
            command = n => s"${n.id}/Docker/publish",
            participates = _.docker,
            gate = Gate.Always,
            targets = _ =>
              List(
                Target("stg", condition = Some(JobCondition.hasPrLabel("deploy-stg"))),
                Target("prod", condition = Some(JobCondition.refStartsWith("refs/tags/v"))),
              ),
            permissions = Map("id-token" -> "write"),
          )
        DocsRender.jobs("docker-service-stg", "docker-service-prod")(cap)(using dockerLibGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("deploy-stg"),
          yaml.contains("docker-service-stg:"),
          yaml.contains("docker-service-prod:"),
          yaml.contains("startsWith(github.ref, 'refs/tags/v')"),
        )
      ),
    ),
    section("Capability-level docker-stg")(
      md"""
Alternate to per-Target conditions: a separate capability name so it does not replace builtin `docker`.

```scala
zipxCapabilities += Capability.dockerGraph
  .copy(name = "docker-stg", gate = Gate.Always)
  .withCondition(JobCondition.hasPrLabel("deploy-stg"))
```
""",
      exampleValue {
        DocsRender.job("docker-stg-service")(
          Capability.dockerGraph
            .copy(name = "docker-stg", gate = Gate.Always)
            .withCondition(JobCondition.hasPrLabel("deploy-stg"))
        )(using dockerLibGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("docker-stg-service:"),
          yaml.contains("deploy-stg"),
          !yaml.contains("refs/tags/v"),
        )
      ),
    ),
    section("Main-only target")(
      md"""
```scala
Target(
  "prod",
  environment = Some("production"),
  condition = Some(JobCondition.refIs("refs/heads/main")),
)
```
""",
      exampleValue {
        DocsRender.job("deploy-prod")(
          Capability.deploy(
            participates = _.id == "service",
            command = n => s"${n.id}/promote",
            targets = _ =>
              List(
                Target(
                  "prod",
                  environment = Some("production"),
                  condition = Some(JobCondition.refIs("refs/heads/main")),
                )
              ),
            needsCapabilities = Nil,
            gate = Gate.Always,
          )
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("environment: production"),
          yaml.contains("github.ref == 'refs/heads/main'"),
        )
      ),
    ),
    section("Raw escape hatch")(
      md"""
```scala
JobCondition.raw("(github.event_name == 'workflow_dispatch')")
```

Prefer typed leaves when possible; `Raw` is for expressions the AST does not cover yet.
""",
      exampleValue {
        Render.renderMapping(ListMap("if" -> JobCondition.raw("always()").render))
      }.assert(yaml => assertTrue(yaml.contains("if: always()"))),
    ),
  )
end JobConditions
