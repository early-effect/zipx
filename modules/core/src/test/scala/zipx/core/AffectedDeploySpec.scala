package zipx.core

import zio.test.*

/** A skip-tolerant Aggregate deploy over an affected-skipped docker job would pull an image that run never pushed. */
object AffectedDeploySpec extends ZIOSpecDefault:
  import Fixtures.*

  private val base = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-SNAPSHOT"),
    affected = AffectedMode.AffectedOnPR,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val on  = base.copy(affectedPublish = true, affectedDeploy = true)
  private val off = base

  private val dockerGraphFixture = sampleGraph.mapNodes {
    case n if n.id.startsWith("service") => n.copy(docker = true)
    case n                               => n
  }

  private def cond(wf: zipx.workflow.Workflow, job: String): String = wf.jobs(job).`if`.getOrElse("")

  private def plan(caps: List[Capability], cfg: PlanConfig, graph: ModuleGraph = dockerGraphFixture) =
    Planner.plan(graph, caps, cfg)

  private def failure(caps: List[Capability], cfg: PlanConfig, graph: ModuleGraph = dockerGraphFixture): String =
    scala.util.Try(plan(caps, cfg, graph)).failed.get.getMessage

  /** `Gate.Always` plus a main condition: a repo that deploys on main pushes, not tags, is one that gates deploys. */
  private def deployGraph(
      needs: List[CapabilityName] = List(Capability.DockerName),
      gate: Gate = Gate.Always,
  ) =
    Capability
      .deployGraph(
        participates = _.docker,
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => List(Target(TargetName("prod"), environment = Some("production"))),
        needsCapabilities = needs,
        gate = gate,
        condition = Option.when(gate == Gate.Always)(JobCondition.refIs("refs/heads/main")),
      )
      .withMatrixCollapse(MatrixCollapse.Off)

  private def deployAggregate(needs: List[CapabilityName] = List(Capability.DockerName)) =
    Capability
      .deploy(
        participates = _.docker,
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => List(Target(TargetName("prod"))),
        needsCapabilities = needs,
      )
      .withMatrixCollapse(MatrixCollapse.Off)

  private def dockerExpanded = Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Off)

  def spec = suite("affected-gating for Deploy")(
    suite("off by default, and its own knob")(
      test("the default is off") {
        assertTrue(!PlanConfig().affectedDeploy)
      },
      test("with it off, a Graph deploy has no affected clause and no affected need") {
        val wf = plan(List(dockerExpanded, deployGraph()), off)
        assertTrue(
          !cond(wf, "deploy-serviceA-prod").contains("needs.affected"),
          !wf.jobs("deploy-serviceA-prod").needs.contains("affected"),
        )
      },
      test("affectedPublish alone does not gate a deploy, so the two knobs are genuinely independent") {
        val wf = plan(List(dockerExpanded, deployGraph()), base.copy(affectedPublish = true))
        assertTrue(
          cond(wf, "docker-serviceA").contains("needs.affected.outputs.modules"),
          !cond(wf, "deploy-serviceA-prod").contains("needs.affected"),
        )
      },
      test("affectedDeploy alone gates the deploy and leaves the publish unnarrowed") {
        val wf = plan(List(dockerExpanded, deployGraph()), base.copy(affectedDeploy = true))
        assertTrue(
          !cond(wf, "docker-serviceA").contains("needs.affected"),
          cond(wf, "deploy-serviceA-prod").contains("contains(fromJson(needs.affected.outputs.modules), 'serviceA')"),
        )
      },
      test("turning it on changes no Verify job's if:, so it reaches only the phase it names") {
        val a =
          plan(List(Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off), dockerExpanded, deployGraph()), base)
        val b =
          plan(
            List(Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off), dockerExpanded, deployGraph()),
            base.copy(affectedDeploy = true),
          )
        assertTrue(
          cond(a, "test-serviceA") == cond(b, "test-serviceA"),
          cond(a, "test-api") == cond(b, "test-api"),
          a.jobs("test-serviceA").needs == b.jobs("test-serviceA").needs,
        )
      },
      test("with AffectedMode.Always, affectedDeploy alone gates nothing") {
        val wf = plan(List(dockerExpanded, deployGraph()), on.copy(affected = AffectedMode.Always))
        assertTrue(
          !wf.jobs.contains("affected"),
          !cond(wf, "deploy-serviceA-prod").contains("needs.affected"),
        )
      },
      test("Aggregate and Layer deploys are untouched by the knob, since only Graph can be narrowed") {
        // `needs = Nil`, so the refusal in the last suite does not apply.
        val aggregate = deployAggregate(needs = Nil)
        val layers    = deployAggregate(needs = Nil).copy(scope = CapabilityScope.Layer)
        val wfA       = plan(List(aggregate), on)
        val wfL       = plan(List(layers), on)
        assertTrue(
          !cond(wfA, "deploy-prod").contains("needs.affected"),
          wfL.jobs.keys.exists(_.startsWith("deploy-L")),
          wfL.jobs.keys.filter(_.startsWith("deploy-L")).forall(id => !cond(wfL, id).contains("needs.affected")),
        )
      },
    ),
    suite("on, a Graph deploy skips exactly when its own module's publish did")(
      test("each deploy job carries its own module's affected clause, with the 'all' escape hatch") {
        val wf = plan(List(dockerExpanded, deployGraph()), on)
        assertTrue(
          cond(wf, "deploy-serviceA-prod").contains("contains(fromJson(needs.affected.outputs.modules), 'serviceA')"),
          cond(wf, "deploy-serviceA-prod").contains("contains(fromJson(needs.affected.outputs.modules), 'all')"),
          !cond(wf, "deploy-serviceA-prod").contains("'serviceB')"),
          wf.jobs("deploy-serviceA-prod").needs.contains("affected"),
        )
      },
      test("the deploy's clause is the same expression as its own docker job's, which is what lockstep means") {
        val wf     = plan(List(dockerExpanded, deployGraph()), on)
        val clause = "(contains(fromJson(needs.affected.outputs.modules), 'serviceA') || " +
          "contains(fromJson(needs.affected.outputs.modules), 'all'))"
        assertTrue(
          cond(wf, "docker-serviceA").contains(clause),
          cond(wf, "deploy-serviceA-prod").contains(clause),
        )
      },
      test("every participating module gets its own deploy clause naming its own id") {
        val wf = plan(List(dockerExpanded, deployGraph()), on)
        assertTrue(
          cond(wf, "deploy-serviceB-prod").contains("'serviceB')"),
          cond(wf, "deploy-serviceC-prod").contains("'serviceC')"),
          !cond(wf, "deploy-serviceB-prod").contains("'serviceC')"),
        )
      },
      test("the main condition and the GitHub Environment survive the narrowing") {
        val wf = plan(List(dockerExpanded, deployGraph()), on)
        assertTrue(
          cond(wf, "deploy-serviceA-prod").contains("github.ref == 'refs/heads/main'"),
          wf.jobs("deploy-serviceA-prod").environment.map(_.name).contains("production"),
          cond(wf, "deploy-serviceA-prod").contains("needs.affected.outputs.modules"),
        )
      },
      test("one job per (module x target), each with its own clause and Environment") {
        val twoTargets = Capability
          .deployGraph(
            participates = _.docker,
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
            targets = _ =>
              List(
                Target(TargetName("stg"), environment = Some("STG_AWS_BATCH_WORKER")),
                Target(TargetName("prd"), environment = Some("PRD_AWS_BATCH_WORKER")),
              ),
            gate = Gate.Always,
            condition = Some(JobCondition.refIs("refs/heads/main")),
          )
          .withMatrixCollapse(MatrixCollapse.Off)
        val wf = plan(List(dockerExpanded, twoTargets), on)
        assertTrue(
          wf.jobs.keys.count(_.startsWith("deploy-")) == 8, // 4 docker'd services x 2 targets
          wf.jobs("deploy-serviceA-prd").environment.map(_.name).contains("PRD_AWS_BATCH_WORKER"),
          wf.jobs("deploy-serviceA-stg").environment.map(_.name).contains("STG_AWS_BATCH_WORKER"),
          cond(wf, "deploy-serviceA-stg").contains("'serviceA')"),
          cond(wf, "deploy-serviceA-prd").contains("'serviceA')"),
        )
      },
      test("Auto folds isomorphic module×target legs into one include job") {
        val twoTargets = Capability.deployGraph(
          participates = _.docker,
          command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
          targets = _ =>
            List(
              Target(TargetName("stg"), environment = Some("STG_AWS_BATCH_WORKER")),
              Target(TargetName("prd"), environment = Some("PRD_AWS_BATCH_WORKER")),
            ),
          gate = Gate.Always,
          condition = Some(JobCondition.refIs("refs/heads/main")),
          needsCapabilities = Nil,
        )
        val wf = plan(List(twoTargets), on.copy(affectedDeploy = false, affected = AffectedMode.Always))
        assertTrue(
          wf.jobs.contains("deploy"),
          wf.jobs.keys.count(_.startsWith("deploy")) == 1,
          wf.jobs("deploy").strategy.exists(_.include.sizeIs == 8),
        )
      },
      test("a failed docker still blocks the deploy, so tolerating skips did not stop tolerating nothing else") {
        val wf = plan(List(dockerExpanded, deployGraph()), on)
        val c  = cond(wf, "deploy-serviceA-prod")
        assertTrue(
          wf.jobs("deploy-serviceA-prod").needs.contains("docker-serviceA"),
          c.contains("needs.docker-serviceA.result != 'failure'"),
          !c.contains("== 'success'"),
        )
      },
      test("the whole if: byte for byte, since this is the string a consumer diffs in their committed ci.yml") {
        val wf = plan(List(dockerExpanded, deployGraph()), on)
        assertTrue(
          cond(wf, "deploy-serviceA-prod") ==
            "(!cancelled() && " +
            "(contains(fromJson(needs.affected.outputs.modules), 'serviceA') || " +
            "contains(fromJson(needs.affected.outputs.modules), 'all')) && " +
            "needs.docker-serviceA.result != 'failure') && " +
            "(github.ref == 'refs/heads/main')"
        )
      },
      test("the plan renders, so none of these conditions is a workflow GitHub would reject") {
        val wf =
          plan(List(Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off), dockerExpanded, deployGraph()), on)
        assertTrue(zipx.workflow.Render.render(wf).isRight)
      },
    ),
    suite("a release tag still deploys everything")(
      // A tag-gated deploy reads `affected`'s output, so `affected` must run on tags or every deploy tests "".
      test("a tag-gated Graph deploy forces the affected job onto tag pushes") {
        val wf = plan(List(deployGraph(needs = Nil, gate = Gate.OnReleaseTag)), on)
        assertTrue(
          wf.jobs.contains("affected"),
          !cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      },
      test("with only affectedDeploy on and no Publish capability at all, the tag exclusion is still dropped") {
        val wf = plan(List(deployGraph(needs = Nil, gate = Gate.OnReleaseTag)), base.copy(affectedDeploy = true))
        assertTrue(!cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"))
      },
      test("a Verify capability alongside keeps its own tag exclusion, which belongs to it and not to the setup job") {
        val wf = plan(
          List(
            Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off),
            deployGraph(needs = Nil, gate = Gate.OnReleaseTag),
          ),
          on,
        )
        assertTrue(
          !cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"),
          cond(wf, "test-serviceA").contains("!startsWith(github.ref, 'refs/tags/')"),
          wf.jobs.keys.count(_ == "affected") == 1,
        )
      },
      test("with the knob off, a tag-gated Graph deploy leaves the affected job's exclusion alone") {
        val wf = plan(
          List(
            Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off),
            deployGraph(needs = Nil, gate = Gate.OnReleaseTag),
          ),
          off,
        )
        assertTrue(cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"))
      },
      test("the affected job runs on a merged-PR push once Deploy reads it") {
        val wf = plan(
          List(
            Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off),
            dockerExpanded,
            deployGraph(),
          ),
          on.copy(skipMergedPrPush = true),
        )
        assertTrue(
          !cond(wf, "affected").contains("needs.verify-gate.outputs.run == 'true'"),
          cond(wf, "test-serviceA").contains("needs.verify-gate.outputs.run == 'true'"),
          !wf.jobs("deploy-serviceA-prod").needs.contains("verify-gate"),
          cond(wf, "deploy-serviceA-prod").contains("needs.affected.outputs.modules"),
        )
      },
      test("fail-open is unchanged: an unusable diff deploys everything") {
        assertTrue(
          Affected.outputModules(dockerGraphFixture, None) == Affected.AllSentinel,
          cond(plan(List(dockerExpanded, deployGraph()), on), "deploy-serviceA-prod").contains("'all')"),
        )
      },
    ),
    suite("the shape that cannot be gated is refused, not generated")(
      test("an Aggregate deploy needing an affected-gated Graph docker is rejected") {
        val err = failure(List(dockerExpanded, deployAggregate()), on)
        assertTrue(
          err.contains("'deploy'"),
          err.contains("Aggregate"),
          err.contains("'docker'"),
          err.contains("artifact nobody built"),
        )
      },
      test("the message names all three ways out, so the error is fixable from itself") {
        val err = failure(List(dockerExpanded, deployAggregate()), on)
        assertTrue(
          err.contains("CapabilityScope.Graph"),
          err.contains("moving tag"),
          err.contains("zipxAffectedPublish"),
        )
      },
      test("the flag named is the producer's own, so turning off the one it names actually fixes it") {
        val gatedDeploy = deployGraph(needs = Nil).copy(name = CapabilityName("promote"))
        val consumer    = deployAggregate(needs = List(CapabilityName("promote")))
        val err         = failure(List(gatedDeploy, consumer), on)
        assertTrue(err.contains("zipxAffectedDeploy"), !err.contains("zipxAffectedPublish"))
      },
      test("a Layer deploy is rejected too, for the same reason: its job spans several modules") {
        val err = failure(List(dockerExpanded, deployAggregate().copy(scope = CapabilityScope.Layer)), on)
        assertTrue(err.contains("Layer"), err.contains("'docker'"))
      },
      test("the Graph spelling the error recommends is accepted, which is what makes the advice actionable") {
        val wf = plan(List(dockerExpanded, deployGraph()), on)
        assertTrue(wf.jobs.contains("deploy-serviceA-prod"))
      },
      test("an Aggregate producer is fine: an Aggregate docker job has nothing in it to skip") {
        val wf = plan(List(Capability.docker, deployAggregate()), on)
        assertTrue(wf.jobs("deploy-prod").needs.contains("docker"), !cond(wf, "deploy-prod").contains("!cancelled()"))
      },
      test("with both knobs off nothing is rejected, so an existing build is unaffected by the check") {
        val wf = plan(List(dockerExpanded, deployAggregate()), off)
        assertTrue(wf.jobs.contains("deploy-prod"))
      },
      test("with only affectedDeploy on, an Aggregate deploy needing an ungated docker is fine") {
        val wf = plan(List(dockerExpanded, deployAggregate()), base.copy(affectedDeploy = true))
        assertTrue(wf.jobs.contains("deploy-prod"), !cond(wf, "deploy-prod").contains("needs.affected"))
      },
      test("an Aggregate capability needing a narrowed Verify is NOT rejected, since it consumes no artifact") {
        val pub = Capability.publish.copy(needsCapabilities = List(Capability.TestName))
        val wf  = plan(List(Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off), pub), on)
        assertTrue(
          wf.jobs.contains("publish"),
          cond(wf, "publish").contains("needs.test-serviceA.result != 'failure'"),
        )
      },
      test("a Once consumer is not rejected: a fixed build-wide command names no module") {
        val announce = Capability.once(
          CapabilityName("announce"),
          SbtCommand.unsafeTask("announce"),
          phase = Phase.Deploy,
          gate = Gate.OnReleaseTag,
          needsCapabilities = List(Capability.DockerName),
        )
        val wf = plan(List(dockerExpanded, announce), on)
        assertTrue(
          wf.jobs.contains("announce"),
          cond(wf, "announce").contains("needs.docker-serviceA.result != 'failure'"),
        )
      },
    ),
  )
end AffectedDeploySpec
