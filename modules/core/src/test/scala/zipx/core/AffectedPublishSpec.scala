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

  /** `docker = true` on the four services, so `Capability.dockerGraph` has something to fan out over. */
  private val dockerGraphFixture = sampleGraph.mapNodes {
    case n if n.id.startsWith("service") => n.copy(docker = true)
    case n                               => n
  }

  private def cond(wf: zipx.workflow.Workflow, job: String): String = wf.jobs(job).`if`.getOrElse("")

  private def plan(caps: List[Capability], cfg: PlanConfig, graph: ModuleGraph = sampleGraph) =
    Planner.plan(graph, caps, cfg)

  private val affectedClause = "contains(fromJson(needs.affected.outputs.modules), 'schema')"

  def spec = suite("affected-gating for Publish")(
    suite("off by default, because under-publishing is loudly broken")(
      // The default matters more than usual here: every existing consumer's committed ci.yml has to stay byte-identical
      // or `zipxWorkflowCheck` fails on upgrade, for a feature they did not ask for.
      test("the default is off") {
        assertTrue(!PlanConfig().affectedPublish)
      },
      test("with it off, a Graph Publish job has no affected clause and no affected need") {
        val wf = plan(List(Capability.publishGraph), off)
        assertTrue(
          !cond(wf, "publish-schema").contains("needs.affected"),
          !wf.jobs("publish-schema").needs.contains("affected"),
          cond(wf, "publish-schema").contains("startsWith(github.ref, 'refs/tags/v')"),
        )
      },
      test("with it off, a Graph Publish alongside a Graph Verify leaves Publish alone") {
        val wf = plan(List(Capability.testGraph, Capability.publishGraph), off)
        assertTrue(
          // The affected job exists, for Verify's sake, and Publish simply does not read it.
          wf.jobs.contains("affected"),
          cond(wf, "test-schema").contains("needs.affected.outputs.modules"),
          !cond(wf, "publish-schema").contains("needs.affected"),
        )
      },
      test("turning it on changes no Verify job's if:, so the two knobs are genuinely independent") {
        val verifyOff = plan(List(Capability.testGraph, Capability.publishGraph), off)
        val verifyOn  = plan(List(Capability.testGraph, Capability.publishGraph), on)
        assertTrue(
          cond(verifyOff, "test-schema") == cond(verifyOn, "test-schema"),
          cond(verifyOff, "test-api") == cond(verifyOn, "test-api"),
          verifyOff.jobs("test-schema").needs == verifyOn.jobs("test-schema").needs,
        )
      },
      test("with AffectedMode.Always, affectedPublish alone gates nothing") {
        // `affected` is the mode; `affectedPublish` only says which phases the mode reaches. Without the mode there is
        // no `affected` job to read, so this combination has to be inert rather than half-wired.
        val wf = plan(List(Capability.publishGraph), on.copy(affected = AffectedMode.Always))
        assertTrue(
          !wf.jobs.contains("affected"),
          !cond(wf, "publish-schema").contains("needs.affected"),
          !cond(wf, "publish-schema").contains("!cancelled()"),
        )
      },
    ),
    suite("on, a Graph Publish job narrows to the affected modules")(
      test("the job reads the affected output, with the 'all' sentinel as the escape hatch") {
        val wf = plan(List(Capability.publishGraph), on)
        assertTrue(
          cond(wf, "publish-schema").contains(affectedClause),
          cond(wf, "publish-schema").contains("'all'"),
          wf.jobs("publish-schema").needs.contains("affected"),
        )
      },
      test("the release gate survives the narrowing: both clauses are present") {
        // Losing the tag gate here would publish snapshots off every PR, which is worse than publishing too much.
        val wf = plan(List(Capability.publishGraph), on)
        assertTrue(
          cond(wf, "publish-schema").contains("startsWith(github.ref, 'refs/tags/v')"),
          cond(wf, "publish-schema").contains(affectedClause),
        )
      },
      test("every participating module gets its own clause naming its own id") {
        val wf = plan(List(Capability.publishGraph), on)
        assertTrue(
          cond(wf, "publish-api").contains("'api')"),
          cond(wf, "publish-clientA").contains("'clientA')"),
          !cond(wf, "publish-api").contains("'clientA')"),
        )
      },
      test("a Graph docker Publish narrows too, which is the case the issue is about") {
        val wf = plan(List(Capability.dockerGraph), on, dockerGraphFixture)
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
      test("Deploy is never narrowed, whatever the knob says") {
        // ROADMAP M6: a deploy is about a destination's desired state, not about what a diff touched. Narrowing it would
        // leave an environment running an image the tag no longer describes.
        val deploy = Capability.deployGraph(
          participates = _.id == "serviceA",
          command = n => SbtCommand.module(n, SbtCommand("deploy")),
          targets = _ => List(Target(TargetName("prod"))),
          needsCapabilities = Nil,
        )
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
        val wf     = plan(List(Capability.publishGraph), on)
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
        val wf = plan(List(Capability.publishGraph), on)
        assertTrue(!cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"))
      },
      test("with only Verify gated, the affected job keeps its tag exclusion") {
        val wf = plan(List(Capability.testGraph), off)
        assertTrue(cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"))
      },
      test("Verify and Publish share one affected job, and it runs on tags") {
        val wf = plan(List(Capability.testGraph, Capability.publishGraph), on)
        assertTrue(
          wf.jobs.keys.count(_ == "affected") == 1,
          !cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"),
          // Verify's own jobs still carry the exclusion; it belongs to them, not to the setup job.
          cond(wf, "test-schema").contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      },
      test("the merged-PR skip still applies to the affected job, only the tag exclusion is dropped") {
        val wf = plan(List(Capability.testGraph, Capability.publishGraph), on.copy(skipMergedPrPush = true))
        assertTrue(
          wf.jobs("affected").needs.contains("verify-gate"),
          // Fail-open: a gate that did not succeed (on a tag it is skipped outright) lets this run anyway.
          cond(wf, "affected").contains("needs.verify-gate.result != 'success'"),
          !cond(wf, "affected").contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      },
      test("fail-open is unchanged: an unusable diff publishes everything") {
        // The planner side of this is the `|| 'all'` clause above; this is the other half, and it is what makes a broken
        // base ref cost CI minutes rather than a missing release artifact.
        assertTrue(
          Affected.outputModules(sampleGraph, None) == Affected.AllSentinel,
          cond(plan(List(Capability.publishGraph), on), "publish-schema").contains("'all')"),
        )
      },
    ),
    suite("a dependent tolerates a skipped need, but not a failed one")(
      test("a Graph deploy needing a narrowed docker becomes skip-tolerant") {
        val deploy = Capability.deployGraph(
          participates = _.id == "serviceA",
          command = n => SbtCommand.module(n, SbtCommand("deploy")),
          targets = _ => List(Target(TargetName("prod"))),
        )
        val wf = plan(List(Capability.dockerGraph, deploy), on, dockerGraphFixture)
        val c  = cond(wf, "deploy-serviceA-prod")
        assertTrue(
          // Without `!cancelled()` GitHub's implicit success() skips this the moment any docker job skips.
          c.contains("!cancelled()"),
          c.contains("needs.docker-serviceA.result != 'failure'"),
          // Tolerating skips must not tolerate failures, so the guard is `!= 'failure'` on each need rather than absent.
          !c.contains("== 'success'"),
        )
      },
      test("an Aggregate deploy needing a narrowed Graph docker is skip-tolerant too") {
        val deploy = Capability.deploy(
          participates = _.id == "serviceA",
          command = n => SbtCommand.module(n, SbtCommand("deploy")),
          targets = _ => List(Target(TargetName("prod"))),
        )
        val wf = plan(List(Capability.dockerGraph, deploy), on, dockerGraphFixture)
        val c  = cond(wf, "deploy-prod")
        assertTrue(
          c.contains("!cancelled()"),
          // Every docker job it waits on, since an Aggregate deploy needs all of them.
          c.contains("needs.docker-serviceA.result != 'failure'"),
          c.contains("needs.docker-serviceB.result != 'failure'"),
          c.contains("startsWith(github.ref, 'refs/tags/v')"),
        )
      },
      test("a Once capability needing a narrowed publish is skip-tolerant") {
        val announce = Capability.once(
          CapabilityName("announce"),
          SbtCommand("announce"),
          phase = Phase.Deploy,
          gate = Gate.OnReleaseTag,
          needsCapabilities = List(Capability.PublishName),
        )
        val wf = plan(List(Capability.publishGraph, announce), on)
        val c  = cond(wf, "announce")
        assertTrue(
          c.contains("!cancelled()"),
          c.contains("needs.publish-schema.result != 'failure'"),
          c.contains("needs.publish-clientA.result != 'failure'"),
        )
      },
      test("a Layer dependent gates only L0, since later waves wait on L0's own decision") {
        val deployLayers = Capability
          .deploy(
            participates = _.publishes,
            command = n => SbtCommand.module(n, SbtCommand("deploy")),
            targets = _ => Nil,
          )
          .copy(scope = CapabilityScope.Layer)
        val wf    = plan(List(Capability.dockerGraph, deployLayers), on, dockerGraphFixture)
        val later = wf.jobs.keys.filter(id => id.startsWith("deploy-L") && id != "deploy-L0").toList
        assertTrue(
          cond(wf, "deploy-L0").contains("!cancelled()"),
          cond(wf, "deploy-L0").contains("needs.docker-serviceA.result != 'failure'"),
          // Two more waves exist, so this is a real exclusion rather than a vacuous forall.
          later.size >= 2,
          later.forall(id => !cond(wf, id).contains("!cancelled()")),
        )
      },
      test("with the knob off, a dependent's if: gains nothing: no tolerance where there is no skip") {
        val deploy = Capability.deploy(
          participates = _.id == "serviceA",
          command = n => SbtCommand.module(n, SbtCommand("deploy")),
          targets = _ => List(Target(TargetName("prod"))),
        )
        val wf = plan(List(Capability.dockerGraph, deploy), off, dockerGraphFixture)
        val c  = cond(wf, "deploy-prod")
        assertTrue(!c.contains("!cancelled()"), !c.contains("result != 'failure'"))
      },
      test("depending on an Aggregate docker adds no tolerance, since an Aggregate job cannot be narrowed") {
        val deploy = Capability.deploy(
          participates = _.id == "serviceA",
          command = n => SbtCommand.module(n, SbtCommand("deploy")),
          targets = _ => List(Target(TargetName("prod"))),
        )
        val wf = plan(List(Capability.docker, deploy), on, dockerGraphFixture)
        val c  = cond(wf, "deploy-prod")
        assertTrue(!c.contains("!cancelled()"), wf.jobs("deploy-prod").needs.contains("docker"))
      },
      test("a narrowed Publish job guards its upstream publishes, so a failed dependency still blocks it") {
        val wf = plan(List(Capability.publishGraph), on)
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
        val fmt = Capability.once(CapabilityName("fmt"), SbtCommand("scalafmtCheckAll"), phase = Phase.Publish)
        val pub = Capability.publishGraph.copy(needsCapabilities = List(fmt.name))
        val wf  = plan(List(fmt, pub), on)
        assertTrue(
          wf.jobs("publish-schema").needs.contains("fmt"),
          cond(wf, "publish-schema").contains("needs.fmt.result != 'failure'"),
        )
      },
      test("the two jobs with clauses of their own are not double-guarded") {
        // `affected` is read through its outputs and `verify-gate` through its own fail-open clause; a `result` guard on
        // either would be redundant at best and, for verify-gate, wrong (a skipped gate means "run").
        val wf = plan(List(Capability.testGraph, Capability.publishGraph), on.copy(skipMergedPrPush = true))
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
        val wf = plan(List(Capability.publishGraph), on)
        assertTrue(cond(wf, "publish-api").startsWith("!cancelled() && "))
      },
      test("the whole if: byte for byte, since this is the string a consumer diffs in their committed ci.yml") {
        val wf = plan(List(Capability.publishGraph), on)
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
        val pub = Capability.publishGraph.withCondition(JobCondition.repositoryIs("acme/libs"))
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
        val deploy = Capability.deploy(
          participates = _.id == "serviceA",
          command = n => SbtCommand.module(n, SbtCommand("deploy")),
          targets = _ => List(Target(TargetName("prod"))),
        )
        val wf = plan(List(Capability.testGraph, Capability.dockerGraph, deploy), on, dockerGraphFixture)
        assertTrue(zipx.workflow.Render.render(wf).isRight)
      },
    ),
  )
end AffectedPublishSpec
