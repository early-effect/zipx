package zipx.core

import neotype.unwrap
import zio.test.*
import zipx.workflow.Render

/** `Capability.withNodeVersion` and the `setupNode` pin (#73). */
object NodeVersionSpec extends ZIOSpecDefault:
  import Fixtures.*
  import Rendered.yaml

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private def plan(capability: Capability) = Planner.plan(sampleGraph, List(capability), config)

  def spec = suite("NodeVersion")(
    suite("the step")(
      test("runs after sbt setup, so a jsEnv sees the pinned Node") {
        val steps = plan(Capability.testJoined.withNodeVersion(NodeVersion("22"))).jobs("test").steps
        val uses  = steps.flatMap(_.uses).map(_.unwrap)
        assertTrue(
          uses.indexWhere(_.startsWith("actions/setup-node")) ==
            uses.indexWhere(_.startsWith("sbt/setup-sbt")) + 1
        )
      },
      test("carries node-version and the pinned SHA") {
        val step = plan(Capability.testJoined.withNodeVersion(NodeVersion("22.11.0")))
          .jobs("test")
          .steps
          .find(_.uses.exists(_.unwrap.startsWith("actions/setup-node")))
        assertTrue(
          step.exists(_.`with`.get("node-version").contains("22.11.0")),
          step.exists(_.uses.contains(config.actions.setupNode)),
          step.flatMap(_.name).contains("Setup Node 22.11.0"),
        )
      },
      test("absent by default, so no existing workflow gains a step") {
        val out = Render.render(plan(Capability.testGraph)).yaml
        assertTrue(!out.contains("setup-node"))
      },
      test("only the capability that asked for it, not the affected or cache-rehydrate jobs") {
        val cfg = config.copy(affected = AffectedMode.AffectedOnPR, cacheRehydrateOnMerge = true)
        val wf  = Planner.plan(sampleGraph, List(Capability.testGraph.withNodeVersion(NodeVersion("22"))), cfg)
        def usesNode(id: String) =
          wf.jobs.get(id).exists(_.steps.exists(_.uses.exists(_.unwrap.startsWith("actions/setup-node"))))
        assertTrue(usesNode("test-core"), !usesNode("affected"), !usesNode("cache-rehydrate"))
      },
    ),
    suite("the newtype")(
      test("accepts the forms setup-node takes") {
        assertTrue(
          NodeVersion.make("22").isRight,
          NodeVersion.make("22.11.0").isRight,
          NodeVersion.make("latest").isRight,
          NodeVersion.make("lts/jod").isRight,
          NodeVersion.make("lts/*").isRight,
        )
      },
      test("rejects what would break the YAML or mean nothing") {
        assertTrue(
          NodeVersion.make("").isLeft,
          NodeVersion.make("22 && rm -rf /").isLeft,
          NodeVersion.make("22\n- run: evil").isLeft,
        )
      },
    ),
    suite("the pin")(
      test("is a typed Field, so the pin file round-trips it") {
        val pins = ActionPins.Defaults
        assertTrue(
          ActionPins.Field.values.exists(_.key == "setupNode"),
          ActionPins.Field.SetupNode.prefix == "actions/setup-node",
          pins.setupNode.unwrap.startsWith("actions/setup-node@"),
          pins.version(ActionPins.Field.SetupNode).isDefined,
        )
      },
      test("an overridden pin reaches the step") {
        val pins = ActionPins.Defaults.withField(
          ActionPins.Field.SetupNode,
          zipx.workflow.ActionRef("actions/setup-node@abc123"),
        )
        val out = Render
          .render(
            Planner.plan(
              sampleGraph,
              List(Capability.testJoined.withNodeVersion(NodeVersion("22"))),
              config.copy(actions = pins),
            )
          )
          .yaml
        assertTrue(out.contains("actions/setup-node@abc123"))
      },
    ),
  )
end NodeVersionSpec
