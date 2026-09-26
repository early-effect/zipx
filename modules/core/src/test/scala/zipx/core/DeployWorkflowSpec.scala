package zipx.core

import zipx.workflow.*
import zio.test.*

object DeployWorkflowSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val graph = sampleGraph.mapNodes {
    case n if n.id.startsWith("service") => n.copy(docker = true)
    case n                               => n
  }

  private val config = PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"))

  private val stg = TargetName("stg")
  private val prd = TargetName("prd")

  private val image = Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Off)

  private val registry = Capability
    .steps(
      name = CapabilityName("registry"),
      steps = Steps.empty,
      phase = Phase.Deploy,
      gate = Gate.Always,
      needsCapabilities = List(Capability.DockerName),
    )
    .copy(scope = CapabilityScope.Graph, participates = _.docker, matrixCollapse = Some(MatrixCollapse.Off))

  private def tiers(group: String => Option[TargetGroup] = t => Some(TargetGroup.unsafeMake(s"all-$t"))) =
    List(
      Target(stg, environment = Some("lab-stg"), group = group("stg")),
      Target(prd, environment = Some("lab-prd"), group = group("prd")),
    )

  private def deploy(targets: List[Target] = tiers()) =
    Capability
      .deployGraph(
        participates = n => n.id == "serviceA" || n.id == "serviceB",
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => targets,
        gate = Gate.Always,
      )
      .withMatrixCollapse(MatrixCollapse.Off)

  private val all = List(Capability.test, Capability.publish, image, registry, deploy())

  private def planned(capabilities: List[Capability] = all): Workflow =
    DeployWorkflow.plan(graph, DeployWorkflow.split(capabilities).deploy, config, DeployWorkflow.ImagesEnvironment)

  private def problems(capabilities: List[Capability]): List[String] =
    DeployWorkflow.problems(DeployWorkflow.split(capabilities), graph)

  private def step(job: Job, name: String): Option[Step] = job.steps.find(_.name.contains(name))

  def spec = suite("DeployWorkflow")(
    suite("split")(
      test("images, deploys, and everything needing one leave ci.yml; test and library publish stay") {
        val split = DeployWorkflow.split(all)
        assertTrue(
          split.deploy.map(_.name) == List(Capability.DockerName, CapabilityName("registry"), Capability.DeployName),
          split.ci.map(_.name) == List(Capability.TestName, Capability.PublishName),
        )
      },
      test("the move is transitive: a capability needing the registry moves with it") {
        val notify =
          registry.copy(name = CapabilityName("notify"), needsCapabilities = List(CapabilityName("registry")))
        assertTrue(DeployWorkflow.split(all :+ notify).deploy.exists(_.name == "notify"))
      },
      test("ci.yml planned from what stays has no image or deploy job") {
        val ci = Planner.plan(graph, DeployWorkflow.split(all).ci, config)
        assertTrue(
          !ci.jobs.keys.exists(k => k.startsWith("docker") || k.startsWith("deploy") || k.startsWith("registry"))
        )
      },
    ),
    suite("generate refuses what zipx-deploy.yml cannot run as declared")(
      test("a well-formed deploy has no problems") {
        assertTrue(problems(all).isEmpty)
      },
      test("a non-Graph image or deploy capability") {
        assertTrue(problems(List(Capability.docker)).exists(_.contains("Aggregate-scoped")))
      },
      test("a Verify capability that needs an image") {
        val smoke = registry.copy(name = CapabilityName("smoke"), phase = Phase.Verify)
        assertTrue(problems(all :+ smoke).exists(_.contains("'smoke' is a Verify capability")))
      },
      test("a need on a capability that stays in ci.yml") {
        val late = deploy().copy(needsCapabilities = List(Capability.DockerName, Capability.PublishName))
        assertTrue(problems(List(Capability.publish, image, late)).exists(_.contains("needs 'publish'")))
      },
      test("a condition that requires a push, which a dispatch never is") {
        val onMainPush = JobCondition.eventIs("push") && JobCondition.refIs("refs/heads/main")
        assertTrue(problems(List(image.withCondition(onMainPush))).exists(_.contains("requiring event 'push'")))
      },
      test("a deploy target with no Environment, whose deploys would leave no record") {
        val bare = deploy(List(Target(stg)))
        assertTrue(problems(List(image, bare)).exists(_.contains("target 'stg' binds no Environment")))
      },
      test("a group named like a target") {
        val clash = deploy(tiers(_ => Some(TargetGroup("stg"))))
        assertTrue(problems(List(image, clash)).exists(_.contains("group 'stg' is also a target name")))
      },
    ),
    suite("the dispatch form")(
      test("modules offers changed, all, then every module the deploy can ship") {
        val modules = planned().on.workflowDispatch.flatMap(_.inputs.get(DeployWorkflow.ModulesInput))
        assertTrue(
          modules.contains(
            DispatchInput.Choice(
              "changed: what differs from each Environment's last deploy; all; or one module",
              ::("changed", List("all", "serviceA", "serviceB", "serviceC", "serviceD")),
            )
          )
        )
      },
      test("target offers target names, then groups") {
        val target = planned().on.workflowDispatch.flatMap(_.inputs.get(DeployWorkflow.TargetInput))
        assertTrue(
          target.contains(
            DispatchInput.Choice("Target or group to deploy to", ::("prd", List("stg", "all-prd", "all-stg")))
          )
        )
      },
      test("with no deploy targets there is no target input, and one concurrency group") {
        val wf = planned(List(image))
        assertTrue(
          !wf.on.workflowDispatch.exists(_.inputs.contains(DeployWorkflow.TargetInput)),
          wf.concurrency.map(_.group).contains("zipx-deploy"),
        )
      },
      test("deploys to one target queue behind each other and are never cancelled") {
        assertTrue(
          planned().concurrency.contains(Concurrency("zipx-deploy-${{ inputs.target }}", CancelInProgress.Never))
        )
      },
    ),
    suite("jobs")(
      test("resolve runs first, reads deployments with its own token, and publishes the plan") {
        val wf      = planned()
        val resolve = wf.jobs("resolve")
        val plan    = step(resolve, "Resolve deploy plan")
        assertTrue(
          wf.jobs.keys.head == "resolve",
          resolve.permissions == Map("contents" -> "read", "deployments" -> "read"),
          resolve.outputs.keySet == Set("sha", "images", "targets"),
          plan.exists(_.env.get(DeployWorkflow.TokenEnv).contains("${{ github.token }}")),
          plan.exists(_.run.exists(_.startsWith("sbt zipxDeployPlan"))),
        )
      },
      test("an image job runs when the plan lists its module, pushes only a missing tag, and records itself") {
        val job   = planned().jobs("docker-serviceA")
        val check = job.steps.indexWhere(_.name.contains("Check image tags"))
        val push  = job.steps.indexWhere(_.name.contains("docker"))
        assertTrue(
          job.needs.contains("resolve"),
          job.`if`.exists(_.contains("contains(fromJson(needs.resolve.outputs.images), 'serviceA')")),
          !job.`if`.exists(_.contains("refs/tags")),
          check >= 0,
          push > check,
          job.steps(push).`if`.contains("steps.image-tags.outputs.missing == 'true'"),
          job.environment.map(_.name).contains(DeployWorkflow.ImagesEnvironment),
          job.environment.flatMap(_.url).exists(_.endsWith("/commit/${{ needs.resolve.outputs.sha }}#serviceA")),
          job.env.get(DeployWorkflow.ShaEnv).contains("${{ needs.resolve.outputs.sha }}"),
          job.steps.head.`with`.get("ref").contains("${{ needs.resolve.outputs.sha }}"),
        )
      },
      test("a deploy job runs when its target's plan lists its module, and records the module it shipped") {
        val job = planned().jobs("deploy-serviceB-stg")
        assertTrue(
          job.`if`.exists(_.contains("contains(fromJson(needs.resolve.outputs.targets)['stg'], 'serviceB')")),
          job.environment.map(_.name).contains("lab-stg"),
          job.environment.flatMap(_.url).exists(_.endsWith("#serviceB")),
          job.needs.contains("docker-serviceB"),
        )
      },
      test("a job without a target or image binds no Environment") {
        assertTrue(planned().jobs("registry-serviceA").environment.isEmpty)
      },
      test("a capability that asks for a collapsed matrix still gets one job per module and target") {
        val collapsed = List(
          Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Strict),
          deploy().withMatrixCollapse(MatrixCollapse.Coarse),
        )
        val wf = planned(collapsed)
        assertTrue(
          wf.jobs.contains("docker-serviceA"),
          wf.jobs.contains("deploy-serviceA-prd"),
          wf.jobs.values.forall(_.strategy.isEmpty),
        )
      },
      test("renders") {
        assertTrue(
          DeployWorkflow
            .render(graph, DeployWorkflow.split(all).deploy, config, DeployWorkflow.ImagesEnvironment)
            .isRight
        )
      },
      test("a target or group selects the targets the plan resolves") {
        val deploys = DeployWorkflow.split(all).deploy
        assertTrue(
          DeployWorkflow.selectedTargets(graph, deploys, "stg") == Right(Set(stg)),
          DeployWorkflow.selectedTargets(graph, deploys, "all-prd") == Right(Set(prd)),
          DeployWorkflow.selectedTargets(graph, deploys, "qa").isLeft,
        )
      },
    ),
  )
end DeployWorkflowSpec
