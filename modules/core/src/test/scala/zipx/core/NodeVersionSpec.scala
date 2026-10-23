package zipx.core

import neotype.unwrap
import zio.test.*

object NodeVersionSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-SNAPSHOT"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private def plan(capability: Capability) = Planner.plan(sampleGraph, List(capability), config)

  def spec = suite("NodeVersion")(
    suite("the step")(
      test("the cache composite does not install Node") {
        val asked = plan(Capability.testJoined.withNodeVersion(NodeVersion("22.11.0")))
          .jobs("test")
          .steps
          .find(_.uses.contains(ZipxComposites.SbtSetupRef))
        val rendered = ZipxComposites.renderSbtSetup(config.actions)
        val yaml     = rendered.fold(identity, identity)
        assertTrue(
          rendered.isRight,
          asked.exists(_.uses.contains(ZipxComposites.SbtSetupRef)),
          !asked.exists(_.`with`.contains("node-version")),
          !yaml.contains("actions/setup-node"),
          !yaml.contains(config.actions.setupNode.unwrap),
        )
      }
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
      test("an overridden setup-node pin stays out of the cache composite") {
        val pins = ActionPins.Defaults.withField(
          ActionPins.Field.SetupNode,
          zipx.workflow.ActionRef("actions/setup-node@abc123"),
        )
        val rendered = ZipxComposites.renderSbtSetup(pins)
        val yaml     = rendered.fold(identity, identity)
        assertTrue(
          rendered.isRight,
          !yaml.contains("actions/setup-node"),
          !yaml.contains("actions/setup-node@abc123"),
        )
      },
    ),
  )
end NodeVersionSpec
