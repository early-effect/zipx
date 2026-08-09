package zipx.aws

import neotype.unwrap
import zio.test.*
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.workflow.ActionRef

object ZipxAwsSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val registry = EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1"))
  private val role     = secret"DEPLOY_ROLE"

  private val graph = sampleGraph.mapNodes {
    case n if n.id == "serviceA" => n.copy(docker = true)
    case n                       => n
  }

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.0.0"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  private def stepContext(actions: ActionPins = ActionPins.Defaults) =
    StepContext(ModuleNode(id = ModuleId("serviceA")), None, matrixed = false, actions = actions)

  def spec = suite("ZipxAws")(
    suite("the login step passes both role-to-assume and aws-region")(
      // #65: omitting aws-region makes configure-aws-credentials fail on the runner reporting a *credentials* problem,
      // which sends the reader to the trust policy rather than to the missing input.
      test("both inputs are present, reading the job env the pack also sets") {
        val step = ZipxAws.oidcLoginSteps(stepContext()).head
        assertTrue(
          step.`with`.get("role-to-assume").contains("${{ env.AWS_ROLE_TO_ASSUME }}"),
          step.`with`.get("aws-region").contains("${{ env.AWS_REGION }}"),
          step.name.contains("Assume AWS role (OIDC)"),
        )
      },
      test("registryEnv supplies exactly the keys the login step reads, plus the registry host") {
        val env = ZipxAws.registryEnv(registry, role)
        assertTrue(
          env.get(ZipxAws.RoleEnv).map(_.render).contains("${{ secrets.DEPLOY_ROLE }}"),
          env.get(ZipxAws.RegionEnv).map(_.render).contains("us-east-1"),
          env.get(ZipxAws.RegistryEnv).map(_.render).contains("111122223333.dkr.ecr.us-east-1.amazonaws.com"),
        )
      },
      test("imageEnv carries <host>/<repository> instead of the bare host") {
        val env = ZipxAws.imageEnv(registry.image(EcrRepository("team/example")), role)
        assertTrue(
          env.get(ZipxAws.RegistryEnv).map(_.render) ==
            Some("111122223333.dkr.ecr.us-east-1.amazonaws.com/team/example")
        )
      },
      test("the bundle is a named Steps with no raw escape hatch to warn about") {
        assertTrue(
          ZipxAws.oidcLoginSteps.name == "aws-oidc-login",
          ZipxAws.oidcLoginSteps.rawFragments.isEmpty,
          ZipxAws.ecrLoginSteps.name == "aws-oidc-login+aws-ecr-login",
        )
      },
    ),
    suite("the action is an extra pin, so pinning it needs no zipx release")(
      test("with no pin file entry, the pack's own SHA-pinned fallback is used") {
        assertTrue(
          ZipxAws.credentialsAction(ActionPins.Defaults) == ZipxAws.DefaultCredentialsAction,
          ZipxAws.DefaultCredentialsAction.unwrap.startsWith("aws-actions/configure-aws-credentials@"),
          // A fallback that is itself unpinned would defeat the point; ActionRef refuses one, but assert it anyway
          // since this is the one ref a consumer never writes.
          ZipxAws.DefaultCredentialsAction.unwrap.length > "aws-actions/configure-aws-credentials@".length + 20,
        )
      },
      test("an extra: pin in the consumer's file wins") {
        val bumped = ActionRef("aws-actions/configure-aws-credentials@1111111111111111111111111111111111111111")
        val pins   = ZipxAws.withCredentialsPin(ActionPins.Defaults, bumped, Some("v6.9.9"))
        val step   = ZipxAws.oidcLoginSteps(stepContext(pins)).head
        assertTrue(
          ZipxAws.credentialsAction(pins) == bumped,
          step.uses.contains(bumped),
          pins.extraVersion(ZipxAws.CredentialsPinKey).contains("v6.9.9"),
        )
      },
      test("the pin round-trips through the committed file format") {
        val bumped = ActionRef("aws-actions/configure-aws-credentials@1111111111111111111111111111111111111111")
        val pins   = ZipxAws.withCredentialsPin(ActionPins.Defaults, bumped, Some("v6.9.9"))
        val text   = ActionPinFile.render(pins)
        assertTrue(
          text.contains("extra:"),
          text.contains(s"  ${ZipxAws.CredentialsPinKey}: ${bumped.unwrap} # v6.9.9"),
          ActionPinFile.parse(text).map(ZipxAws.credentialsAction) == Right(bumped),
        )
      },
      test("zipxActionsPull bumps the key once it exists, since Dependabot edits the workflow") {
        val bumped = "aws-actions/configure-aws-credentials@2222222222222222222222222222222222222222"
        val base   = ZipxAws.withCredentialsPin(ActionPins.Defaults, ZipxAws.DefaultCredentialsAction, Some("v6.2.3"))
        val pulled = ActionPinFile.pullFromWorkflow(s"      - uses: $bumped # v6.9.9\n", base)
        assertTrue(
          pulled.map(_.extraRef(ZipxAws.CredentialsPinKey).map(_.unwrap)) == Right(Some(bumped)),
          pulled.map(_.extraVersion(ZipxAws.CredentialsPinKey)) == Right(Some("v6.9.9")),
        )
      },
    ),
    suite("dockerPublish")(
      test("one job for the whole push, with OIDC permissions and the login step already wired") {
        val wf  = Planner.plan(graph, List(ZipxAws.dockerPublish(registry, role)), config)
        val job = wf.jobs("docker")
        assertTrue(
          wf.jobs.keys.count(_.startsWith("docker")) == 1,
          job.permissions.get("id-token").contains("write"),
          job.permissions.get("contents").contains("read"),
          job.env.get("AWS_REGION").contains("us-east-1"),
          job.steps.exists(_.name.contains("Assume AWS role (OIDC)")),
          job.`if`.exists(_.contains("refs/tags/v")),
        )
      },
      test("the login step precedes the publish command, since credentials must exist first") {
        val steps = Planner.plan(graph, List(ZipxAws.dockerPublish(registry, role)), config).jobs("docker").steps
        val login = steps.indexWhere(_.name.exists(_.contains("Assume AWS role")))
        val push  = steps.indexWhere(_.run.exists(_.contains("Docker/publish")))
        assertTrue(login >= 0, push > login)
      },
      test("registryTargets fans out one job per registry, for the case that really wants that") {
        val second  = EcrRegistry(AwsAccountId("444455556666"), AwsRegion("eu-west-1"))
        val targets = ZipxAws.registryTargets(
          List(
            (TargetName("us"), registry, secret"US_ROLE"),
            (TargetName("eu"), second, secret"EU_ROLE"),
          )
        )
        val fannedOut = ZipxAws.dockerPublish(registry, role).withTargets(_ => targets)
        val wf        = Planner.plan(graph, List(fannedOut), config)
        assertTrue(
          wf.jobs.contains("docker-us"),
          wf.jobs.contains("docker-eu"),
          // Each destination logs into its own account with its own role, from the same one bundle.
          wf.jobs("docker-eu").env.get("AWS_REGION").contains("eu-west-1"),
          wf.jobs("docker-eu").env.get("AWS_ROLE_TO_ASSUME").contains("${{ secrets.EU_ROLE }}"),
          wf.jobs("docker-us").env.get("AWS_ROLE_TO_ASSUME").contains("${{ secrets.US_ROLE }}"),
        )
      },
    ),
    suite("dockerPublishAll pushes several registries from one build (#71)")(
      test("one job, not one per registry: 6 registries stay 1 job with 6 logins") {
        val wf  = Planner.plan(graph, List(ZipxAws.dockerPublishAll(sixRegistries)), config)
        val job = wf.jobs("docker")
        assertTrue(
          wf.jobs.keys.count(_.startsWith("docker")) == 1,
          job.steps.count(_.name.exists(_.startsWith("Assume AWS role (OIDC,"))) == 6,
          // One build. Two `Docker/publish` invocations would defeat the point of the shape.
          job.steps.count(_.run.exists(_.contains("Docker/publish"))) == 1,
        )
      },
      test("each login reads its own destination's role and region, never a shared unprefixed key") {
        val job    = Planner.plan(graph, List(ZipxAws.dockerPublishAll(sixRegistries)), config).jobs("docker")
        val logins = job.steps.filter(_.name.exists(_.startsWith("Assume AWS role (OIDC,")))
        val us     = logins.find(_.name.contains("Assume AWS role (OIDC, us)")).get
        assertTrue(
          us.`with`.get("role-to-assume").contains("${{ env.ZIPX_US_AWS_ROLE_TO_ASSUME }}"),
          us.`with`.get("aws-region").contains("${{ env.ZIPX_US_AWS_REGION }}"),
          // Every login points at a *distinct* pair, which is what the prefix buys.
          logins.flatMap(_.`with`.get("role-to-assume")).distinct.size == 6,
          logins.forall(s => !s.`with`.get("aws-region").contains("${{ env.AWS_REGION }}")),
        )
      },
      test("every registry's host, region and role is in the job env under its own prefix") {
        val env = Planner.plan(graph, List(ZipxAws.dockerPublishAll(sixRegistries)), config).jobs("docker").env
        assertTrue(
          env.get("ZIPX_EU_AWS_REGION").contains("eu-west-1"),
          env.get("ZIPX_EU_AWS_ECR_REGISTRY").contains("444455556666.dkr.ecr.eu-west-1.amazonaws.com"),
          env.get("ZIPX_US_AWS_ECR_REGISTRY").contains("111122223333.dkr.ecr.us-east-1.amazonaws.com"),
          env.get("ZIPX_US_AWS_ROLE_TO_ASSUME").contains("${{ secrets.US_ROLE }}"),
        )
      },
      test("OIDC permissions and the release gate are unchanged from the single-registry factory") {
        val job = Planner.plan(graph, List(ZipxAws.dockerPublishAll(sixRegistries)), config).jobs("docker")
        assertTrue(
          job.permissions.get("id-token").contains("write"),
          job.permissions.get("contents").contains("read"),
          job.`if`.exists(_.contains("refs/tags/v")),
        )
      },
      test("the bundle is named and raw-fragment free, like every other bundle this pack ships") {
        assertTrue(
          ZipxAws.sharedLoginSteps.name == "aws-oidc-login-per-destination",
          ZipxAws.sharedLoginSteps.rawFragments.isEmpty,
          // With no destinations it emits nothing rather than one credential-less login.
          ZipxAws.sharedLoginSteps(stepContext()).isEmpty,
        )
      },
      test("Graph scope is one job per module still, with the logins inside each") {
        val graphed = ZipxAws.dockerPublishAll(sixRegistries, scope = CapabilityScope.Graph)
        val wf      = Planner.plan(graph, List(graphed), config)
        assertTrue(
          wf.jobs.keys.filter(_.startsWith("docker")).toList == List("docker-serviceA"),
          wf.jobs("docker-serviceA").steps.count(_.name.exists(_.startsWith("Assume AWS role (OIDC,"))) == 6,
        )
      },
    ),
  )

  /** Six registries across two accounts, the shape #71 describes: one assume-role per destination, one image. */
  private val sixRegistries: List[(TargetName, EcrRegistry, EnvValue)] =
    val us = EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1"))
    val eu = EcrRegistry(AwsAccountId("444455556666"), AwsRegion("eu-west-1"))
    List(
      (TargetName("us"), us, secret"US_ROLE"),
      (TargetName("eu"), eu, secret"EU_ROLE"),
      (TargetName("apac"), EcrRegistry(AwsAccountId("777788889999"), AwsRegion("ap-southeast-2")), secret"APAC_ROLE"),
      (TargetName("gov"), EcrRegistry(AwsAccountId("222233334444"), AwsRegion("us-gov-west-1")), secret"GOV_ROLE"),
      (TargetName("dev"), EcrRegistry(AwsAccountId("555566667777"), AwsRegion("us-east-2")), secret"DEV_ROLE"),
      (TargetName("mirror"), EcrRegistry(AwsAccountId("888899990000"), AwsRegion("eu-central-1")), secret"MIRROR_ROLE"),
    )
  end sixRegistries

end ZipxAwsSpec
