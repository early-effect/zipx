package zipx.docs

import neotype.unwrap
import specular.*
import specular.ziotest.DocSpecSuite
import zipx.aws.*
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.docs.DocsFixtures.*
import zipx.docs.DocsRender.yaml
import zipx.github.ZipxGitHubPackages
import zipx.maven.ZipxMaven
import zipx.agent.{AgentDaemon, AgentEnv, AgentImage, AgentPins, CursorEnv}
import zipx.specular.ZipxDocs
import zio.test.*

object Packs extends DocSpecSuite:

  private val registry = EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1"))

  def doc = page("Packs")(
    md"""
Skip this page until you are ready to publish or deploy. A **pack** is a ready-made path: one line in the build.
Secret *names* live in Scala; values stay in GitHub.

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
destination; you only name secrets in code, values stay in GitHub. `Ship` rows release from `zipx-release.yml`
instead; see **Release workflows** below.
""",
    section("ZipxCentral")(
      md"""
```scala
// Aggregate (preferred for libraries / dogfood)
zipxCapabilities += ZipxCentral.release   // GPG import + publishSigned; sonaRelease

// Append without restating gpg-import (withExtraSteps would replace it)
zipxCapabilities += ZipxCentral.release.plusExtraSteps(publishCleanFull)
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
    section("Release workflows")(
      md"""
A build with `Ship` / `ShipGroup` rows publishes from `zipx-release.yml`, never from `ci.yml`. The workflow is one
registry. These three are the same shape: a `ships` field that opens on `all`, one sbt session, one deployment.
Signing and `sonaRelease` stay on Central. GitHub Packages and a two-URL Maven repository `publish` unsigned. How a
run chooses ships, and why one run is one deployment: **Snapshots and releases**.

```scala
zipxReleaseWorkflow := Some(ZipxCentral.releases)
zipxCapabilities += ZipxCentral.snapshots   // each merge: unreleased rows at <row>-<sha>-SNAPSHOT, unsigned

zipxReleaseWorkflow := Some(
  ZipxGitHubPackages.releases("iterable", "maven-packages", token = secret"GH_PACKAGES_TOKEN")
)

zipxReleaseWorkflow := Some(
  ZipxMaven.releases(
    snapshots = "https://acme.artifactory.example/maven-snapshots",
    releases  = "https://acme.artifactory.example/maven-releases",
    username  = secret"MAVEN_USERNAME",
    password  = secret"MAVEN_PASSWORD",
  )
)
```

A bearer token is the other Maven constructor:
`ZipxMaven.releases(snapshots, releases, token = secret"CODEARTIFACT_TOKEN")`. A fixed username such as CodeArtifact's
`aws` is `EnvValue.plain("aws")` on the username/password constructor. Minting that token is a workflow step the build
adds, the same way Central imports a signing key. The registry does not learn AWS.
""",
      exampleValue {
        ReleaseWorkflow.render(ZipxCentral.releases, config, TagScheme.PerRow).yaml
      }.assert(yml =>
        assertTrue(
          yml.contains("Import signing key"),
          yml.contains("SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}"),
          yml.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""),
          yml.contains("ships:"),
          yml.contains("default: all"),
        )
      ),
      exampleValue {
        ReleaseWorkflow
          .render(
            ZipxGitHubPackages.releases("iterable", "maven-packages", token = secret"GH_PACKAGES_TOKEN"),
            config,
            TagScheme.PerRow,
          )
          .yaml
      }.assert(yml =>
        assertTrue(
          yml.contains("ships:"),
          yml.contains("default: all"),
          yml.contains("GH_PACKAGES_TOKEN: ${{ secrets.GH_PACKAGES_TOKEN }}"),
          yml.contains("packages: write"),
          !yml.contains("sonaRelease"),
          !yml.contains("PGP_"),
          !yml.contains("Import signing key"),
        )
      ),
      exampleValue {
        ReleaseWorkflow
          .render(
            ZipxMaven.releases(
              snapshots = "https://acme.artifactory.example/maven-snapshots",
              releases = "https://acme.artifactory.example/maven-releases",
              username = secret"MAVEN_USERNAME",
              password = secret"MAVEN_PASSWORD",
            ),
            config,
            TagScheme.PerRow,
          )
          .yaml
      }.assert(yml =>
        assertTrue(
          yml.contains("ships:"),
          yml.contains("default: all"),
          yml.contains("MAVEN_USERNAME: ${{ secrets.MAVEN_USERNAME }}"),
          yml.contains("MAVEN_PASSWORD: ${{ secrets.MAVEN_PASSWORD }}"),
          !yml.contains("sonaRelease"),
          !yml.contains("PGP_"),
          !yml.contains("Import signing key"),
          !yml.contains("packages: write"),
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

Thin CI wiring (`packages: write` + token + `PUBLISH_GITHUB_PACKAGES=true`) for a build that publishes without ships.
`Ship` rows use `ZipxGitHubPackages.releases` in **Release workflows** instead. **sbt** owns `publishTo` / Credentials
on this capability path. See **Job conditions** for fork gates and multi-publish recipes.
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
          yaml.contains("uses: ./.github/actions/zipx-aws-login"),
          yaml.contains("role-env: AWS_ROLE_TO_ASSUME"),
          yaml.contains("region-env: AWS_REGION"),
          yaml.contains("login-ecr: \"true\""),
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
          yaml.contains("uses: ./.github/actions/zipx-aws-login"),
          yaml.contains("name-suffix: us"),
          yaml.contains("name-suffix: eu"),
          yaml.contains("role-env: ZIPX_EU_AWS_ROLE_TO_ASSUME"),
          yaml.contains("region-env: ZIPX_EU_AWS_REGION"),
          yaml.contains("account-env: ZIPX_US_AWS_ACCOUNT_ID"),
          yaml.contains("login-ecr: \"true\""),
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
than pushed. The default branch gets the moving tags:
""",
      exampleValue {
        ImageTag.forCommit("1.4.2", "abc1234", branch = "main")
      }.assert(tags => assertTrue(tags == Right(List("1.4.2-abc1234", "1.4.2-main-abc1234", "1.4.2-main-latest")))),
      md"""
Any other branch gets only the immutable one, because a moving tag on a feature branch is a race between two PRs
pushing the same name:
""",
      exampleValue {
        ImageTag.forCommit("1.4.2", "abc1234", branch = "feat-x")
      }.assert(tags => assertTrue(tags == Right(List("1.4.2-abc1234")))),
      md"""
A `/` is refused rather than silently replaced: `example:main-feat/x` parses as a different *repository*, so the image
would publish where nothing deploys from while the build stayed green:
""",
      exampleValue {
        ImageTag.branchCommit("1.4.2", "feat/x", "abc1234")
      }.assert(tag => assertTrue(tag.left.exists(_.contains("ImageTag.slug")))),
      md"""
`ImageTag.slug` is there when mangling is what you want, and it truncates to the registry's limit rather than producing
a name ECR rejects:
""",
      exampleValue {
        ImageTag.slug("feat/x")
      }.assert(tag => assertTrue(tag == Right("feat-x"))),
      md"""
### Pinning the action

`aws-actions/configure-aws-credentials` is an **extra** pin (see **Action pins**), not a typed field, because zipx's own
planner never emits it: it arrives through this pack, so pinning it must not wait on a zipx release. The pack carries a
SHA-pinned fallback when the jar has no extra pin. This repository lists both as catalog rows so `zipxActionUpdate`
owns them and jar defaults ship the SHAs. A consumer overlays the same way to move ahead of a zipx release:

```scala
val awsCredentials = Action(
  "aws-actions/configure-aws-credentials",
  "v6.2.3",
  sha = "<40-hex>",
)
val ecrLogin = Action("aws-actions/amazon-ecr-login", "v2.1.6", sha = "<40-hex>")
```

`zipxActionUpdate` bumps them like any other Action. Overlay keys extra by `owner/repo`, and the pack looks up by that
prefix.
""",
    ),
    section("Cloud agent")(
      md"""
`zipxAgentEnv` renders the Cursor cloud agent from the same toolchain CI already uses. Default off. `docker` is
explicit.

```scala
zipxAgentEnv := ZipxAgentEnv.cursor(docker = true)
```

`zipxWorkflowGenerate` writes `.cursor/environment.json`, `.cursor/Dockerfile`, and `.github/zipx-agent/install.sh`.
The Dockerfile and `environment.json` are the only files under `.cursor/`. Ignore the rest of that directory so local
rules and MCP stay untracked:

```
.cursor/*
!.cursor/environment.json
!.cursor/Dockerfile
```

Secret *names* from `zipxEnv` are checked before anything resolves. So is the release
lookup credential: `GITHUB_TOKEN` when the registry reads GitHub's token, otherwise the secret's env name. `install.sh`
fails if any of those variables is empty, and the message says to set each as a Cursor **Runtime Secret**. A Build
Secret is present only while the image is built. `repositoryDependencies` widens the git token. Neither one
authenticates Maven. The warm command is `zipxCacheRehydrateTask`, run as `sbt --server --batch`. Caches and tokens
stay out of the image. The agent's warm disk is the install step, not `actions/cache`.

With `zipxSbtImage` off, the Dockerfile is Ubuntu. The JDK comes from `project/zipx-ci.env` at install time. The sbt
runner reads `project/build.properties`. Ubuntu, Docker CE 28, and the sbt runner are catalog `Pin` vals
(`agent-base`, `agent-docker`, `agent-sbt`) so `zipxPinUpdate` can move them. Docker 29 is refused. `docker = true`
installs Docker CE from apt and starts it with `sudo service docker start`.

With `zipxSbtImage` on, those three pins are unused. Delete them. The agent Dockerfile is the public
`sbtscala/scala-sbt` pin, then the same `RUN` lines the env image publishes, then a daemon when `docker = true`. It
does not `FROM` `ghcr.io/<owner>/<repo>/zipx-env`. Cursor cannot pull that tag. The daemon is the static `dockerd`
from the `docker-cli` pin, plus exact `fuse-overlayfs` and `iptables` pins on feed `agent-daemon` (`ubuntu-suite`,
`fuse-overlayfs`, `iptables`). `ubuntu-suite` names the Ubuntu release those package versions belong to. Apt installs
them from the image's own archives, so a suite that image does not have fails the image build. `start` is
`/usr/local/bin/zipx-dockerd`. With `docker = false` and no derived Dockerfile, `environment.json` sets `image` to
the public pin. With a derived Dockerfile, the agent builds that text. Plain job env (`HOME`, `-Duser.home`, git
`safe.directory`, `COURSIER_TTL`) is written into that Dockerfile. Secrets are not.
""",
      exampleValue {
        CursorEnv
          .render(
            AgentEnv(
              docker = true,
              rehydrate = SbtCommand.unsafeTask("Test/compile"),
              secretEnv = List("REGISTRY_TOKEN"),
              pins = Some(AgentPins(AgentPins.DefaultBase, AgentPins.DefaultDocker, AgentPins.DefaultSbtRunner)),
            )
          )
          .fold(identity, files => files.environmentJson + files.dockerfile.getOrElse(""))
      }.assert { rendered =>
        assertTrue(
          !rendered.contains("context"),
          rendered.contains(""""dockerfile": "Dockerfile""""),
          rendered.contains("bash .github/zipx-agent/install.sh"),
          rendered.contains("sudo service docker start"),
          rendered.contains("fuse-overlayfs"),
          rendered.contains(AgentPins.DefaultDocker),
          !rendered.contains("x86_64"),
        )
      },
      exampleValue {
        val image = "sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_2.x@sha256:" + ("ab" * 32)
        CursorEnv
          .render(
            AgentEnv(
              docker = true,
              rehydrate = SbtCommand.unsafeTask("Test/compile"),
              secretEnv = List("GITHUB_TOKEN"),
              toolchain = Some(
                AgentImage(
                  upstream = image,
                  baked = None,
                  dockerCli = Some("28.5.2"),
                  fuseOverlayfs = Some(AgentDaemon.DefaultFuse),
                  iptables = Some(AgentDaemon.DefaultIptables),
                  env = Map("HOME" -> "/home/sbtuser", "COURSIER_TTL" -> "0s"),
                )
              ),
            )
          )
          .fold(identity, files => files.environmentJson + files.dockerfile.getOrElse("") + files.installSh)
      }.assert { rendered =>
        assertTrue(
          rendered.contains("FROM sbtscala/scala-sbt:eclipse-temurin-21.0.12_8_2.x@sha256:" + ("ab" * 32)),
          rendered.contains("/usr/local/bin/zipx-dockerd"),
          rendered.contains("docker-28.5.2.tgz"),
          rendered.contains("fuse-overlayfs=1.14-1build2"),
          !rendered.contains("ghcr.io"),
          !rendered.contains("gpg"),
          !rendered.contains("service docker start"),
          rendered.contains("Cursor Runtime Secret"),
          rendered.contains("GITHUB_TOKEN"),
          !rendered.contains("export COURSIER_TTL=0s"),
          !rendered.contains("openjdk"),
        )
      },
    ),
  )
end Packs
