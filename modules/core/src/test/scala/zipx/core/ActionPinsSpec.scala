package zipx.core

import zio.test.*

object ActionPinsSpec extends ZIOSpecDefault:

  private val config = PlanConfig(workflowName = "CI", cacheEpoch = "1.0.0", affected = AffectedMode.Always)

  def spec = suite("ActionPins")(
    test("defaults are full commit SHAs, not mutable tags") {
      val p = ActionPins.Defaults
      assertTrue(
        p.checkout.contains("@9c091bb"),
        !p.checkout.endsWith("@v4"),
        !p.checkout.endsWith("@v7"),
        p.setupJava.contains("@03ad4de"),
        p.setupSbt.contains("@9d56cf1"),
        p.cache.contains("@55cc834"),
        p.uploadArtifact.contains("@043fb46"),
        p.downloadArtifact.contains("@3e5f45b"),
      )
    },
    test("planner emits the configured pins on every job") {
      val custom = ActionPins(
        checkout = "actions/checkout@deadbeef",
        setupJava = "actions/setup-java@cafebabe",
        setupSbt = "sbt/setup-sbt@feedface",
        cache = "actions/cache@00ff00ff",
      )
      val wf  = Planner.plan(Fixtures.sampleGraph, List(Capability.test), config.copy(actions = custom))
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
        List(Capability.test),
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
