package zipx.core

import neotype.unwrap
import zio.test.*

/** `TargetFanOut.SharedJob`: several destinations served by one job (#71).
  *
  * A suite of its own because the property under test is arithmetic, not shape: `targets` multiplying jobs is correct
  * for deploy environments and wrong for registries, and the whole point of the mode is the job *count*. The numbers in
  * the issue are 6 registries × 8 images = 48 jobs each rebuilding the same image, versus 8. Anything that quietly
  * reintroduces per-target fan-out here restores that multiplication, and only a counting assertion catches it.
  */
object SharedTargetsSpec extends ZIOSpecDefault:
  import Fixtures.*
  import EnvValue.secret

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  /** `docker = true` on the four services, so a docker capability has something to fan out over. */
  private val dockerGraph = sampleGraph.mapNodes {
    case n if n.id.startsWith("service") => n.copy(docker = true)
    case n                               => n
  }

  /** The issue's own count: six registries for one image.
    *
    * Names spelled as literals rather than mapped over a `List[String]`, because `TargetName.apply` validates at
    * compile time and so refuses a value it cannot fold. That is the point of the type, and a test is not the place to
    * reach for `unsafeMake`.
    */
  private val sixRegistries: List[Target] =
    List(
      TargetName("us"),
      TargetName("eu"),
      TargetName("apac"),
      TargetName("gov"),
      TargetName("dev"),
      TargetName("mirror"),
    ).zipWithIndex.map { (name, i) =>
      Target(
        name,
        env = Map(
          "AWS_REGION"         -> EnvValue.plain(s"region-$i"),
          "AWS_ROLE_TO_ASSUME" -> secret"DEPLOY_ROLE",
        ),
      )
    }

  private def dockerWith(targets: List[Target], scope: CapabilityScope, shared: Boolean): Capability =
    val base = scope match
      case CapabilityScope.Graph => Capability.dockerGraph
      case _                     => Capability.docker
    val shaped =
      if shared then base.withSharedTargets(targets) else base.withTargets(_ => targets)
    // Expansion arithmetic is the load-bearing property here; Auto collapse is covered in MatrixCollapseSpec.
    shaped.withMatrixCollapse(MatrixCollapse.Off)

  private def plan(capability: Capability) = Planner.plan(dockerGraph, List(capability), config)

  private def jobIds(capability: Capability): List[String] = plan(capability).jobs.keys.toList

  def spec = suite("TargetFanOut.SharedJob")(
    suite("the job count, which is the entire point")(
      test("6 registries over 4 images is 4 jobs shared, where per-target it is 24") {
        val shared = jobIds(dockerWith(sixRegistries, CapabilityScope.Graph, shared = true))
        val perJob = jobIds(dockerWith(sixRegistries, CapabilityScope.Graph, shared = false))
        assertTrue(
          shared.count(_.startsWith("docker")) == 4,
          perJob.count(_.startsWith("docker")) == 24,
        )
      },
      test("an Aggregate capability collapses to the one job it would have had with no targets at all") {
        val shared   = jobIds(dockerWith(sixRegistries, CapabilityScope.Aggregate, shared = true))
        val noTarget = jobIds(Capability.docker.withMatrixCollapse(MatrixCollapse.Off))
        assertTrue(shared == noTarget, shared == List("docker"))
      },
      test("the shared job ids are exactly the no-target ids, so a needs: edge onto them keeps working") {
        val shared   = jobIds(dockerWith(sixRegistries, CapabilityScope.Graph, shared = true))
        val noTarget = jobIds(Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Off))
        assertTrue(shared == noTarget)
      },
      test("a dependent's needs names the shared job, not a per-destination one that does not exist") {
        val docker = dockerWith(sixRegistries, CapabilityScope.Graph, shared = true)
        val deploy = Capability
          .deployGraph(
            participates = _.id == "serviceA",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
            targets = _ => List(Target(TargetName("prod"))),
          )
          .withMatrixCollapse(MatrixCollapse.Off)
        val wf = Planner.plan(dockerGraph, List(docker, deploy), config)
        assertTrue(
          wf.jobs("deploy-serviceA-prod").needs.contains("docker-serviceA"),
          wf.jobs("deploy-serviceA-prod").needs.forall(n => wf.jobs.contains(n)),
        )
      },
      test("Auto SharedJob folds independent Graph modules into one matrix job") {
        val shared = dockerWith(sixRegistries, CapabilityScope.Graph, shared = true)
          .withMatrixCollapse(MatrixCollapse.Auto)
        val wf = Planner.plan(dockerGraph, List(shared), config)
        assertTrue(
          wf.jobs.keys.count(_.startsWith("docker")) == 1,
          wf.jobs.contains("docker"),
          wf.jobs("docker").strategy.exists(_.matrix.contains("module")),
        )
      },
      test("Auto JobPerTarget folds registries into one include job when shape allows") {
        val perJob = dockerWith(sixRegistries, CapabilityScope.Graph, shared = false)
          .withMatrixCollapse(MatrixCollapse.Auto)
        val wf = Planner.plan(dockerGraph, List(perJob), config)
        assertTrue(
          wf.jobs.keys.count(_.startsWith("docker")) == 1,
          wf.jobs("docker").strategy.exists(s => s.include.nonEmpty || s.matrix.contains("target")),
        )
      },
    ),
    suite("every destination's env reaches the one job, under its own prefix")(
      // Merging unprefixed would keep whichever `++` saw last, and the job would push twice to one account while
      // silently skipping five. The prefix is what makes six roles coexist.
      test("all six roles and regions are present, none overwriting another") {
        val job     = plan(dockerWith(sixRegistries, CapabilityScope.Graph, shared = true)).jobs("docker-serviceA")
        val regions = sixRegistries.map(t => job.env.get(t.envKey("AWS_REGION")))
        assertTrue(
          regions == List(0, 1, 2, 3, 4, 5).map(i => Some(s"region-$i")),
          sixRegistries.forall(t => job.env.contains(t.envKey("AWS_ROLE_TO_ASSUME"))),
          // The unprefixed key is *absent*: a step reading it would silently get one arbitrary destination.
          !job.env.contains("AWS_REGION"),
        )
      },
      test("the prefix is ZIPX_-anchored and - becomes _, since an env name allows neither") {
        val t = Target(TargetName("us-east"), env = Map("AWS_REGION" -> EnvValue.plain("us-east-1")))
        assertTrue(
          t.envPrefix == "ZIPX_US_EAST",
          t.envKey("AWS_REGION") == "ZIPX_US_EAST_AWS_REGION",
          t.prefixedEnv.keys.toList == List("ZIPX_US_EAST_AWS_REGION"),
        )
      },
      test("envName is total for a target named github, which unanchored would derive a reserved GITHUB_ name") {
        val t = Target(TargetName("github"))
        assertTrue(t.envName(zipx.workflow.EnvName("AWS_REGION")).unwrap == "ZIPX_GITHUB_AWS_REGION")
      },
      test("capability env still applies, and a destination does not clobber it") {
        val docker = dockerWith(sixRegistries, CapabilityScope.Graph, shared = true)
          .copy(env = Map("DOCKER_BUILDKIT" -> EnvValue.plain("1")))
        val job = plan(docker).jobs("docker-serviceA")
        assertTrue(job.env.get("DOCKER_BUILDKIT").contains("1"), job.env.size == 13)
      },
    ),
    suite("extraSteps see the destinations")(
      test("a bundle emits one step per destination, in target-name order") {
        val steps = Steps.buildingWith("logins")(ctx =>
          ctx.destinations.map(t => zipx.workflow.Step.run(zipx.shell.Script(zipx.shell.Exec("true"))).named(t.name))
        )
        val docker = dockerWith(sixRegistries, CapabilityScope.Graph, shared = true).copy(extraSteps = steps)
        val names  = plan(docker).jobs("docker-serviceA").steps.flatMap(_.name)
        assertTrue(
          sixRegistries.map(_.name).sorted.forall(names.contains),
          names.count(n => sixRegistries.exists(_.name == n)) == 6,
        )
      },
      test("destinations is empty under JobPerTarget, where target is populated instead") {
        val seen  = scala.collection.mutable.ListBuffer.empty[(Option[String], Int)]
        val probe = Steps("probe") { ctx =>
          seen += ((ctx.target.map(_.name), ctx.destinations.size))
          Nil
        }
        val docker = dockerWith(sixRegistries, CapabilityScope.Graph, shared = false).copy(extraSteps = probe)
        val _      = plan(docker)
        assertTrue(seen.forall((target, count) => target.isDefined && count == 0))
      },
      test("target is empty under SharedJob, since there is no single target the job belongs to") {
        val seen  = scala.collection.mutable.ListBuffer.empty[(Option[String], Int)]
        val probe = Steps("probe") { ctx =>
          seen += ((ctx.target.map(_.name), ctx.destinations.size))
          Nil
        }
        val docker = dockerWith(sixRegistries, CapabilityScope.Graph, shared = true).copy(extraSteps = probe)
        val _      = plan(docker)
        assertTrue(seen.nonEmpty, seen.forall((target, count) => target.isEmpty && count == 6))
      },
    ),
    suite("a per-destination field one job cannot honor is rejected, not dropped")(
      // Dropping a target condition would push to a registry the author said to skip; applying it to the whole job
      // would skip the five that were fine. Both are silent wrong answers.
      test("a target condition is refused, naming the field and the alternative") {
        val docker = dockerWith(
          List(Target(TargetName("us"), condition = Some(JobCondition.varNonEmpty("US_ENABLED")))),
          CapabilityScope.Graph,
          shared = true,
        )
        val err = scala.util.Try(plan(docker)).failed.get.getMessage
        assertTrue(
          err.contains("target 'us'"),
          err.contains("a condition"),
          err.contains("TargetFanOut.JobPerTarget"),
        )
      },
      test("a target environment is refused, since a job binds one Environment") {
        val docker = dockerWith(
          List(Target(TargetName("us"), environment = Some("production"))),
          CapabilityScope.Graph,
          shared = true,
        )
        val err = scala.util.Try(plan(docker)).failed.get.getMessage
        assertTrue(err.contains("an environment"), err.contains("target 'us'"))
      },
      test("both stay legal under JobPerTarget, which is what has a job each to put them on") {
        val docker = dockerWith(
          List(Target(TargetName("us"), environment = Some("production"))),
          CapabilityScope.Graph,
          shared = false,
        )
        assertTrue(plan(docker).jobs("docker-serviceA-us").environment.contains("production"))
      },
    ),
    suite("nothing changes for a capability that does not ask for it")(
      test("JobPerTarget is the default, so an existing build's job ids are untouched") {
        val cap = Capability.dockerGraph
          .copy(targets = _ => sixRegistries)
          .withMatrixCollapse(MatrixCollapse.Off)
        assertTrue(
          cap.targetFanOut == TargetFanOut.JobPerTarget,
          jobIds(cap).count(_.startsWith("docker")) == 24,
        )
      },
      test("withSharedTargets sets both fields, since setting either alone is the mistake") {
        val cap = Capability.dockerGraph.withSharedTargets(sixRegistries)
        assertTrue(
          cap.targetFanOut == TargetFanOut.SharedJob,
          cap.targets(ModuleNode(ModuleId("serviceA"))).size == 6,
        )
      },
    ),
  )
end SharedTargetsSpec
