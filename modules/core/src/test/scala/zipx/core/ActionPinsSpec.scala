package zipx.core

import zio.test.*

object ActionPinsSpec extends ZIOSpecDefault:

  private val config = PlanConfig(
    workflowName = "CI",
    cacheEpoch = CacheEpoch.Fixed("1.0.0"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  def spec = suite("ActionPins")(
    test("defaults are pinned to commit SHAs, not mutable tags or branches") {
      val p = ActionPins.Defaults

      def isShaPinned(ref: String) = ref.matches("^[^@]+@[0-9A-Fa-f]{40}$")

      assertTrue(
        isShaPinned(p.checkout),
        isShaPinned(p.setupJava),
        isShaPinned(p.setupSbt),
        isShaPinned(p.cache),
        isShaPinned(p.uploadArtifact),
        isShaPinned(p.downloadArtifact),
        isShaPinned(p.scalaSteward),
      ) &&
      assertTrue(
        p.versions.nonEmpty,
        p.versions.values.exists(_.startsWith("v")),
      )
    },
    test("planner emits the configured pins on every job") {
      val custom = ActionPins(
        checkout = "actions/checkout@deadbeef",
        setupJava = "actions/setup-java@cafebabe",
        setupSbt = "sbt/setup-sbt@feedface",
        cache = "actions/cache@00ff00ff",
      )
      val wf  = Planner.plan(Fixtures.sampleGraph, List(Capability.testGraph), config.copy(actions = custom))
      val job = wf.jobs("test-core")
      assertTrue(
        job.steps.exists(_.uses.contains("actions/checkout@deadbeef")),
        job.steps.exists(_.uses.contains("sbt/setup-sbt@feedface")),
        job.steps.exists(_.uses.contains("actions/setup-java@cafebabe")),
        job.steps.exists(_.uses.contains("actions/cache@00ff00ff")),
        job.steps.find(_.uses.exists(_.contains("checkout"))).exists(_.`with`.get("fetch-depth").contains("0")),
      )
    },
    test("affected setup job also uses the configured checkout and setup-sbt pins") {
      val custom = ActionPins.Defaults.copy(checkout = "actions/checkout@aabbccdd", setupSbt = "sbt/setup-sbt@11223344")
      val wf     = Planner.plan(
        Fixtures.sampleGraph,
        List(Capability.testGraph),
        config.copy(affected = AffectedMode.AffectedOnPR, actions = custom),
      )
      val steps = wf.jobs("affected").steps
      assertTrue(
        steps.exists(_.uses.contains("actions/checkout@aabbccdd")),
        steps.exists(_.uses.contains("sbt/setup-sbt@11223344")),
      )
    },
  )
end ActionPinsSpec
