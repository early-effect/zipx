package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.docs.DocsRender.yaml
import zipx.github.ZipxGitHubPackages
import zipx.workflow.Render
import zio.test.*

import scala.collection.immutable.ListMap

/** Typed job `if:` predicates and concrete recipes. */
object JobConditions extends DocSpecSuite:

  def doc = page("Job conditions")(
    md"""
Skip until you need a job to run only on some PRs (forks, labels, branches). Most jobs use the default timeline:
tests always, publish on a version tag.

[[JobCondition]] is a typed AST for optional job `if:` filters. [[Gate]] is still the timeline (`Always` vs
`OnReleaseTag`). The planner **ANDs** Gate clauses with capability and target conditions.

```mermaid
flowchart TD
  Cap[Capability] --> Gate[Gate · Always or OnReleaseTag]
  Cap --> Cond[JobCondition · optional]
  Gate --> And[planner AND]
  Cond --> And
  And --> If([job if:])
  class Cap,Gate,Cond warn
  class And,If happy
```

Default on every capability and target: `condition = None` (no extra filter). Prefer `withCondition(...)` to set a
filter, or `andCondition(...)` to layer onto a pack that already ships one (e.g. `ZipxDocs.pages`).
""",
    section("Compose with && and ||")(
      md"""
```scala
val deployDocs =
  JobCondition.onReleaseTag || JobCondition.onWorkflowDispatch

val upstreamOnly =
  JobCondition.repositoryIs("acme/libs") && deployDocs

// Negation:
val notFork = !JobCondition.repositoryIs("acme/other")
```

`JobCondition.and` / `or` / `not` remain available; infix `&&` / `||` / `!` are the usual style. Precedence matches
Boolean ops: `&&` binds tighter than `||` (`a || b && c` ≡ `a || (b && c)`); both are left-associative. Parenthesize
when you mean `(a || b) && c`. Typed leaves also include `eventIs`, `onWorkflowDispatch`, and `onReleaseTag`.
""",
      exampleValue {
        val c = (JobCondition.onReleaseTag || JobCondition.onWorkflowDispatch) &&
          JobCondition.repositoryIs("early-effect/zipx")
        Render.renderMapping(ListMap("if" -> c.render)).yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("workflow_dispatch"),
          yaml.contains("refs/tags/v"),
          yaml.contains("early-effect/zipx"),
        )
      ),
    ),
    section("Defaults and Gate vs condition")(
      md"""
| Capability | Default `Gate` | Default `JobCondition` |
|---|---|---|
| test / testJoined / Layers / Graph | `Always` | `None` |
| publish / docker / deploy | `OnReleaseTag` | `None` |
| ZipxCentral / ZipxGitHubPackages | `OnReleaseTag` | `None` (unless you pass one) |
| ZipxDocs.pages | `Always` | `onReleaseTag` or `onWorkflowDispatch` |

**Important:** Gate and JobCondition are ANDed. A capability with `Gate.OnReleaseTag` will **not** run on a PR even if
a Target has `HasPrLabel`. For stage-on-PR + prod-on-tag, use `Gate.Always` with per-Target conditions, or two
capabilities.

```scala
// Footgun: OnReleaseTag ∧ HasPrLabel still requires a v* tag
Capability.dockerGraph.copy(
  gate = Gate.OnReleaseTag,
  targets = _ => List(Target(TargetName("stg"), condition = Some(JobCondition.hasPrLabel("deploy-stg")))),
)
```
""",
      exampleValue {
        DocsRender.job("docker-service-stg")(
          Capability.dockerGraph.copy(
            gate = Gate.OnReleaseTag,
            targets = _ => List(Target(TargetName("stg"), condition = Some(JobCondition.hasPrLabel("deploy-stg")))),
          )
        )(using dockerLibGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("refs/tags/v"),
          yaml.contains("deploy-stg"),
        )
      ),
    ),
    section("A conjunction that can never be true is refused")(
      md"""
The footgun above is worth catching, and where zipx *can* prove the conjunction is never true, it refuses to generate
the workflow rather than emitting a job that silently never runs:

```scala
// Fails `zipxWorkflowGenerate`: no ref both starts with `refs/tags/v` and equals `refs/heads/main`
Capability.publish.copy(
  gate = Gate.OnReleaseTag,
  condition = Some(JobCondition.refIs("refs/heads/main")),
)
```

The error names the capability (and the target, when the clause is on one), quotes both rendered clauses, and says
which fact makes them incompatible. That is deliberate: the gate typically comes from a pack, the condition from
`build.sbt`, and the target condition from a `project/*.scala` list, so nobody reading one file sees the conjunction.
`examples/monorepo` shipped exactly this bug: a `deploy-prod` job gated on a release tag *and* on `refs/heads/main`.

**What is checked** is a small decidable subset over the single-valued `github` contexts, inside a conjunction:

| Shape | Why it is refused |
|---|---|
| `refIs(a)` with `refIs(b)`, `a != b` | `github.ref` holds one value per run |
| `eventIs` / `repositoryIs` likewise | same, per context |
| `refIs(r)` with `refStartsWith(p)`, `r` not under `p` | that ref does not start with that prefix |
| `refStartsWith(p)` with `refStartsWith(q)`, neither a prefix of the other | no ref starts with both |
| a clause and its own negation | one negates the other |

**What is not checked**, and passes in silence: anything under `||` (`Any`), `Raw`, `varNonEmpty`, `hasPrLabel`, and two
*negated* claims (excluding two values always leaves a third). `Gate.OnReleaseTag` with `hasPrLabel` is therefore still
a footgun zipx cannot catch, which is why the section above exists.

The subset is narrow on purpose. **An unsound rejection is worse than a missed one:** a missed contradiction is the
status quo, while a wrong rejection is a build that cannot generate its own CI and no way for the author to argue with
it. Nesting does not help it escape, though: `All` and `!(a || b)` are flattened first, so a contradiction buried in a
nested conjunction is still found.
""",
      exampleValue {
        val contradiction = Capability.deploy(
          participates = _.id == "service",
          command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
          targets = _ => List(Target(TargetName("prod"), condition = Some(JobCondition.refIs("refs/heads/main")))),
          needsCapabilities = Nil,
          gate = Gate.OnReleaseTag,
        )
        scala.util
          .Try(DocsRender.plan(contradiction))
          .fold(_.getMessage, _ => "planned (no error)")
      }.assert(error =>
        assertTrue(
          error.contains("can never run"),
          error.contains("target 'prod'"),
          error.contains("refs/tags/v"),
          error.contains("refs/heads/main"),
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
  ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.repositoryIs("acme/my-fork"))),
)
```
""",
      exampleValue {
        DocsRender.jobs("publish", "github-packages")(
          ZipxCentral.release,
          ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.repositoryIs("acme/fork"))),
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
   `types: [opened, synchronize, reopened, labeled]` (zipx does not auto-emit that yet; set triggers in a companion
   workflow or extend PlanConfig later).
2. Use a custom docker capability with **`Gate.Always`** and **per-Target** conditions.
3. Point Target env at the ECR registry + OIDC role; keep `Docker/publish` as the command (native-packager / `REGISTRY`
   still choose the repository URL).

```scala
zipxCapabilities += Capability
  .custom(
    name = CapabilityName("docker"),
    command = cmd"$${Docker / publish}",
    participates = _.docker,
    phase = Phase.Publish,
    gate = Gate.Always,
    targets = _ => List(
      Target(
        name = TargetName("stg"),
        env = Map(
          "REGISTRY"    -> EnvValue.plain("111.dkr.ecr.us-east-1.amazonaws.com/stg"),
          "DEPLOY_ROLE" -> secret"STG_REGISTRY_ROLE",
        ),
        condition = Some(JobCondition.hasPrLabel("deploy-stg")),
      ),
      Target(
        name = TargetName("prod"),
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
      Step
        .uses("aws-actions/configure-aws-credentials@v6")
        .named("Login to registry")
        .withInput("role-to-assume", Expr.env("DEPLOY_ROLE"))
        .build
    )
  )
```

Add label `deploy-stg` on the PR → only the stg job's `if` is true; prod still waits for a `v*` tag.
""",
      exampleValue {
        val cap = Capability
          .custom(
            name = Capability.DockerName,
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("Docker/publish")),
            participates = _.docker,
            gate = Gate.Always,
            targets = _ =>
              List(
                Target(TargetName("stg"), condition = Some(JobCondition.hasPrLabel("deploy-stg"))),
                Target(TargetName("prod"), condition = Some(JobCondition.refStartsWith("refs/tags/v"))),
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
  .copy(name = CapabilityName("docker-stg"), gate = Gate.Always)
  .withCondition(JobCondition.hasPrLabel("deploy-stg"))
```
""",
      exampleValue {
        DocsRender.job("docker-stg-service")(
          Capability.dockerGraph
            .copy(name = CapabilityName("docker-stg"), gate = Gate.Always)
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
  TargetName("prod"),
  environment = Some("production"),
  condition = Some(JobCondition.refIs("refs/heads/main")),
)
```
""",
      exampleValue {
        DocsRender.job("deploy-prod")(
          Capability.deploy(
            participates = _.id == "service",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
            targets = _ =>
              List(
                Target(
                  TargetName("prod"),
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
JobCondition.raw("always()")
```

Prefer typed leaves and `&&` / `||` when possible; `Raw` is for expressions the AST does not cover yet.
""",
      exampleValue {
        Render.renderMapping(ListMap("if" -> JobCondition.raw("always()").render)).yaml
      }.assert(yaml => assertTrue(yaml.contains("if: always()"))),
    ),
  )
end JobConditions
