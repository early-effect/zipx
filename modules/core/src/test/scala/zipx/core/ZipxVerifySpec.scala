package zipx.core

import zio.test.*

object ZipxVerifySpec extends ZIOSpecDefault:
  import Fixtures.sampleGraph

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.0.0"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  def spec = suite("ZipxVerify")(
    test("Strict is all On") {
      assertTrue(
        ZipxVerify.Strict.fmt == VerifyOpt.On,
        ZipxVerify.Strict.workflowCheck == VerifyOpt.On,
        ZipxVerify.Strict.advisories == VerifyOpt.On,
      )
    },
    test("blank Skip reason is refused with a copy snippet") {
      ZipxVerify.validate(ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("  "))) match
        case Left(err) =>
          assertTrue(
            err.contains("Skip reason must be non-empty"),
            err.contains("zipxVerify := ZipxVerify.Strict.copy"),
          )
        case Right(_) => assertTrue(false)
    },
    test("blank leftover Warn reason is refused with a copy snippet") {
      LeftoverOpt.validate(LeftoverOpt.Warn("")) match
        case Left(err) =>
          assertTrue(err.contains("Warn reason must be non-empty"), err.contains("zipxLeftoverSteward"))
        case Right(_) => assertTrue(false)
    },
    test("leftover message names files, dep update, and Warn snippet") {
      val msg = LeftoverOpt.leftoverMessage(List(LeftoverOpt.WorkflowPath), None)
      assertTrue(
        msg.contains(LeftoverOpt.WorkflowPath),
        msg.contains("zipxDepUpdate yes"),
        msg.contains("LeftoverOpt.Warn"),
      )
    },
    test("skipOnce prints the gate and reason") {
      val cap  = Capability.skipOnce(Capability.FmtName, "fmt", "hotfix: scalafmt 3.x")
      val step = cap.extraSteps(StepContext(ModuleNode(id = ModuleId("root")), None, matrixed = false)).head
      assertTrue(
        cap.name == Capability.FmtName,
        step.run.exists(_.contains("zipx: skipping fmt: hotfix: scalafmt 3.x")),
      )
    },
    test("Skip still emits the job; blank reason is refused with a copy snippet") {
      val cap = Capability.skipOnce(Capability.FmtName, "fmt", "hotfix: GHSA-xxxx in checkout")
      val wf  = Planner.plan(sampleGraph, List(Capability.test, cap), config)
      assertTrue(
        wf.jobs.contains("fmt"),
        wf.jobs("fmt").needs.isEmpty,
        wf.jobs("test").needs.forall(_ != "fmt"),
        wf.jobs("fmt").steps.exists(_.run.exists(_.contains("zipx: skipping fmt: hotfix: GHSA-xxxx in checkout"))),
      )
    },
    test("parallel Verify Once jobs have empty needs versus test") {
      val fmt     = Capability.once(Capability.FmtName, SbtCommand.unsafeCommand("scalafmtCheckAll"))
      val wfCheck =
        Capability.once(Capability.WorkflowCheckName, SbtCommand.unsafeTask("zipxWorkflowCheck"))
      val adv = Capability.once(Capability.AdvisoriesName, SbtCommand.unsafeTask("zipxAdvisoryCheck"))
      val wf  = Planner.plan(sampleGraph, List(Capability.test, fmt, wfCheck, adv), config)
      assertTrue(
        wf.jobs.contains("fmt"),
        wf.jobs.contains("workflow-check"),
        wf.jobs.contains("advisories"),
        wf.jobs.contains("test"),
        wf.jobs("fmt").needs.isEmpty,
        wf.jobs("workflow-check").needs.isEmpty,
        wf.jobs("advisories").needs.isEmpty,
        !wf.jobs("test").needs.contains("fmt"),
        !wf.jobs("test").needs.contains("workflow-check"),
        !wf.jobs("test").needs.contains("advisories"),
      )
    },
  )
end ZipxVerifySpec
