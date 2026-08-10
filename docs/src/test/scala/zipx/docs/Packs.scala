package zipx.docs

import neotype.unwrap
import specular.*
import specular.ziotest.DocSpecSuite
import zipx.aws.*
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.github.ZipxGitHubPackages
import zipx.specular.ZipxDocs
import zio.test.*

/** Early-effect paved paths as capabilities. */
object Packs extends DocSpecSuite:

  private val registry = EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1"))

  def doc = page("Packs")(
    md"""
Org paved paths are capabilities (secret *names* only; values stay in GitHub):

```mermaid
flowchart TD
  Caps([zipxCapabilities]) --> Central[ZipxCentral.release]
  Caps --> Packages[ZipxGitHubPackages]
  Caps --> Docs[ZipxDocs.pages]
  Caps --> Aws[ZipxAws.dockerPublish]
  Central --> Sonatype[(Maven Central)]
  Packages --> GH[(GitHub Packages)]
  Docs --> Pages[(GitHub Pages)]
  Aws --> Ecr[(ECR)]
  class Caps warn
  class Central,Packages,Docs,Aws,Sonatype,GH,Pages,Ecr happy
```

Amber is the knob (`zipxCapabilities += …`). Each green pack is a paved Publish/docs capability that lands in its
destination; you only name secrets in code, values stay in GitHub.
""",
    section("ZipxCentral")(
      md"""
```scala
// Aggregate (preferred for libraries / dogfood)
zipxCapabilities += ZipxCentral.release   // GPG import + publishSigned; sonaRelease

// Graph escape hatch
zipxCapabilities ++= Seq(ZipxCentral.publishSigned, ZipxCentral.releaseOnce)
```
""",
      exampleValue {
        DocsRender.job("publish")(ZipxCentral.release)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("publishSigned; sonaRelease"),
          yaml.contains("SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}"),
          yaml.contains("Import signing key"),
        )
      ),
    ),
    section("ZipxGitHubPackages")(
      md"""
```scala
zipxCapabilities ++= Seq(
  ZipxCentral.release,
  ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.repositoryIs("acme/my-fork"))),
)
// Shared registry PAT: ZipxGitHubPackages.sharedRegistry(token = secret"GH_PACKAGES_TOKEN")
```

Thin CI wiring (`packages: write` + token + `PUBLISH_GITHUB_PACKAGES=true`). **sbt** owns `publishTo` / Credentials.
See **Job conditions** for fork gates and multi-publish recipes.
""",
      exampleValue {
        DocsRender.job("github-packages")(
          ZipxGitHubPackages.sameRepo(condition = Some(JobCondition.repositoryIs("acme/fork")))
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("packages: write"),
          yaml.contains("PUBLISH_GITHUB_PACKAGES"),
          yaml.contains("acme/fork"),
        )
      ),
    ),
    section("ZipxDocs")(
      md"""
```scala
zipxCapabilities += ZipxDocs.pages()
zipxWorkflowDispatch := true  // Actions → Run workflow (docs without a release tag)

// Layer a fork gate; andCondition keeps the built-in tag|dispatch filter:
zipxCapabilities += ZipxDocs.pages().andCondition(JobCondition.repositoryIs("acme/libs"))
```

`ZipxDocs.pages` calls the org reusable workflow on **`v*` tags or `workflow_dispatch`**. Verify is skipped on
dispatch so a manual run is docs-cheap; publish stays tag-only. No hand-rolled `docs.yml`.
""",
      exampleValue {
        DocsRender.job("docs")(ZipxDocs.pages())(using GraphFixture(Nil))
      }.assert(yaml =>
        assertTrue(
          yaml.contains(ZipxDocs.ReusableWorkflow.unwrap),
          yaml.contains("sbt-project: docs"),
          yaml.contains("pages: write"),
          yaml.contains("workflow_dispatch"),
        )
      ),
    ),
    section("ZipxAws")(
      md"""
`zipx-aws` is the AWS paved path: assume a role by OIDC, push to ECR. It holds no credentials and no account numbers of
its own; you pass a validated account id and region, and a secret *name*.

```scala
// project/plugins.sbt already has sbt-zipx; the pack ships with it.
import zipx.aws.*

val registry = EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1"))

zipxCapabilities += ZipxAws.dockerPublish(registry, role = secret"DEPLOY_ROLE")
```

That one line is `Capability.docker` plus three things it is easy to get wrong by hand: `id-token: write` (naming any
permission drops the default set, so `contents: read` has to come back with it), the job `env:` the login step reads,
and the login step itself.
""",
      exampleValue {
        DocsRender.job("docker")(ZipxAws.dockerPublish(registry, role = secret"DEPLOY_ROLE"))
      }.assert(yaml =>
        assertTrue(
          yaml.contains("id-token: write"),
          yaml.contains("contents: read"),
          yaml.contains("AWS_REGION: us-east-1"),
          yaml.contains("""AWS_ECR_REGISTRY: "111122223333.dkr.ecr.us-east-1.amazonaws.com""""),
          yaml.contains("role-to-assume: ${{ env.AWS_ROLE_TO_ASSUME }}"),
          yaml.contains("aws-region: ${{ env.AWS_REGION }}"),
        )
      ),
      md"""
### The region is a constructor parameter, not a field you might forget

`EcrRegistry` derives its host from the account and the region, so there is no registry value with no region for the
login step to omit `aws-region` from. `configure-aws-credentials` requires that input, and omitting it fails on the
runner reporting a *credentials* problem, which sends the reader to the role's trust policy instead of to the missing
line.

```scala
EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1")).host
// 111122223333.dkr.ecr.us-east-1.amazonaws.com

EcrRegistry(AwsAccountId("111122223333"))  // does not compile: no such constructor
AwsAccountId("11112222333")                // does not compile: 12 digits
AwsRegion("us-east1")                      // does not compile
```

The account id checks length (11 digits still yields a syntactically fine host, so the failure would otherwise surface
as DNS on the runner) and the region checks shape rather than membership of a list, so a region added after this release
still works.
""",
      md"""
### Steps, env, and targets

| You want | Reach for |
| --- | --- |
| Just the role, for a non-ECR job | `ZipxAws.oidcLoginSteps` + `ZipxAws.oidcPermissions` + `ZipxAws.registryEnv` |
| OIDC plus ECR docker login (including `Docker / publish`) | `ZipxAws.ecrLoginSteps` |
| One repository rather than a whole account in `AWS_ECR_REGISTRY` | `ZipxAws.imageEnv(registry.image(EcrRepository("team/svc")), role)` |
| Several registries for one image | `ZipxAws.dockerPublishAll(registries)` |
| Separate accounts with separate **approvals** | `ZipxAws.registryTargets(…)` via `withTargets` |

The bundle reads its role and region from the job's `env:`, which is what lets one bundle serve every destination: a
per-target `env` block changes which account the same steps log into.

Those last two rows are the same list of registries and two different shapes, so pick by asking whether the destinations
need separate *approval*:

```scala
// One job: one image built once, one login per registry, one push per dockerAliases entry.
zipxCapabilities += ZipxAws.dockerPublishAll(
  List(
    (TargetName("us"), EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1")), secret"US_ROLE"),
    (TargetName("eu"), EcrRegistry(AwsAccountId("444455556666"), AwsRegion("eu-west-1")), secret"EU_ROLE"),
  )
)
```
""",
      exampleValue {
        DocsRender.job("docker")(
          ZipxAws.dockerPublishAll(
            List(
              (TargetName("us"), registry, secret"US_ROLE"),
              (TargetName("eu"), EcrRegistry(AwsAccountId("444455556666"), AwsRegion("eu-west-1")), secret"EU_ROLE"),
            )
          )
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("Assume AWS role (OIDC, us)"),
          yaml.contains("Assume AWS role (OIDC, eu)"),
          yaml.contains("Log in to ECR (us)"),
          yaml.contains("Log in to ECR (eu)"),
          yaml.contains("role-to-assume: ${{ env.ZIPX_EU_AWS_ROLE_TO_ASSUME }}"),
          yaml.contains("aws-region: ${{ env.ZIPX_EU_AWS_REGION }}"),
          yaml.contains("registries: ${{ env.ZIPX_US_AWS_ACCOUNT_ID }}"),
          yaml.contains("ZIPX_US_AWS_ROLE_TO_ASSUME: ${{ secrets.US_ROLE }}"),
          yaml.contains("ZIPX_US_AWS_ACCOUNT_ID:"),
        )
      ),
      md"""
`registryTargets` with `withTargets` is the shape to reach for **last**, and the cost of reaching for it by mistake is
multiplicative: `Docker / publish` pushes every `dockerAliases` entry from one build, so a target per registry costs N*M
jobs for M modules, each rebuilding the same image, and stops guaranteeing the registries hold identical bytes. Point
`dockerAliases` at every registry (`EcrImage.taggedAll` builds that list from the same `EcrRegistry` values, so the two
sides cannot drift) and let `dockerPublishAll` set up the credentials for each. See **Docker and deploy** for the
general rule and what a shared job refuses.
""",
      exampleValue {
        val targets = ZipxAws.registryTargets(
          List(
            (TargetName("us"), registry, secret"US_ROLE"),
            (TargetName("eu"), EcrRegistry(AwsAccountId("444455556666"), AwsRegion("eu-west-1")), secret"EU_ROLE"),
          )
        )
        DocsRender.jobs("docker-us", "docker-eu")(
          ZipxAws.dockerPublish(registry, secret"DEPLOY_ROLE").withTargets(_ => targets)
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("docker-us:"),
          yaml.contains("docker-eu:"),
          yaml.contains("AWS_ROLE_TO_ASSUME: ${{ secrets.US_ROLE }}"),
          yaml.contains("AWS_REGION: eu-west-1"),
        )
      ),
      md"""
### Image tags

`ImageTag` is the registry's own rule, so a tag that would go somewhere unexpected is refused where it is written rather
than pushed:

```scala
ImageTag.forCommit(version, sha, branch)
// on main:      List("1.4.2-abc1234", "1.4.2-main-abc1234", "1.4.2-main-latest")
// on feat-x:    List("1.4.2-abc1234")

ImageTag.branchCommit("1.4.2", "feat/x", "abc1234")  // Left: a tag may not contain '/'
ImageTag.slug("feat/x")                              // Right("feat-x"): the opt-in mangle
```

The moving tags land only on the default branch, because a moving tag on a feature branch is a race between two PRs
pushing the same name. And a `/` is refused rather than silently replaced: `example:main-feat/x` parses as a different
*repository*, so the image would publish where nothing deploys from while the build stayed green. `ImageTag.slug` is
there when mangling is what you want, and it truncates to the registry's limit rather than producing a name ECR rejects.
""",
      md"""
### Pinning the action

`aws-actions/configure-aws-credentials` is an **extra** pin (see **Action pins**), not a typed field, because zipx's own
planner never emits it: it arrives through this pack, so pinning it must not wait on a zipx release. The pack carries a
SHA-pinned fallback, and `.github/zipx/action-pins.yml` takes ownership of the version:

```yaml
extra:
  configureAwsCredentials: aws-actions/configure-aws-credentials@<sha> # v6.2.3
  amazonEcrLogin: aws-actions/amazon-ecr-login@<sha> # v2.1.6
```

Once the key is present, `zipxActionsPull` bumps it from a Dependabot edit to the workflow like any other pin.
""",
    ),
  )
end Packs
