package zipx.core

import zipx.workflow.{Expr, ExprLiteral}
import zio.test.*

/** Publish waits on every Verify job through one roll-up, not on `test` alone.
  *
  * The heddle shape this closes: `test`, `test-js`, `test-native`, and `fmt` are siblings, and `snapshots` used to need
  * only `test`.
  */
object VerifyRollupSpec extends ZIOSpecDefault:
  import Fixtures.sampleGraph

  private val config = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.4.2-SNAPSHOT"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val testJs =
    Capability.once(CapabilityName("test-js"), SbtCommand.unsafeTask("testJS"), phase = Phase.Verify)

  private def plan(caps: List[Capability], cfg: PlanConfig = config) =
    Planner.plan(sampleGraph, caps, cfg)

  private inline def failed(inline id: String): Expr =
    Expr.group(
      (Expr.jobResult(id) === Expr.quoted("failure")) || (Expr.jobResult(id) === Expr.quoted("cancelled"))
    )

  private val rehydrate = config.copy(skipMergedPrPush = true, cacheRehydrateOnMerge = true)

  def spec = suite("Verify roll-up")(
    test("snapshots needs the roll-up, and the roll-up needs every Verify job") {
      val wf        = plan(List(Capability.test, testJs, Capability.snapshots()))
      val rollup    = wf.jobs("verify")
      val snapshots = wf.jobs("snapshots")
      val keys      = wf.jobs.keys.toList
      assertTrue(
        rollup.needs == List("test", "test-js"),
        snapshots.needs == List("verify"),
        !snapshots.needs.contains("test"),
        !snapshots.needs.contains("test-js"),
        keys.indexOf("test") < keys.indexOf("verify"),
        keys.indexOf("test-js") < keys.indexOf("verify"),
        keys.indexOf("verify") < keys.indexOf("snapshots"),
      )
    },
    test("the roll-up runs unless the workflow was cancelled, and fails on failure or cancelled") {
      val wf   = plan(List(Capability.test, testJs, Capability.snapshots()))
      val job  = wf.jobs("verify")
      val step = job.steps.headOption
      val cond = (failed("test") || failed("test-js")).unwrapped
      assertTrue(
        job.`if`.contains("!cancelled()"),
        job.steps.size == 1,
        step.flatMap(_.`if`).contains(cond),
        step.flatMap(_.`if`).forall(!_.contains("skipped")),
        step.flatMap(_.run).exists(_.contains("exit 1")),
        step.exists(_.uses.isEmpty),
        !job.steps.exists(_.uses.exists(_.toString.contains("checkout"))),
        !job.steps.exists(_.run.exists(_.contains("sbt"))),
      )
    },
    test("a merged-PR push still publishes from cache-rehydrate") {
      val wf        = plan(List(Capability.test, testJs, Capability.snapshots()), rehydrate)
      val snapshots = wf.jobs("snapshots")
      val cond      = snapshots.`if`.getOrElse("")
      assertTrue(
        wf.jobs("verify").`if`.contains("!cancelled()"),
        !wf.jobs("verify").needs.contains("verify-gate"),
        snapshots.needs == List("cache-rehydrate", "verify"),
        cond.contains("needs.cache-rehydrate.result != 'failure'"),
        cond.contains("needs.verify.result != 'failure'"),
        !cond.contains("needs.test.result"),
      )
    },
    test("pullRequestSnapshots gets the same edge") {
      val cap = Capability.pullRequestSnapshots(ExprLiteral("snapshots"))
      val job = plan(List(Capability.test, cap)).jobs("snapshots-pr")
      assertTrue(job.needs.contains("verify"), !job.needs.contains("test"))
    },
    test("an OnReleaseTag publish job gains no edge") {
      val wf  = plan(List(Capability.test, Capability.publish))
      val job = wf.jobs("publish")
      assertTrue(!job.needs.contains("verify"), !job.`if`.exists(_.contains("needs.verify")))
    },
    test("a Deploy job does not wait on the roll-up, and is not part of it") {
      val docs = Capability.steps(
        name = CapabilityName("docs"),
        steps = _ => Nil,
        phase = Phase.Deploy,
        gate = Gate.Always,
      )
      val wf = plan(List(Capability.test, docs))
      assertTrue(
        wf.jobs("verify").needs == List("test"),
        !wf.jobs("docs").needs.contains("verify"),
      )
    },
    test("an action-only publish waits on the roll-up and not on cache-rehydrate") {
      val announce = Capability.steps(
        name = CapabilityName("announce"),
        steps = _ => List(zipx.workflow.Step(name = Some("say"), run = Some("echo hi"))),
        phase = Phase.Publish,
        gate = Gate.Always,
      )
      val job = plan(List(Capability.test, announce), rehydrate).jobs("announce")
      assertTrue(job.needs.contains("verify"), !job.needs.contains("cache-rehydrate"))
    },
    test("a reusable-workflow publish does not wait on cache-rehydrate") {
      val called = Capability
        .once(CapabilityName("pages"), SbtCommand.unsafeTask("unused"), phase = Phase.Publish, gate = Gate.Always)
        .copy(workflowCall =
          Some(WorkflowCall(uses = zipx.workflow.ActionRef("acme/workflows/.github/workflows/pages.yml@v1")))
        )
      val job = plan(List(Capability.test, called), rehydrate).jobs("pages")
      assertTrue(job.needs.contains("verify"), !job.needs.contains("cache-rehydrate"))
    },
    test("expanded Graph Verify ids are the roll-up needs, and a non-participant is absent") {
      val narrowed = Capability.testGraph
        .withMatrixCollapse(MatrixCollapse.Off)
        .copy(participates = n => n.id == "schema" || n.id == "api")
      val wf      = plan(List(narrowed, Capability.snapshots()))
      val emitted = Planner.allJobIds(narrowed, sampleGraph, config).map(id => id: String).sorted
      assertTrue(
        emitted == List("test-api", "test-schema"),
        wf.jobs("verify").needs == emitted,
        !wf.jobs("verify").needs.exists(_.contains("service")),
      )
    },
    test("a Layer publish needs the roll-up on the first wave only") {
      val layers  = Capability.publishLayers.copy(gate = Gate.Always)
      val wf      = plan(List(Capability.test, layers), rehydrate)
      val later   = wf.jobs.keys.filter(id => id.startsWith("publish-L") && id != "publish-L0").toList
      val laterOk = later.forall(id => !wf.jobs(id).needs.contains("verify"))
      assertTrue(
        wf.jobs("publish-L0").needs.contains("verify"),
        wf.jobs("publish-L0").needs.contains("cache-rehydrate"),
        later.nonEmpty,
        laterOk,
      )
    },
    test("an Aggregate publish with Gate.Always needs the roll-up") {
      val publish = Capability.publish.copy(gate = Gate.Always)
      val job     = plan(List(Capability.test, publish), rehydrate).jobs("publish")
      assertTrue(job.needs.contains("verify"), job.needs.contains("cache-rehydrate"))
    },
    test("a capability named verify is refused") {
      val cap     = Capability.once(CapabilityName("verify"), SbtCommand.unsafeTask("say"))
      val message = scala.util.Try(plan(List(cap))).toEither.left.toOption.map(_.getMessage).getOrElse("")
      assertTrue(message.contains("capability name 'verify'"))
    },
    test("no Verify jobs means no roll-up") {
      val wf = plan(List(Capability.publish))
      assertTrue(!wf.jobs.contains("verify"), !wf.jobs("publish").needs.contains("verify"))
    },
  )
end VerifyRollupSpec
