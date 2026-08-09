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
        val fannedOut = ZipxAws.dockerPublish(registry, role).copy(targets = _ => targets)
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
  )
end ZipxAwsSpec
