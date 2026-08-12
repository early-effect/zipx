package zipx.core

import neotype.unwrap
import zio.test.*
import zipx.workflow.ActionRef

object ActionPinsSpec extends ZIOSpecDefault:

  private val config = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.0.0"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  def spec = suite("ActionPins")(
    test("defaults are pinned to commit SHAs, not mutable tags or branches") {
      // Stricter than [[ActionRef]] on purpose: `actions/checkout@v4` is a legal `uses:` value and would pass the
      // field's own type, but a *default* zipx ships must be a SHA. Over every `Field`, so a new pin cannot escape it.
      def isShaPinned(ref: ActionRef) = ref.unwrap.matches("^[^@]+@[0-9A-Fa-f]{40}$")

      val p = ActionPins.Defaults
      assertTrue(
        ActionPins.Field.values.forall(f => isShaPinned(p.field(f))),
        p.versions.nonEmpty,
        p.versions.values.exists(_.startsWith("v")),
      )
    },
    test("planner emits the configured pins on every job") {
      val custom = ActionPins(
        checkout = ActionRef("actions/checkout@deadbeef"),
        setupJava = ActionRef("actions/setup-java@cafebabe"),
        setupSbt = ActionRef("sbt/setup-sbt@feedface"),
        cache = ActionRef("actions/cache@00ff00ff"),
      )
      val wf    = Planner.plan(Fixtures.sampleGraph, List(Capability.testGraph), config.copy(actions = custom))
      val job   = wf.jobs("test-core")
      val setup = ZipxComposites.sbtSetup(custom, CacheEpoch.Fixed("1.0.0"))
      assertTrue(
        job.steps.exists(_.uses.contains(ZipxComposites.SbtSetupRef)),
        setup.steps.exists(_.uses.contains(ActionRef("actions/checkout@deadbeef"))),
        setup.steps.exists(_.uses.contains(ActionRef("sbt/setup-sbt@feedface"))),
        setup.steps.exists(_.uses.contains(ActionRef("actions/setup-java@cafebabe"))),
        setup.steps.exists(_.uses.contains(ActionRef("actions/cache@00ff00ff"))),
      )
    },
    test("affected setup job also uses the configured checkout and setup-sbt pins") {
      val custom = ActionPins.Defaults.copy(
        checkout = ActionRef("actions/checkout@aabbccdd"),
        setupSbt = ActionRef("sbt/setup-sbt@11223344"),
      )
      val wf = Planner.plan(
        Fixtures.sampleGraph,
        List(Capability.testGraph),
        config.copy(affected = AffectedMode.AffectedOnPR, actions = custom),
      )
      val steps = wf.jobs("affected").steps
      val setup = ZipxComposites.sbtSetup(custom, CacheEpoch.Fixed("1.0.0"))
      assertTrue(
        steps.exists(_.uses.contains(ZipxComposites.SbtSetupRef)),
        setup.steps.exists(_.uses.contains(ActionRef("actions/checkout@aabbccdd"))),
        setup.steps.exists(_.uses.contains(ActionRef("sbt/setup-sbt@11223344"))),
      )
    },
  )
end ActionPinsSpec
