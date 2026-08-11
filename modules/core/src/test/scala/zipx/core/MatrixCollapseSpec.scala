package zipx.core

import zipx.core.Rendered.yaml
import zipx.workflow.Render
import zio.test.*

/** Matrix-collapse: cascade resolution, Strict/Coarse eligibility, Aggregate/Layer target matrices. */
object MatrixCollapseSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val baseConfig = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  /** Independent docker services (no edges among participants). */
  private val independentDocker = GraphFixture(
    List(
      ModuleNode(ModuleId("api"), docker = true, crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("web"), docker = true, crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("batch"), docker = true, crossScalaVersions = List(scala3)),
    )
  )

  /** DependencyOrdered docker chain. */
  private val orderedDocker = GraphFixture(
    List(
      ModuleNode(ModuleId("maple"), docker = true, crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("cedar"), dependsOn = List("maple"), docker = true, crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("spruce"), dependsOn = List("cedar"), docker = true, crossScalaVersions = List(scala3)),
    )
  )

  private def imageCap(mode: Option[MatrixCollapse] = Some(MatrixCollapse.Strict)): Capability =
    Capability
      .custom(
        name = CapabilityName("image"),
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("Docker/publishLocal")),
        participates = _.docker,
        ordering = Ordering.ParallelWithUpstream,
        gate = Gate.Always,
        phase = Phase.Publish,
      )
      .copy(matrixCollapse = mode)

  private def deployAgg(targets: List[Target], mode: Option[MatrixCollapse] = None): Capability =
    Capability
      .deploy(
        participates = _.docker,
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => targets,
      )
      .copy(matrixCollapse = mode)

  private def deployLayer(targets: List[Target], mode: Option[MatrixCollapse] = None): Capability =
    deployAgg(targets, mode).copy(scope = CapabilityScope.Layer)

  private val gTargetName: Gen[Any, TargetName] =
    Gen.elements(TargetName("prod"), TargetName("staging"), TargetName("eu"), TargetName("us"))

  private val gMultiTargets: Gen[Any, List[Target]] =
    for names <- Gen.listOfBounded(2, 4)(gTargetName).map(_.distinctBy(n => n: String)).filter(_.sizeIs >= 2)
    yield names.map(name => Target(name, environment = Some(name)))

  def spec = suite("MatrixCollapse")(
    suite("cascade")(
      test("defaults to Off") {
        assertTrue(MatrixCollapse.effective(Capability.dockerGraph, baseConfig) == MatrixCollapse.Off)
      },
      test("plan allowlist enables Strict") {
        val cfg = baseConfig.copy(matrixCollapse = Map(Capability.DockerName -> MatrixCollapse.Strict))
        assertTrue(MatrixCollapse.effective(Capability.dockerGraph, cfg) == MatrixCollapse.Strict)
      },
      test("capability Off vetoes plan Coarse") {
        val cfg = baseConfig.copy(matrixCollapse = Map(Capability.DockerName -> MatrixCollapse.Coarse))
        val cap = Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Off)
        assertTrue(MatrixCollapse.effective(cap, cfg) == MatrixCollapse.Off)
      },
      test("capability Strict wins without plan entry") {
        assertTrue(MatrixCollapse.effective(imageCap(Some(MatrixCollapse.Strict)), baseConfig) == MatrixCollapse.Strict)
      },
      test("effective = cap.orElse(plan).getOrElse(Off)") {
        check(
          Gen.option(Gen.elements(MatrixCollapse.values.toList*)),
          Gen.option(Gen.elements(MatrixCollapse.values.toList*)),
        ) { (capMode, planMode) =>
          val cap      = imageCap(capMode)
          val cfg      = baseConfig.copy(matrixCollapse = planMode.map(CapabilityName("image") -> _).toMap)
          val got      = MatrixCollapse.effective(cap, cfg)
          val expected = capMode.orElse(planMode).getOrElse(MatrixCollapse.Off)
          assertTrue(got == expected)
        }
      },
    ),
    suite("Graph Strict")(
      test("collapses independent modules into one matrix job") {
        val wf  = Planner.plan(independentDocker, List(imageCap()), baseConfig)
        val job = wf.jobs("image")
        assertTrue(
          wf.jobs.keys.filter(_.startsWith("image")).toList == List("image"),
          job.strategy.exists(_.matrix.get("module").contains(List("api", "batch", "web"))),
          job.steps.exists(_.run.exists(_.contains("${{ matrix.module }}/Docker/publishLocal"))),
        )
      },
      test("errors when same-cap inter-module needs exist") {
        val result = try
          Planner.plan(
            orderedDocker,
            List(Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Strict)),
            baseConfig,
          )
          Right(())
        catch case e: RuntimeException => Left(e.getMessage)
        assertTrue(result.swap.exists(_.contains("MatrixCollapse.Strict")))
      },
      test("errors on JobPerTarget under Strict") {
        val cap = Capability
          .deployGraph(
            participates = _.docker,
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
            targets = _ => List(Target(TargetName("prod")), Target(TargetName("staging"))),
          )
          .withMatrixCollapse(MatrixCollapse.Strict)
        val result = try
          Planner.plan(independentDocker, List(cap), baseConfig)
          Right(())
        catch case e: RuntimeException => Left(e.getMessage)
        assertTrue(result.swap.exists(_.contains("JobPerTarget")))
      },
    ),
    suite("Graph Coarse")(
      test("collapses DependencyOrdered docker and drops same-cap needs") {
        val wf = Planner.plan(
          orderedDocker,
          List(Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Coarse)),
          baseConfig,
        )
        val job = wf.jobs("docker")
        assertTrue(
          wf.jobs.keys.filter(_.startsWith("docker")).toList == List("docker"),
          job.strategy.exists(_.matrix("module").sizeIs == 3),
          !job.needs.exists(_.startsWith("docker-")),
          MatrixCollapse
            .warnings(List(Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Coarse)), orderedDocker, baseConfig)
            .nonEmpty,
        )
      },
      test("module × target matrix when JobPerTarget") {
        val targets = List(
          Target(TargetName("prod"), environment = Some("prod")),
          Target(TargetName("staging"), environment = Some("staging")),
        )
        val cap = Capability
          .deployGraph(
            participates = _.docker,
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
            targets = _ => targets,
            needsCapabilities = Nil,
          )
          .withMatrixCollapse(MatrixCollapse.Coarse)
        val wf  = Planner.plan(independentDocker, List(cap), baseConfig)
        val job = wf.jobs("deploy")
        assertTrue(
          job.strategy.exists(s => s.matrix.contains("module") && s.matrix.contains("target")),
          job.environment.contains("${{ matrix.target }}"),
        )
      },
    ),
    suite("affected")(
      test("job-level if does not reference matrix (illegal in jobs.<id>.if)") {
        val cfg  = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        val wf   = Planner.plan(independentDocker, List(imageCap()), cfg)
        val job  = wf.jobs("image")
        val `if` = job.`if`.getOrElse("")
        assertTrue(
          !`if`.contains("matrix."),
          `if`.contains("needs.affected.outputs.modules != '[]'"),
          job.needs.contains("affected"),
        )
      },
      test("step-level if gates each leg on matrix.module or all") {
        val cfg   = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        val wf    = Planner.plan(independentDocker, List(imageCap()), cfg)
        val steps = wf.jobs("image").steps
        val gate  =
          "(contains(fromJson(needs.affected.outputs.modules), matrix.module) || " +
            "contains(fromJson(needs.affected.outputs.modules), 'all'))"
        assertTrue(
          steps.nonEmpty,
          steps.forall(_.`if`.contains(gate)),
        )
      },
      test("Coarse + affected keeps job if matrix-free and steps gated") {
        val cfg = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        val wf  = Planner.plan(
          orderedDocker,
          List(Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Coarse)),
          cfg,
        )
        val job = wf.jobs("docker")
        assertTrue(
          !job.`if`.exists(_.contains("matrix.")),
          job.`if`.exists(_.contains("needs.affected.outputs.modules != '[]'")),
          job.steps.forall(_.`if`.exists(_.contains("matrix.module"))),
        )
      },
      test("without affected publish, collapsed job has no affected step gate") {
        val cfg = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = false)
        val wf  = Planner.plan(independentDocker, List(imageCap()), cfg)
        val job = wf.jobs("image")
        assertTrue(
          !job.needs.contains("affected"),
          job.steps.forall(s => !s.`if`.exists(_.contains("affected"))),
          !job.`if`.exists(_.contains("affected")),
        )
      },
      test("Off collapse still uses job-level contains(module id), not matrix") {
        val cfg = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        val wf  = Planner.plan(independentDocker, List(imageCap(Some(MatrixCollapse.Off))), cfg)
        val api = wf.jobs("image-api")
        assertTrue(
          api.`if`.exists(_.contains("contains(fromJson(needs.affected.outputs.modules), 'api')")),
          !api.`if`.exists(_.contains("matrix.")),
          api.steps.forall(s => !s.`if`.exists(_.contains("matrix.module"))),
        )
      },
      test("rendered YAML never puts matrix in a collapsed job if") {
        check(Gen.elements(MatrixCollapse.Strict, MatrixCollapse.Coarse)) { mode =>
          val cfg  = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
          val wf   = Planner.plan(independentDocker, List(imageCap(Some(mode))), cfg)
          val job  = wf.jobs("image")
          val yaml = Render.renderJob("image", job).yaml
          // Job-level `if:` line must not mention matrix; step `if:` may.
          val jobIfLine = yaml.linesIterator.find(_.trim.startsWith("if:")).getOrElse("")
          assertTrue(
            !jobIfLine.contains("matrix."),
            jobIfLine.contains("needs.affected.outputs.modules != '[]'"),
            yaml.contains("matrix.module"),
          )
        }
      },
    ),
    suite("Aggregate / Layer target collapse")(
      test("Aggregate Strict folds targets into one matrix job") {
        val targets = List(
          Target(TargetName("prod"), environment = Some("prod")),
          Target(TargetName("staging"), environment = Some("staging")),
        )
        val wf = Planner.plan(independentDocker, List(deployAgg(targets, Some(MatrixCollapse.Strict))), baseConfig)
        assertTrue(
          wf.jobs.keys.filter(_.startsWith("deploy")).toList == List("deploy"),
          wf.jobs("deploy").strategy.exists(_.matrix.get("target").contains(List("prod", "staging"))),
        )
      },
      test("Layer Strict keeps wave jobs and matrices targets") {
        val targets = List(
          Target(TargetName("prod"), environment = Some("prod")),
          Target(TargetName("staging"), environment = Some("staging")),
        )
        val wf = Planner.plan(orderedDocker, List(deployLayer(targets, Some(MatrixCollapse.Strict))), baseConfig)
        assertTrue(
          wf.jobs.contains("deploy-L0"),
          wf.jobs.contains("deploy-L1"),
          wf.jobs("deploy-L1").needs.contains("deploy-L0"),
          wf.jobs("deploy-L0").strategy.exists(_.matrix.contains("target")),
          !wf.jobs.keys.exists(_.contains("deploy-L0-")),
        )
      },
      test("Aggregate Strict job count is one for any multi-target list") {
        check(gMultiTargets) { targets =>
          val off  = Planner.plan(independentDocker, List(deployAgg(targets)), baseConfig)
          val on   = Planner.plan(independentDocker, List(deployAgg(targets, Some(MatrixCollapse.Strict))), baseConfig)
          val offN = off.jobs.keys.count(_.startsWith("deploy"))
          val onN  = on.jobs.keys.count(_.startsWith("deploy"))
          assertTrue(
            offN == targets.size,
            onN == 1,
            on.jobs("deploy").strategy.exists(_.matrix("target").sizeIs == targets.size),
          )
        }
      },
      test("Layer Strict job count equals wave count") {
        check(gMultiTargets) { targets =>
          val waves = orderedDocker.subsetLayers(_.docker).size
          val off   = Planner.plan(orderedDocker, List(deployLayer(targets)), baseConfig)
          val on    = Planner.plan(orderedDocker, List(deployLayer(targets, Some(MatrixCollapse.Strict))), baseConfig)
          assertTrue(
            off.jobs.keys.count(_.startsWith("deploy-L")) == waves * targets.size,
            on.jobs.keys.count(_.startsWith("deploy-L")) == waves,
          )
        }
      },
    ),
    suite("Off is unchanged")(
      test("plan allowlist empty matches no collapse") {
        val offCaps = List(Capability.dockerGraph)
        val a       = Planner.plan(independentDocker, offCaps, baseConfig)
        val b       = Planner.plan(independentDocker, offCaps, baseConfig.copy(matrixCollapse = Map.empty))
        assertTrue(a.jobs.keys.toList.sorted == b.jobs.keys.toList.sorted)
      }
    ),
  )
end MatrixCollapseSpec
