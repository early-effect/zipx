package zipx.core

import zio.test.*

/** Affected-gating for [[Phase.Deploy]], and the rejection that makes it safe to turn on.
  *
  * Why this is not simply "one more predicate returns true", and why it is tested at this length:
  *
  * [[Capability.deploy]] needs [[Capability.DockerName]] by default and is [[CapabilityScope.Aggregate]] by default, so
  * before this change turning on [[PlanConfig.affectedPublish]] alone produced a latent 404 on main. `tolerateSkips`
  * gave the deploy `!cancelled() && needs.docker-<m>.result != 'failure'`, so an affected-*skipped* `docker-stoWorker`
  * left the deploy **running**, pulling an image tag that run never pushed. The tolerance is not the bug: it is right
  * for an Aggregate job spanning several modules. So the fix is two-sided, and both sides are pinned below:
  *
  *   1. a Graph deploy can be gated, putting it in lockstep with its own module's publish (suites 2 and 3);
  *   2. the shape that cannot be gated is **refused** rather than generated (suite 4).
  *
  * Plus the two properties that make any of this safe: off by default and byte-identical when off (suite 1), and a
  * release tag deploys everything (suite 3).
  */
object AffectedDeploySpec extends ZIOSpecDefault:
  import Fixtures.*

  private val base = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.AffectedOnPR,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  /** Both knobs on, which is the combination the migration this was built for actually uses: narrow the image push, and
    * narrow the deploy that consumes it, so the two skip together.
    */
  private val on  = base.copy(affectedPublish = true, affectedDeploy = true)
  private val off = base

  /** `docker = true` on the four services, so a Graph docker capability has something to fan out over. */
  private val dockerGraphFixture = sampleGraph.mapNodes {
    case n if n.id.startsWith("service") => n.copy(docker = true)
    case n                               => n
  }

  private def cond(wf: zipx.workflow.Workflow, job: String): String = wf.jobs(job).`if`.getOrElse("")

  private def plan(caps: List[Capability], cfg: PlanConfig, graph: ModuleGraph = dockerGraphFixture) =
    Planner.plan(graph, caps, cfg)

  private def failure(caps: List[Capability], cfg: PlanConfig, graph: ModuleGraph = dockerGraphFixture): String =
    scala.util.Try(plan(caps, cfg, graph)).failed.get.getMessage

  /** A deploy over the docker'd services, `Gate.Always` + a main condition: the real shape, since a repo that pushes
    * images on main pushes rather than on tags is what motivates gating a deploy at all.
    */
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
        // The whole point of a second flag: narrowing image pushes while still reconciling every destination on every
        // run is a legitimate combination, and one switch would take it away.
        val wf = plan(List(dockerExpanded, deployGraph()), base.copy(affectedPublish = true))
        assertTrue(
          cond(wf, "docker-serviceA").contains("needs.affected.outputs.modules"),
          !cond(wf, "deploy-serviceA-prod").contains("needs.affected"),
        )
      },
      test("affectedDeploy alone gates the deploy and leaves the publish unnarrowed") {
        // And the other direction, which is the odder-looking but still coherent combination.
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
        // `affected` is the mode; the phase flags only say which phases the mode reaches. Without the mode there is no
        // `affected` job to read, so this has to be inert rather than half-wired.
        val wf = plan(List(dockerExpanded, deployGraph()), on.copy(affected = AffectedMode.Always))
        assertTrue(
          !wf.jobs.contains("affected"),
          !cond(wf, "deploy-serviceA-prod").contains("needs.affected"),
        )
      },
      test("Aggregate and Layer deploys are untouched by the knob, since only Graph can be narrowed") {
        // Needing nothing gated, so the rejection in the last suite does not apply and this is purely about scope.
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
        // Not merely "both mention affected": the *same* module id on both sides is the property that makes
        // deploy-serviceA-prod run exactly when docker-serviceA did.
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
        // Losing either would be the migration's worst failure: an unapproved or off-branch production deploy. The
        // Environment is not a condition at all, so it has to be checked on the job rather than in the if:.
        val wf = plan(List(dockerExpanded, deployGraph()), on)
        assertTrue(
          cond(wf, "deploy-serviceA-prod").contains("github.ref == 'refs/heads/main'"),
          wf.jobs("deploy-serviceA-prod").environment.contains("production"),
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
          wf.jobs("deploy-serviceA-prd").environment.contains("PRD_AWS_BATCH_WORKER"),
          wf.jobs("deploy-serviceA-stg").environment.contains("STG_AWS_BATCH_WORKER"),
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
        // Note the shape: the capability's own condition is ANDed on *last*, parenthesized, by `andConditions`. The
        // affected clause sits inside the first group, between `!cancelled()` and the need guard.
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
      // The reason part 2 of this change exists at all: `affectedOnTags` was derived from Publish alone, so a
      // tag-gated Graph deploy would have carried `needs: affected` and read its output on a ref where the affected
      // job does not run. Every deploy job would then have tested an empty string: a release that deploys nothing.
      test("a tag-gated Graph deploy forces the affected job onto tag pushes") {
        val wf = plan(List(deployGraph(needs = Nil, gate = Gate.OnReleaseTag)), on)
        assertTrue(
          wf.jobs.contains("affected"),
          !cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      },
      test("with only affectedDeploy on and no Publish capability at all, the tag exclusion is still dropped") {
        // Isolates part 2 from affectedPublish: nothing in this plan is a Publish capability, so the old derivation
        // would have left the exclusion on and broken the release.
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
      // This is the highest-value part of the change: it turns "we found this by reading Planner line by line" into a
      // build-load error for the next consumer.
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
        // A deploy needing a gated *deploy* is a different flag from a deploy needing a gated *publish*, and naming the
        // wrong one sends the reader to a setting that changes nothing.
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
        // The check keys on whether the *producer* is gated, not on whether any flag is set anywhere.
        val wf = plan(List(dockerExpanded, deployAggregate()), base.copy(affectedDeploy = true))
        assertTrue(wf.jobs.contains("deploy-prod"), !cond(wf, "deploy-prod").contains("needs.affected"))
      },
      test("an Aggregate capability needing a narrowed Verify is NOT rejected, since it consumes no artifact") {
        // Verify is always gated, so refusing this would refuse nearly every build that has an Aggregate publish
        // needing `test`. It stays skip-tolerant instead, which is the pre-existing behavior.
        val pub = Capability.publish.copy(needsCapabilities = List(Capability.TestName))
        val wf  = plan(List(Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off), pub), on)
        assertTrue(
          wf.jobs.contains("publish"),
          cond(wf, "publish").contains("needs.test-serviceA.result != 'failure'"),
        )
      },
      test("a Once consumer is not rejected: a fixed build-wide command names no module") {
        // An `announce` that needs `publish` is not broken by one module not publishing, so it keeps the tolerance.
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
