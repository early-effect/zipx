package zipx.core

import zio.test.*

/** Affected-gating for [[Phase.Publish]] (#70).
  *
  * Tested at this length because the change is not "one more predicate returns true". Narrowing a Publish job means a
  * job can now *skip* in a phase where nothing skipped before, and GitHub's implicit `success()` turns a skipped need
  * into a skipped dependent. `Capability.deploy` needs `docker` by default, so getting that wrong would silently skip
  * the deploy that wanted the other modules' images: the exact class of failure this feature is supposed to save money
  * on, not create.
  *
  * The four properties each suite below pins down:
  *   1. off by default, and off means byte-identical output;
  *   2. on narrows Graph Publish and nothing else;
  *   3. a release tag still publishes everything;
  *   4. a dependent tolerates a *skipped* need without tolerating a *failed* one.
  */
object AffectedPublishSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val base = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.AffectedOnPR,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val on  = base.copy(affectedPublish = true)
  private val off = base

  /** `docker = true` on the four services, so `dockerExpanded` has something to fan out over. */
  private val dockerGraphFixture = sampleGraph.mapNodes {
    case n if n.id.startsWith("service") => n.copy(docker = true)
    case n                               => n
  }

  private def cond(wf: zipx.workflow.Workflow, job: String): String = wf.jobs(job).`if`.getOrElse("")

  private def plan(caps: List[Capability], cfg: PlanConfig, graph: ModuleGraph = sampleGraph) =
    Planner.plan(graph, caps, cfg)

  private def publishExpanded = Capability.publishGraph.withMatrixCollapse(MatrixCollapse.Off)
  private def dockerExpanded  = Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Off)
  private def testExpanded    = Capability.testGraph.withMatrixCollapse(MatrixCollapse.Off)

  private val affectedClause = "contains(fromJson(needs.affected.outputs.modules), 'schema')"

  def spec = suite("affected-gating for Publish")(
    suite("off by default, because under-publishing is loudly broken")(
      // The default matters more than usual here: every existing consumer's committed ci.yml has to stay byte-identical
      // or `zipxWorkflowCheck` fails on upgrade, for a feature they did not ask for.
      test("the default is off") {
        assertTrue(!PlanConfig().affectedPublish)
      },
      test("with it off, a Graph Publish job has no affected clause and no affected need") {
        val wf = plan(List(publishExpanded), off)
        assertTrue(
          !cond(wf, "publish-schema").contains("needs.affected"),
          !wf.jobs("publish-schema").needs.contains("affected"),
          cond(wf, "publish-schema").contains("startsWith(github.ref, 'refs/tags/v')"),
        )
      },
      test("with it off, a Graph Publish alongside a Graph Verify leaves Publish alone") {
        val wf = plan(List(testExpanded, publishExpanded), off)
        assertTrue(
          // The affected job exists, for Verify's sake, and Publish simply does not read it.
          wf.jobs.contains("affected"),
          cond(wf, "test-schema").contains("needs.affected.outputs.modules"),
          !cond(wf, "publish-schema").contains("needs.affected"),
        )
      },
      test("turning it on changes no Verify job's if:, so the two knobs are genuinely independent") {
        val verifyOff = plan(List(testExpanded, publishExpanded), off)
        val verifyOn  = plan(List(testExpanded, publishExpanded), on)
        assertTrue(
          cond(verifyOff, "test-schema") == cond(verifyOn, "test-schema"),
          cond(verifyOff, "test-api") == cond(verifyOn, "test-api"),
          verifyOff.jobs("test-schema").needs == verifyOn.jobs("test-schema").needs,
        )
      },
      test("with AffectedMode.Always, affectedPublish alone gates nothing") {
        // `affected` is the mode; `affectedPublish` only says which phases the mode reaches. Without the mode there is
        // no `affected` job to read, so this combination has to be inert rather than half-wired.
        val wf = plan(List(publishExpanded), on.copy(affected = AffectedMode.Always))
        assertTrue(
          !wf.jobs.contains("affected"),
          !cond(wf, "publish-schema").contains("needs.affected"),
          !cond(wf, "publish-schema").contains("!cancelled()"),
        )
      },
    ),
    suite("on, a Graph Publish job narrows to the affected modules")(
      test("the job reads the affected output, with the 'all' sentinel as the escape hatch") {
        val wf = plan(List(publishExpanded), on)
        assertTrue(
          cond(wf, "publish-schema").contains(affectedClause),
          cond(wf, "publish-schema").contains("'all'"),
          wf.jobs("publish-schema").needs.contains("affected"),
        )
      },
      test("the release gate survives the narrowing: both clauses are present") {
        // Losing the tag gate here would publish snapshots off every PR, which is worse than publishing too much.
        val wf = plan(List(publishExpanded), on)
        assertTrue(
          cond(wf, "publish-schema").contains("startsWith(github.ref, 'refs/tags/v')"),
          cond(wf, "publish-schema").contains(affectedClause),
        )
      },
      test("every participating module gets its own clause naming its own id") {
        val wf = plan(List(publishExpanded), on)
        assertTrue(
          cond(wf, "publish-api").contains("'api')"),
          cond(wf, "publish-clientA").contains("'clientA')"),
          !cond(wf, "publish-api").contains("'clientA')"),
        )
      },
      test("a Graph docker Publish narrows too, which is the case the issue is about") {
        val wf = plan(List(dockerExpanded), on, dockerGraphFixture)
        assertTrue(
          cond(wf, "docker-serviceA").contains("contains(fromJson(needs.affected.outputs.modules), 'serviceA')"),
          !cond(wf, "docker-serviceA").contains("'serviceB')"),
          wf.jobs.keys.count(_.startsWith("docker-")) == 4,
        )
      },
      test("Aggregate Publish is never narrowed: one sbt session over every module has nothing to skip") {
        val wf = plan(List(Capability.publish), on)
        assertTrue(
          !cond(wf, "publish").contains("needs.affected"),
          !wf.jobs("publish").needs.contains("affected"),
          // And with no Graph capability at all, there is no affected job to emit.
          !wf.jobs.contains("affected"),
        )
      },
      test("Layer Publish is never narrowed either") {
        val wf = plan(List(Capability.publishLayers), on)
        assertTrue(
          wf.jobs.keys.exists(_.startsWith("publish-L")),
          wf.jobs.keys.filter(_.startsWith("publish-L")).forall(id => !cond(wf, id).contains("needs.affected")),
        )
      },
      test("Deploy is not narrowed by this knob: affectedDeploy is its own switch") {
        // The two are deliberately separate (see AffectedDeploySpec): narrowing image pushes while still reconciling
        // every destination on every run is a legitimate combination, and one switch would take it away.
        val deploy = Capability
          .deployGraph(
            participates = _.id == "serviceA",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
            targets = _ => List(Target(TargetName("prod"))),
            needsCapabilities = Nil,
          )
          .withMatrixCollapse(MatrixCollapse.Off)
        val wf = plan(List(deploy), on, dockerGraphFixture)
        assertTrue(
          !cond(wf, "deploy-serviceA-prod").contains("needs.affected"),
          !wf.jobs("deploy-serviceA-prod").needs.contains("affected"),
        )
      },
    ),
    suite("a release tag still publishes everything")(
      // The subtle case the issue raises: "affected relative to what base?" for a tag after a series of merges. The
      // answer is that there is no base and no diff at all, so the question does not arise.
      test("the affected script emits the 'all' sentinel for anything that is not a PR or a tracked push") {
        val wf     = plan(List(publishExpanded), on)
        val script = wf.jobs("affected").steps.find(_.id.contains("compute")).flatMap(_.run).getOrElse("")
        assertTrue(
          script.contains("""modules='["all"]'"""),
          script.contains("pull_request"),
          !script.contains("refs/tags"),
        )
      },
      test("the affected job runs on a tag push once Publish reads it") {
        // Without this the whole release is skipped: `affected` would carry Verify's tag exclusion, skip on the tag, and
        // every Publish job's membership test would read an empty output.
        val wf = plan(List(publishExpanded), on)
        assertTrue(!cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"))
      },
      test("with only Verify gated, the affected job keeps its tag exclusion") {
        val wf = plan(List(testExpanded), off)
        assertTrue(cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"))
      },
      test("Verify and Publish share one affected job, and it runs on tags") {
        val wf = plan(List(testExpanded, publishExpanded), on)
        assertTrue(
          wf.jobs.keys.count(_ == "affected") == 1,
          !cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"),
          // Verify's own jobs still carry the exclusion; it belongs to them, not to the setup job.
          cond(wf, "test-schema").contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      },
      test("the affected job runs on a merged-PR push once Publish reads it") {
        // Without this the whole publish is skipped: `affected` would carry Verify's merged-PR skip, skip after
        // merge, and every Publish job's membership test would `fromJson` an empty output.
        val wf = plan(List(testExpanded, publishExpanded), on.copy(skipMergedPrPush = true))
        assertTrue(
          !cond(wf, "affected").contains("needs.verify-gate.outputs.run == 'true'"),
          !cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"),
          cond(wf, "test-schema").contains("needs.verify-gate.outputs.run == 'true'"),
          !wf.jobs("publish-schema").needs.contains("verify-gate"),
        )
      },
      test("with only Verify gated, the affected job still skips after a merged PR") {
        val wf = plan(List(testExpanded), off.copy(skipMergedPrPush = true))
        assertTrue(
          cond(wf, "affected").contains("needs.verify-gate.outputs.run == 'true'"),
          cond(wf, "test-schema").contains("needs.verify-gate.outputs.run == 'true'"),
        )
      },
      test("affected emits JSON whenever a later job will fromJson it") {
        val wf        = plan(List(testExpanded, publishExpanded), on.copy(skipMergedPrPush = true))
        val fromJson  = "fromJson(needs.affected.outputs.modules)"
        val consumers = wf.jobs.filter { (id, job) =>
          id != "affected" && (
            job.`if`.exists(_.contains(fromJson)) ||
              job.steps.exists(_.`if`.exists(_.contains(fromJson)))
          )
        }
        val mergedPrSkip = "needs.verify-gate.outputs.run == 'true'"
        assertTrue(
          consumers.nonEmpty,
          consumers.forall((_, job) => job.needs.contains("affected")),
          // affected's skip condition is a subset of the consumers': the merged-PR skip is not on affected, so
          // it cannot skip while a consumer's if: is still true.
          !cond(wf, "affected").contains(mergedPrSkip),
          consumers.exists((_, job) => job.`if`.exists(_.contains("needs.affected.outputs.modules"))),
        )
      },
      test("fail-open is unchanged: an unusable diff publishes everything") {
        // The planner side of this is the `|| 'all'` clause above; this is the other half, and it is what makes a broken
        // base ref cost CI minutes rather than a missing release artifact.
        assertTrue(
          Affected.outputModules(sampleGraph, None) == Affected.AllSentinel,
          cond(plan(List(publishExpanded), on), "publish-schema").contains("'all')"),
        )
      },
    ),
    suite("a dependent tolerates a skipped need, but not a failed one")(
      test("a Graph deploy needing a narrowed docker becomes skip-tolerant") {
        val deploy = Capability
          .deployGraph(
            participates = _.id == "serviceA",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
            targets = _ => List(Target(TargetName("prod"))),
          )
          .withMatrixCollapse(MatrixCollapse.Off)
        val wf = plan(List(dockerExpanded, deploy), on, dockerGraphFixture)
        val c  = cond(wf, "deploy-serviceA-prod")
        assertTrue(
          // Without `!cancelled()` GitHub's implicit success() skips this the moment any docker job skips.
          c.contains("!cancelled()"),
          c.contains("needs.docker-serviceA.result != 'failure'"),
          // Tolerating skips must not tolerate failures, so the guard is `!= 'failure'` on each need rather than absent.
          !c.contains("== 'success'"),
        )
      },
      test("an Aggregate consumer of a narrowed Verify capability is skip-tolerant") {
        // The Aggregate-consumer-of-a-narrowed-*Publish* case is no longer generated at all: it is refused, because it
        // would run against an artifact nobody built (see AffectedDeploySpec). Verify is the scope where an Aggregate
        // consumer of something skippable is still legitimate, since it consumes no artifact.
        val pub = Capability.publish.copy(needsCapabilities = List(Capability.TestName))
        val wf  = plan(List(testExpanded, pub), on)
        val c   = cond(wf, "publish")
        assertTrue(
          c.contains("!cancelled()"),
          // Every test job it waits on, since an Aggregate consumer needs all of them.
          c.contains("needs.test-schema.result != 'failure'"),
          c.contains("needs.test-api.result != 'failure'"),
        )
      },
      test("a Once capability needing a narrowed publish is skip-tolerant") {
        val announce = Capability.once(
          CapabilityName("announce"),
          SbtCommand.unsafeTask("announce"),
          phase = Phase.Deploy,
          gate = Gate.OnReleaseTag,
          needsCapabilities = List(Capability.PublishName),
        )
        val wf = plan(List(publishExpanded, announce), on)
        val c  = cond(wf, "announce")
        assertTrue(
          c.contains("!cancelled()"),
          c.contains("needs.publish-schema.result != 'failure'"),
          c.contains("needs.publish-clientA.result != 'failure'"),
        )
      },
      test("a Layer dependent gates only L0, since later waves wait on L0's own decision") {
        // Layer consuming a narrowed *Verify*: the Publish-producer spelling of this is refused outright now, for the
        // same reason as the Aggregate one above.
        val pubLayers = Capability.publishLayers.copy(needsCapabilities = List(Capability.TestName))
        val wf        = plan(List(testExpanded, pubLayers), on)
        val later     = wf.jobs.keys.filter(id => id.startsWith("publish-L") && id != "publish-L0").toList
        assertTrue(
          cond(wf, "publish-L0").contains("!cancelled()"),
          cond(wf, "publish-L0").contains("needs.test-schema.result != 'failure'"),
          // Two more waves exist, so this is a real exclusion rather than a vacuous forall.
          later.size >= 2,
          later.forall(id => !cond(wf, id).contains("!cancelled()")),
        )
      },
      test("with the knob off, a dependent's if: gains nothing: no tolerance where there is no skip") {
        val deploy = Capability
          .deploy(
            participates = _.id == "serviceA",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
            targets = _ => List(Target(TargetName("prod"))),
          )
          .withMatrixCollapse(MatrixCollapse.Off)
        val wf = plan(List(dockerExpanded, deploy), off, dockerGraphFixture)
        val c  = cond(wf, "deploy-prod")
        assertTrue(!c.contains("!cancelled()"), !c.contains("result != 'failure'"))
      },
      test("depending on an Aggregate docker adds no tolerance, since an Aggregate job cannot be narrowed") {
        val deploy = Capability
          .deploy(
            participates = _.id == "serviceA",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
            targets = _ => List(Target(TargetName("prod"))),
          )
          .withMatrixCollapse(MatrixCollapse.Off)
        val wf = plan(List(Capability.docker, deploy), on, dockerGraphFixture)
        val c  = cond(wf, "deploy-prod")
        assertTrue(!c.contains("!cancelled()"), wf.jobs("deploy-prod").needs.contains("docker"))
      },
      test("a narrowed Publish job guards its upstream publishes, so a failed dependency still blocks it") {
        val wf = plan(List(publishExpanded), on)
        // publishGraph is DependencyOrdered, so `publish-api` waits on `publish-schema`, which can now skip.
        assertTrue(
          wf.jobs("publish-api").needs.contains("publish-schema"),
          cond(wf, "publish-api").contains("needs.publish-schema.result != 'failure'"),
          cond(wf, "publish-api").contains("!cancelled()"),
        )
      },
      test("a cross-capability need is guarded as well, so a failed gate is not let through by !cancelled()") {
        // The trap: `!cancelled()` overrides the implicit success() for *every* need, not only the skippable ones. A
        // failed `fmt` would otherwise stop blocking the publish it gates.
        val fmt =
          Capability.once(CapabilityName("fmt"), SbtCommand.unsafeTask("scalafmtCheckAll"), phase = Phase.Publish)
        val pub = publishExpanded.copy(needsCapabilities = List(fmt.name))
        val wf  = plan(List(fmt, pub), on)
        assertTrue(
          wf.jobs("publish-schema").needs.contains("fmt"),
          cond(wf, "publish-schema").contains("needs.fmt.result != 'failure'"),
        )
      },
      test("the two jobs with clauses of their own are not double-guarded") {
        // `affected` is read through its outputs and `verify-gate` through its own fail-open clause; a `result` guard on
        // either would be redundant at best and, for verify-gate, wrong (a skipped gate means "run").
        val wf = plan(List(testExpanded, publishExpanded), on.copy(skipMergedPrPush = true))
        val c  = cond(wf, "publish-schema")
        assertTrue(
          !c.contains("needs.affected.result"),
          !c.contains("needs.verify-gate.result"),
          c.contains("needs.affected.outputs.modules"),
        )
      },
    ),
    suite("the shape of the generated condition")(
      test("!cancelled() comes first, so the clause order stays readable and stable") {
        val wf = plan(List(publishExpanded), on)
        assertTrue(cond(wf, "publish-api").startsWith("!cancelled() && "))
      },
      test("the whole if: byte for byte, since this is the string a consumer diffs in their committed ci.yml") {
        val wf = plan(List(publishExpanded), on)
        assertTrue(
          cond(wf, "publish-schema") ==
            "!cancelled() && startsWith(github.ref, 'refs/tags/v') && " +
            "(contains(fromJson(needs.affected.outputs.modules), 'schema') || " +
            "contains(fromJson(needs.affected.outputs.modules), 'all'))",
          // And with an upstream that can skip, its guard is appended after the affected clause.
          cond(wf, "publish-api") ==
            "!cancelled() && startsWith(github.ref, 'refs/tags/v') && " +
            "(contains(fromJson(needs.affected.outputs.modules), 'api') || " +
            "contains(fromJson(needs.affected.outputs.modules), 'all')) && " +
            "needs.publish-schema.result != 'failure'",
        )
      },
      test("a capability condition is ANDed on without displacing the affected clauses") {
        val pub = publishExpanded.withCondition(JobCondition.repositoryIs("acme/libs"))
        val wf  = plan(List(pub), on)
        val c   = cond(wf, "publish-schema")
        assertTrue(
          c.contains("!cancelled()"),
          c.contains("needs.affected.outputs.modules"),
          c.contains("startsWith(github.ref, 'refs/tags/v')"),
          c.contains("github.repository == 'acme/libs'"),
        )
      },
      test("the plan renders, so none of these conditions is a workflow GitHub would reject") {
        // Graph deploy, because an Aggregate one needing a narrowed docker is now refused outright.
        val deploy = Capability
          .deployGraph(
            participates = _.id == "serviceA",
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
            targets = _ => List(Target(TargetName("prod"))),
          )
          .withMatrixCollapse(MatrixCollapse.Off)
        val wf = plan(List(testExpanded, dockerExpanded, deploy), on, dockerGraphFixture)
        assertTrue(zipx.workflow.Render.render(wf).isRight)
      },
    ),
  )
end AffectedPublishSpec
