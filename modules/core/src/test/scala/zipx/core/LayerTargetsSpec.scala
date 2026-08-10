package zipx.core

import zio.test.*

/** Layer × target fan-out: Aggregate-by-target per toposort wave, plus SharedJob destinations on every wave.
  *
  * Arithmetic and within-target `needs` are the load-bearing properties; example tests pin field placement.
  */
object LayerTargetsSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  /** Three docker waves so within-target `needs` chaining is observable (sample services alone sit in one wave). */
  private val dockerGraph = GraphFixture(
    List(
      ModuleNode(ModuleId("maple"), docker = true, crossScalaVersions = List(Fixtures.scala3)),
      ModuleNode(
        ModuleId("cedar"),
        dependsOn = List("maple"),
        docker = true,
        crossScalaVersions = List(Fixtures.scala3),
      ),
      ModuleNode(
        ModuleId("spruce"),
        dependsOn = List("cedar"),
        docker = true,
        crossScalaVersions = List(Fixtures.scala3),
      ),
    )
  )

  private def deployLayers(targets: List[Target], shared: Boolean = false): Capability =
    val base = Capability
      .deploy(
        participates = _.docker,
        command = n => SbtCommand.module(n, SbtCommand("promote")),
        targets = _ => targets,
      )
      .copy(scope = CapabilityScope.Layer)
    if shared then base.withSharedTargets(targets) else base

  private def plan(caps: Capability*) = Planner.plan(dockerGraph, caps.toList, config)

  private def waveCount(capability: Capability): Int =
    dockerGraph.subsetLayers(capability.participates).size

  private val gTargetName: Gen[Any, TargetName] =
    Gen.elements(TargetName("prod"), TargetName("staging"), TargetName("eu"), TargetName("us"))

  private val gTargets: Gen[Any, List[Target]] =
    for
      names   <- Gen.listOfBounded(1, 4)(gTargetName).map(_.distinctBy(n => n: String))
      withEnv <- Gen.boolean
    yield names.map { name =>
      Target(
        name,
        environment = Option.when((name: String) == "prod")("production"),
        env = if withEnv then Map("TIER" -> EnvValue.plain(name)) else Map.empty,
      )
    }

  private val gMultiTargets: Gen[Any, List[Target]] =
    for
      names   <- Gen.listOfBounded(2, 4)(gTargetName).map(_.distinctBy(n => n: String)).filter(_.sizeIs >= 2)
      withEnv <- Gen.boolean
    yield names.map { name =>
      Target(
        name,
        env = if withEnv then Map("TIER" -> EnvValue.plain(name)) else Map.empty,
      )
    }

  def spec = suite("Layer × target fan-out")(
    suite("JobPerTarget is Aggregate-by-target per wave")(
      test("environment, condition, and env land on each wave×target job") {
        val targets = List(
          Target(TargetName("staging"), env = Map("TIER" -> EnvValue.plain("staging"))),
          Target(
            TargetName("prod"),
            environment = Some("production"),
            env = Map("TIER" -> EnvValue.plain("prod")),
            condition = Some(JobCondition.eventIs("push")),
          ),
        )
        val wf     = plan(Capability.dockerLayers, deployLayers(targets))
        val l0Prod = wf.jobs("deploy-L0-prod")
        assertTrue(
          wf.jobs.contains("deploy-L0-staging"),
          wf.jobs.contains("deploy-L0-prod"),
          wf.jobs.contains("deploy-L1-prod"),
          l0Prod.environment.contains("production"),
          l0Prod.env.get("TIER").contains("prod"),
          l0Prod.`if`.exists(_.contains("github.event_name == 'push'")),
          wf.jobs("deploy-L0-staging").environment.isEmpty,
          wf.jobs("deploy-L0-staging").env.get("TIER").contains("staging"),
        )
      },
      test("within-target waves chain; targets in a wave do not need each other") {
        val targets = List(Target(TargetName("staging")), Target(TargetName("prod")))
        val wf      = plan(Capability.dockerLayers, deployLayers(targets))
        assertTrue(
          wf.jobs("deploy-L1-prod").needs.contains("deploy-L0-prod"),
          !wf.jobs("deploy-L1-prod").needs.contains("deploy-L0-staging"),
          !wf.jobs("deploy-L0-prod").needs.contains("deploy-L0-staging"),
          wf.jobs("deploy-L0-prod").needs.exists(_.startsWith("docker")),
        )
      },
      test("no-target Layer shape is unchanged") {
        val wf = Planner.plan(sampleGraph, List(Capability.testLayers), config)
        assertTrue(
          wf.jobs.contains("test-L0"),
          wf.jobs.get("test-L1").exists(_.needs == List("test-L0")),
          !wf.jobs.keys.exists(_.contains("test-L0-")),
        )
      },
    ),
    suite("SharedJob puts destinations on every wave")(
      test("job ids equal the no-target Layer ids") {
        val registries = List(
          Target(TargetName("us"), env = Map("AWS_REGION" -> EnvValue.plain("us-east-1"))),
          Target(TargetName("eu"), env = Map("AWS_REGION" -> EnvValue.plain("eu-west-1"))),
        )
        val shared      = Capability.dockerLayers.withSharedTargets(registries)
        val noTarget    = Capability.dockerLayers
        val sharedIds   = plan(shared).jobs.keys.filter(_.startsWith("docker")).toList.sorted
        val noTargetIds = plan(noTarget).jobs.keys.filter(_.startsWith("docker")).toList.sorted
        assertTrue(sharedIds == noTargetIds, sharedIds.headOption.contains("docker-L0"))
      },
      test("every wave job carries prefixed env for each destination") {
        val registries = List(
          Target(TargetName("us"), env = Map("AWS_REGION" -> EnvValue.plain("us-east-1"))),
          Target(TargetName("eu"), env = Map("AWS_REGION" -> EnvValue.plain("eu-west-1"))),
        )
        val wf  = plan(Capability.dockerLayers.withSharedTargets(registries))
        val job = wf.jobs("docker-L0")
        assertTrue(
          job.env.get("ZIPX_US_AWS_REGION").contains("us-east-1"),
          job.env.get("ZIPX_EU_AWS_REGION").contains("eu-west-1"),
          wf.jobs.keys.filter(_.startsWith("docker-L")).forall { id =>
            val env = wf.jobs(id).env
            env.contains("ZIPX_US_AWS_REGION") && env.contains("ZIPX_EU_AWS_REGION")
          },
        )
      },
      test("per-destination environment on SharedJob is still refused") {
        val bad = List(Target(TargetName("us"), environment = Some("production")))
        val err = scala.util.Try(plan(Capability.dockerLayers.withSharedTargets(bad))).failed.get.getMessage
        assertTrue(err.contains("shared job"), err.contains("environment"))
      },
    ),
    suite("algebraic properties")(
      test("JobPerTarget job count is waves times distinct targets") {
        check(gTargets) { targets =>
          val cap   = deployLayers(targets)
          val waves = waveCount(cap)
          val wf    = plan(Capability.dockerLayers, cap)
          val n     = wf.jobs.keys.count(_.startsWith("deploy-L"))
          assertTrue(n == waves * targets.size)
        }
      },
      test("SharedJob job count equals no-target Layer count") {
        check(gTargets) { targets =>
          val shared =
            Capability.dockerLayers.withSharedTargets(targets.map(_.copy(environment = None, condition = None)))
          val plain = Capability.dockerLayers
          assertTrue(
            plan(shared).jobs.keys.count(_.startsWith("docker")) ==
              plan(plain).jobs.keys.count(_.startsWith("docker"))
          )
        }
      },
      test("within-target needs chain for every wave after L0") {
        check(gTargets) { targets =>
          val cap   = deployLayers(targets)
          val waves = waveCount(cap)
          val wf    = plan(Capability.dockerLayers, cap)
          assertTrue(
            waves >= 2,
            (0 until waves - 1).forall { i =>
              targets.forall { t =>
                val later = s"deploy-L${i + 1}-${t.name}"
                val prev  = s"deploy-L$i-${t.name}"
                wf.jobs.get(later).exists(_.needs.contains(prev)) &&
                targets.filterNot(_.name == t.name).forall { other =>
                  !wf.jobs(later).needs.contains(s"deploy-L$i-${other.name}")
                }
              }
            },
          )
        }
      },
      test("targets in the same wave do not need each other") {
        check(gMultiTargets) { targets =>
          val wf = plan(Capability.dockerLayers, deployLayers(targets))
          val ok = targets.combinations(2).forall {
            case List(a, b) =>
              val idA = s"deploy-L0-${a.name}"
              val idB = s"deploy-L0-${b.name}"
              !wf.jobs(idA).needs.contains(idB) && !wf.jobs(idB).needs.contains(idA)
            case _ => true
          }
          assertTrue(ok)
        }
      },
    ),
    suite("skip consumers stay refused")(
      test("Layer deploy needing an affected-gated Graph docker is refused") {
        val deploy = deployLayers(List(Target(TargetName("prod"), environment = Some("production"))))
        val cfg    = config.copy(affectedPublish = true, affected = AffectedMode.AffectedOnPR)
        val err    = scala.util
          .Try(Planner.plan(dockerGraph, List(Capability.dockerGraph, deploy), cfg))
          .failed
          .get
          .getMessage
        assertTrue(err.contains("CapabilityScope.Graph"), err.contains("refused"))
      }
    ),
  )
end LayerTargetsSpec
