package zipx.core

import zipx.core.Rendered.yaml
import zipx.workflow.Render
import zio.test.*

/** Every [[MatrixCollapse]] mode against Graph / Aggregate / Layer, including Auto soft-fail and include matrices. */
object MatrixCollapseSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val baseConfig = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val independentDocker = GraphFixture(
    List(
      ModuleNode(ModuleId("api"), docker = true, crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("web"), docker = true, crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("batch"), docker = true, crossScalaVersions = List(scala3)),
    )
  )

  private val orderedDocker = GraphFixture(
    List(
      ModuleNode(ModuleId("maple"), docker = true, crossScalaVersions = List(scala3)),
      ModuleNode(
        ModuleId("cedar"),
        dependsOn = List("maple"),
        docker = true,
        crossScalaVersions = List(scala3),
      ),
      ModuleNode(
        ModuleId("spruce"),
        dependsOn = List("cedar"),
        docker = true,
        crossScalaVersions = List(scala3),
      ),
    )
  )

  private def imageCap(mode: MatrixCollapse): Capability =
    Capability
      .custom(
        name = CapabilityName("image"),
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("Docker/publishLocal")),
        participates = _.docker,
        ordering = Ordering.ParallelWithUpstream,
        gate = Gate.Always,
        phase = Phase.Publish,
      )
      .withMatrixCollapse(mode)

  private def dockerCap(mode: MatrixCollapse, ordering: Ordering = Ordering.DependencyOrdered): Capability =
    Capability.dockerGraph.copy(ordering = ordering).withMatrixCollapse(mode)

  private def deployGraph(targets: List[Target], mode: MatrixCollapse): Capability =
    Capability
      .deployGraph(
        participates = _.docker,
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => targets,
        needsCapabilities = Nil,
      )
      .withMatrixCollapse(mode)

  private def deployAgg(targets: List[Target], mode: MatrixCollapse): Capability =
    Capability
      .deploy(
        participates = _.docker,
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => targets,
      )
      .withMatrixCollapse(mode)

  private def deployLayer(targets: List[Target], mode: MatrixCollapse): Capability =
    deployAgg(targets, mode).copy(scope = CapabilityScope.Layer)

  private def tryPlan(
      graph: ModuleGraph,
      caps: List[Capability],
      cfg: PlanConfig = baseConfig,
  ): Either[String, zipx.workflow.Workflow] =
    try Right(Planner.plan(graph, caps, cfg))
    catch case e: RuntimeException => Left(e.getMessage)

  private val gMode: Gen[Any, MatrixCollapse] =
    Gen.elements(MatrixCollapse.values.toList*)

  private val gCollapseMode: Gen[Any, MatrixCollapse] =
    Gen.elements(MatrixCollapse.Auto, MatrixCollapse.Strict, MatrixCollapse.Coarse)

  private val gTargetName: Gen[Any, TargetName] =
    Gen.elements(TargetName("prod"), TargetName("staging"), TargetName("eu"), TargetName("us"))

  /** Simple-matrix-safe: environment equals name (or absent). */
  private val gSimpleTargets: Gen[Any, List[Target]] =
    for names <- Gen.listOfBounded(2, 4)(gTargetName).map(_.distinctBy(n => n: String)).filter(_.sizeIs >= 2)
    yield names.map(name => Target(name, environment = Some(name)))

  /** Include-matrix-safe: environment differs from name; shared env keys and no conditions. */
  private val gIncludeTargets: Gen[Any, List[Target]] =
    for names <- Gen.listOfBounded(2, 4)(gTargetName).map(_.distinctBy(n => n: String)).filter(_.sizeIs >= 2)
    yield names.zipWithIndex.map { (name, i) =>
      Target(
        name,
        environment = Some(s"ENV_${name.toUpperCase}"),
        env = Map("AWS_REGION" -> EnvValue.plain(s"region-$i"), "TIER" -> EnvValue.plain(name)),
      )
    }

  /** Collapse-refused: same env keys but differing conditions. */
  private val gIncompatibleTargets: Gen[Any, List[Target]] =
    for names <- Gen.listOfBounded(2, 3)(gTargetName).map(_.distinctBy(n => n: String)).filter(_.sizeIs >= 2)
    yield names.zipWithIndex.map { (name, i) =>
      Target(
        name,
        environment = Some(name),
        env = Map("TIER" -> EnvValue.plain(name)),
        condition = Option.when(i == 0)(JobCondition.varNonEmpty("ONLY_FIRST")),
      )
    }

  private def dockerJobCount(wf: zipx.workflow.Workflow): Int =
    wf.jobs.keys.count(k => k == "docker" || k.startsWith("docker-"))

  private def deployJobCount(wf: zipx.workflow.Workflow): Int =
    wf.jobs.keys.count(k => k == "deploy" || k.startsWith("deploy"))

  private def imageJobCount(wf: zipx.workflow.Workflow): Int =
    wf.jobs.keys.count(k => k == "image" || k.startsWith("image-"))

  def spec = suite("MatrixCollapse")(
    suite("cascade")(
      test("defaults to Auto") {
        assertTrue(MatrixCollapse.effective(Capability.dockerGraph, baseConfig) == MatrixCollapse.Auto)
      },
      test("effective = cap.orElse(plan).getOrElse(Auto)") {
        check(
          Gen.option(gMode),
          Gen.option(gMode),
        ) { (capMode, planMode) =>
          val cap = imageCap(capMode.getOrElse(MatrixCollapse.Auto)).copy(matrixCollapse = capMode)
          val cfg = baseConfig.copy(matrixCollapse = planMode.map(CapabilityName("image") -> _).toMap)
          assertTrue(MatrixCollapse.effective(cap, cfg) == capMode.orElse(planMode).getOrElse(MatrixCollapse.Auto))
        }
      },
      test("capability Off vetoes every plan mode") {
        check(gMode) { planMode =>
          val cfg = baseConfig.copy(matrixCollapse = Map(Capability.DockerName -> planMode))
          val cap = Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Off)
          assertTrue(MatrixCollapse.effective(cap, cfg) == MatrixCollapse.Off)
        }
      },
    ),
    suite("targetsCompatible")(
      test("empty targets are Simple") {
        assertTrue(MatrixCollapse.targetsCompatible(Nil) == Right(MatrixCollapse.TargetMatrix.Simple))
      },
      test("environment == name is Simple for any multi-target list") {
        check(gSimpleTargets) { targets =>
          assertTrue(MatrixCollapse.targetsCompatible(targets) == Right(MatrixCollapse.TargetMatrix.Simple))
        }
      },
      test("environment != name with shared shape is Include") {
        check(gIncludeTargets) { targets =>
          assertTrue(MatrixCollapse.targetsCompatible(targets) == Right(MatrixCollapse.TargetMatrix.Include))
        }
      },
      test("differing conditions refuse collapse") {
        check(gIncompatibleTargets) { targets =>
          assertTrue(MatrixCollapse.targetsCompatible(targets).isLeft)
        }
      },
      test("includeRows are the cartesian product of modules × targets") {
        check(gIncludeTargets) { targets =>
          val modules = List("api", "web")
          val rows    = MatrixCollapse.includeRows(modules, targets)
          assertTrue(
            rows.size == modules.size * targets.size,
            rows.forall(r => r.contains("module") && r.contains("target") && r.contains("environment")),
            targets.forall(t => rows.exists(r => r.get("target").contains(t.name: String))),
          )
        }
      },
    ),
    suite("Off")(
      test("Graph job count equals participating modules") {
        check(Gen.elements(independentDocker, orderedDocker)) { graph =>
          val n  = graph.nodes.count(_.docker)
          val wf = Planner.plan(graph, List(dockerCap(MatrixCollapse.Off)), baseConfig)
          assertTrue(dockerJobCount(wf) == n, wf.jobs.keys.forall(!_.equals("docker")))
        }
      },
      test("Aggregate target job count equals target count") {
        check(gSimpleTargets) { targets =>
          val wf = Planner.plan(independentDocker, List(deployAgg(targets, MatrixCollapse.Off)), baseConfig)
          assertTrue(deployJobCount(wf) == targets.size)
        }
      },
      test("Layer target job count equals waves × targets") {
        check(gSimpleTargets) { targets =>
          val waves = orderedDocker.subsetLayers(_.docker).size
          val wf    = Planner.plan(orderedDocker, List(deployLayer(targets, MatrixCollapse.Off)), baseConfig)
          assertTrue(wf.jobs.keys.count(_.startsWith("deploy-L")) == waves * targets.size)
        }
      },
    ),
    suite("Auto")(
      test("collapses independent Graph modules into one matrix job") {
        val wf = Planner.plan(independentDocker, List(imageCap(MatrixCollapse.Auto)), baseConfig)
        assertTrue(
          imageJobCount(wf) == 1,
          wf.jobs("image").strategy.exists(_.matrix.get("module").contains(List("api", "batch", "web"))),
        )
      },
      test("expands Graph when same-capability needs would be dropped") {
        assertTrue(!MatrixCollapse.graphCollapseFeasible(dockerCap(MatrixCollapse.Auto), orderedDocker))
        val wf = Planner.plan(orderedDocker, List(dockerCap(MatrixCollapse.Auto)), baseConfig)
        assertTrue(dockerJobCount(wf) == 3, wf.jobs.keys.exists(_.startsWith("docker-")))
      },
      test("never fails generate on ordered Graph") {
        check(Gen.elements(MatrixCollapse.Auto, MatrixCollapse.Off, MatrixCollapse.Coarse)) { mode =>
          assertTrue(tryPlan(orderedDocker, List(dockerCap(mode))).isRight)
        }
      },
      test("Aggregate Simple targets collapse to one job") {
        check(gSimpleTargets) { targets =>
          val wf = Planner.plan(independentDocker, List(deployAgg(targets, MatrixCollapse.Auto)), baseConfig)
          assertTrue(
            deployJobCount(wf) == 1,
            wf.jobs("deploy").strategy.exists(_.matrix("target").sizeIs == targets.size),
            wf.jobs("deploy").strategy.exists(_.include.isEmpty),
          )
        }
      },
      test("Aggregate Include targets collapse with matrix.include") {
        check(gIncludeTargets) { targets =>
          val wf  = Planner.plan(independentDocker, List(deployAgg(targets, MatrixCollapse.Auto)), baseConfig)
          val job = wf.jobs("deploy")
          assertTrue(
            deployJobCount(wf) == 1,
            job.strategy.exists(_.include.sizeIs == targets.size),
            job.environment.contains("${{ matrix.environment }}"),
          )
        }
      },
      test("Aggregate expands when targets are incompatible") {
        check(gIncompatibleTargets) { targets =>
          val wf = Planner.plan(independentDocker, List(deployAgg(targets, MatrixCollapse.Auto)), baseConfig)
          assertTrue(deployJobCount(wf) == targets.size)
        }
      },
      test("Layer Simple targets keep one job per wave") {
        check(gSimpleTargets) { targets =>
          val waves = orderedDocker.subsetLayers(_.docker).size
          val wf    = Planner.plan(orderedDocker, List(deployLayer(targets, MatrixCollapse.Auto)), baseConfig)
          assertTrue(wf.jobs.keys.count(_.startsWith("deploy-L")) == waves)
        }
      },
      test("Graph JobPerTarget with Include rows collapses under Auto") {
        check(gIncludeTargets) { targets =>
          val wf  = Planner.plan(independentDocker, List(deployGraph(targets, MatrixCollapse.Auto)), baseConfig)
          val job = wf.jobs("deploy")
          assertTrue(
            deployJobCount(wf) == 1,
            job.strategy.exists(_.include.nonEmpty),
            job.strategy.exists(_.include.sizeIs == 3 * targets.size),
          )
        }
      },
      test("graphCollapseFeasible tracks independence and target compatibility") {
        check(gSimpleTargets, gIncompatibleTargets) { (ok, bad) =>
          val feasible   = deployGraph(ok, MatrixCollapse.Auto)
          val infeasible = deployGraph(bad, MatrixCollapse.Auto)
          assertTrue(
            MatrixCollapse.graphCollapseFeasible(feasible, independentDocker),
            !MatrixCollapse.graphCollapseFeasible(infeasible, independentDocker),
            !MatrixCollapse.graphCollapseFeasible(dockerCap(MatrixCollapse.Auto), orderedDocker),
          )
        }
      },
    ),
    suite("Strict")(
      test("collapses independent Graph like Auto") {
        check(Gen.elements(MatrixCollapse.Auto, MatrixCollapse.Strict)) { mode =>
          val a = Planner.plan(independentDocker, List(imageCap(mode)), baseConfig)
          val b = Planner.plan(independentDocker, List(imageCap(MatrixCollapse.Strict)), baseConfig)
          assertTrue(
            imageJobCount(a) == 1,
            a.jobs("image").strategy.map(_.matrix) == b.jobs("image").strategy.map(_.matrix),
          )
        }
      },
      test("fails when same-capability inter-module needs exist") {
        val result = tryPlan(orderedDocker, List(dockerCap(MatrixCollapse.Strict)))
        assertTrue(result.swap.exists(_.contains("MatrixCollapse.Strict")))
      },
      test("fails Graph JobPerTarget even when targets are Simple-compatible") {
        check(gSimpleTargets) { targets =>
          val result = tryPlan(independentDocker, List(deployGraph(targets, MatrixCollapse.Strict)))
          assertTrue(result.swap.exists(_.contains("JobPerTarget")))
        }
      },
      test("Aggregate Simple targets collapse to one job") {
        check(gSimpleTargets) { targets =>
          val wf = Planner.plan(independentDocker, List(deployAgg(targets, MatrixCollapse.Strict)), baseConfig)
          assertTrue(
            deployJobCount(wf) == 1,
            wf.jobs("deploy").strategy.exists(_.matrix("target").sizeIs == targets.size),
          )
        }
      },
      test("Aggregate incompatible targets fail generate") {
        check(gIncompatibleTargets) { targets =>
          assertTrue(tryPlan(independentDocker, List(deployAgg(targets, MatrixCollapse.Strict))).isLeft)
        }
      },
      test("Layer job count equals wave count for Simple targets") {
        check(gSimpleTargets) { targets =>
          val waves = orderedDocker.subsetLayers(_.docker).size
          val wf    = Planner.plan(orderedDocker, List(deployLayer(targets, MatrixCollapse.Strict)), baseConfig)
          assertTrue(wf.jobs.keys.count(_.startsWith("deploy-L")) == waves)
        }
      },
    ),
    suite("Coarse")(
      test("collapses ordered Graph and drops same-cap needs") {
        val wf = Planner.plan(orderedDocker, List(dockerCap(MatrixCollapse.Coarse)), baseConfig)
        assertTrue(
          dockerJobCount(wf) == 1,
          !wf.jobs("docker").needs.exists(_.startsWith("docker-")),
          MatrixCollapse.warnings(List(dockerCap(MatrixCollapse.Coarse)), orderedDocker, baseConfig).nonEmpty,
        )
      },
      test("Graph module × Simple target matrix") {
        check(gSimpleTargets) { targets =>
          val wf  = Planner.plan(independentDocker, List(deployGraph(targets, MatrixCollapse.Coarse)), baseConfig)
          val job = wf.jobs("deploy")
          assertTrue(
            deployJobCount(wf) == 1,
            job.strategy.exists(s => s.matrix.contains("module") && s.matrix.contains("target")),
            job.environment.contains("${{ matrix.target }}"),
          )
        }
      },
      test("Graph module × Include target uses include rows") {
        check(gIncludeTargets) { targets =>
          val wf  = Planner.plan(independentDocker, List(deployGraph(targets, MatrixCollapse.Coarse)), baseConfig)
          val job = wf.jobs("deploy")
          assertTrue(
            deployJobCount(wf) == 1,
            job.strategy.exists(_.include.sizeIs == 3 * targets.size),
            job.environment.contains("${{ matrix.environment }}"),
          )
        }
      },
      test("Off job count is always ≥ Coarse job count on independent Graph") {
        check(gSimpleTargets) { targets =>
          val off    = Planner.plan(independentDocker, List(deployGraph(targets, MatrixCollapse.Off)), baseConfig)
          val coarse = Planner.plan(independentDocker, List(deployGraph(targets, MatrixCollapse.Coarse)), baseConfig)
          assertTrue(deployJobCount(off) >= deployJobCount(coarse), deployJobCount(coarse) == 1)
        }
      },
    ),
    suite("affected gating under collapse")(
      test("collapsed job if never references matrix") {
        check(gCollapseMode) { mode =>
          val cfg =
            if mode == MatrixCollapse.Auto || mode == MatrixCollapse.Strict then
              baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
            else baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
          val graph = if mode == MatrixCollapse.Coarse then orderedDocker else independentDocker
          val cap   =
            if mode == MatrixCollapse.Coarse then dockerCap(mode)
            else imageCap(mode)
          val id     = if mode == MatrixCollapse.Coarse then "docker" else "image"
          val job    = Planner.plan(graph, List(cap), cfg).jobs(id)
          val ifLine = job.`if`.getOrElse("")
          assertTrue(
            !ifLine.contains("matrix."),
            ifLine.contains("needs.affected.outputs.modules != '[]'"),
            job.steps.forall(_.`if`.exists(_.contains("matrix.module"))),
          )
        }
      },
      test("Off keeps per-module contains in job if") {
        val cfg = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        val api = Planner.plan(independentDocker, List(imageCap(MatrixCollapse.Off)), cfg).jobs("image-api")
        assertTrue(
          api.`if`.exists(_.contains("contains(fromJson(needs.affected.outputs.modules), 'api')")),
          !api.`if`.exists(_.contains("matrix.")),
        )
      },
      test("rendered YAML job if line is matrix-free for every collapsing mode") {
        check(Gen.elements(MatrixCollapse.Auto, MatrixCollapse.Strict, MatrixCollapse.Coarse)) { mode =>
          val cfg   = baseConfig.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
          val graph = if mode == MatrixCollapse.Coarse then orderedDocker else independentDocker
          val cap   = if mode == MatrixCollapse.Coarse then dockerCap(mode) else imageCap(mode)
          val id    = if mode == MatrixCollapse.Coarse then "docker" else "image"
          val job   = Planner.plan(graph, List(cap), cfg).jobs(id)
          val yaml  = Render.renderJob(id, job).yaml
          val jobIf = yaml.linesIterator.find(_.trim.startsWith("if:")).getOrElse("")
          assertTrue(
            !jobIf.contains("matrix."),
            jobIf.contains("needs.affected.outputs.modules"),
            yaml.contains("matrix.module"),
          )
        }
      },
    ),
    suite("Auto vs Off vs Strict job-count laws")(
      test("on independent Graph with no targets, Auto and Strict match Off×1 and Strict×1") {
        check(Gen.elements(imageCap(MatrixCollapse.Auto), imageCap(MatrixCollapse.Strict))) { cap =>
          val collapsed = Planner.plan(independentDocker, List(cap), baseConfig)
          val off       = Planner.plan(independentDocker, List(imageCap(MatrixCollapse.Off)), baseConfig)
          assertTrue(imageJobCount(collapsed) == 1, imageJobCount(off) == 3)
        }
      },
      test("Off Aggregate target count equals targets; collapsing modes equal 1 when compatible") {
        check(gSimpleTargets, Gen.elements(MatrixCollapse.Auto, MatrixCollapse.Strict, MatrixCollapse.Coarse)) {
          (targets, mode) =>
            val off = Planner.plan(independentDocker, List(deployAgg(targets, MatrixCollapse.Off)), baseConfig)
            val on  = Planner.plan(independentDocker, List(deployAgg(targets, mode)), baseConfig)
            assertTrue(deployJobCount(off) == targets.size, deployJobCount(on) == 1)
        }
      },
    ),
  )
end MatrixCollapseSpec
