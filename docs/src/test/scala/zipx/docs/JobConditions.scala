package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.github.ZipxGitHubPackages
import zio.test.*

/** Typed job `if:` predicates and concrete recipes. */
object JobConditions extends DocSpecSuite:

  def doc = page("Job conditions")(
    md"""
[[JobCondition]] is a typed AST for optional job `if:` filters (fork repo, PR label, branch, repo var, …).
[[Gate]] is still the timeline (`Always` vs `OnReleaseTag`). The planner **ANDs** Gate clauses with capability and
target conditions.

Default on every capability and target: `condition = None` (no extra filter).
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
""",
      exampleValue {
        val footgun = Capability.dockerGraph.copy(
          gate = Gate.OnReleaseTag,
          targets = _ => List(Target("stg", condition = Some(JobCondition.hasPrLabel("deploy-stg")))),
        )
        val graph = libGraph.copy(nodes = libGraph.nodes.map {
          case n if n.id == "service" => n.copy(docker = true)
          case n                      => n
        })
        Planner.plan(graph, List(footgun), config).jobs("docker-service-stg").`if`
      }.assert(cond =>
        assertTrue(
          cond.exists(_.contains("refs/tags/v")),
          cond.exists(_.contains("deploy-stg")),
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
        val cap = Capability.publish.withCondition(JobCondition.repositoryIs("acme/my-fork"))
        Planner.plan(libGraph, List(cap), config).jobs("publish").`if`
      }.assert(cond =>
        assertTrue(
          cond.exists(_.contains("github.repository == 'acme/my-fork'")),
          cond.exists(_.contains("refs/tags/v")),
        )
      ),
    ),
    section("Repo-variable opt-in")(
      md"""
Mechanoid-style: only publish when a repo variable is set.

```scala
ZipxGitHubPackages.sameRepo(
  condition = Some(JobCondition.varNonEmpty("PUBLISH_PACKAGES_REPO")),
)
// or combine with repositoryIs via the factory's repository= / condition=
```
""",
      exampleValue {
        JobCondition.varNonEmpty("PUBLISH_PACKAGES_REPO").render
      }.assert(r => assertTrue(r == "vars.PUBLISH_PACKAGES_REPO != ''")),
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
        val wf = Planner.plan(
          libGraph,
          List(ZipxCentral.release, ZipxGitHubPackages.sameRepo(repository = Some("acme/fork"))),
          config,
        )
        (
          wf.jobs.keySet.toList.sorted,
          wf.jobs("github-packages").env.get("PUBLISH_GITHUB_PACKAGES"),
          wf.jobs("github-packages").permissions.get("packages"),
        )
      }.assert { case (ids, flag, pkgs) =>
        assertTrue(
          ids.contains("publish"),
          ids.contains("github-packages"),
          flag.contains("true"),
          pkgs.contains("write"),
        )
      },
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
    command = n => s"$${n.id}/Docker/publish",
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
        val graph = libGraph.copy(nodes = libGraph.nodes.map {
          case n if n.id == "service" => n.copy(docker = true)
          case n                      => n
        })
        val cap = Capability.custom(
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
        val wf = Planner.plan(graph, List(cap), config)
        (
          wf.jobs("docker-service-stg").`if`,
          wf.jobs("docker-service-prod").`if`,
        )
      }.assert { case (stg, prod) =>
        assertTrue(
          stg.exists(_.contains("deploy-stg")),
          !stg.exists(_.contains("startsWith(github.ref, 'refs/tags/v')")),
          prod.exists(_.contains("startsWith(github.ref, 'refs/tags/v')")),
        )
      },
    ),
    section("Capability-level docker-stg")(
      md"""
Alternate to per-Target conditions: a separate capability name so it does not replace builtin `docker`.

```scala
Capability.dockerGraph.copy(
  name = "docker-stg",
  gate = Gate.Always,
  condition = Some(JobCondition.hasPrLabel("deploy-stg")),
)
```
""",
      exampleValue {
        val cap = Capability.dockerGraph.copy(
          name = "docker-stg",
          gate = Gate.Always,
          condition = Some(JobCondition.hasPrLabel("deploy-stg")),
        )
        cap.condition.map(_.render)
      }.assert(r => assertTrue(r.exists(_.contains("deploy-stg")))),
    ),
    section("Main-only target")(
      md"""
```scala
Target("prod", environment = Some("production"), condition = Some(JobCondition.refIs("refs/heads/main")))
```
""",
      exampleValue {
        JobCondition.refIs("refs/heads/main").render
      }.assert(r => assertTrue(r == "github.ref == 'refs/heads/main'")),
    ),
    section("Raw escape hatch")(
      md"""
```scala
JobCondition.raw("(github.event_name == 'workflow_dispatch')")
```

Prefer typed leaves when possible; `Raw` is for expressions the AST does not cover yet.
""",
      exampleValue {
        JobCondition.raw("always()").render
      }.assert(r => assertTrue(r == "always()")),
    ),
  )
end JobConditions
