package zipx.core

import neotype.unwrap
import zio.test.*

/** `Capability.withNodeVersion` and the `setupNode` pin (#73). */
object NodeVersionSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private def plan(capability: Capability) = Planner.plan(sampleGraph, List(capability), config)

  def spec = suite("NodeVersion")(
    suite("the step")(
      test("sbt-setup composite receives node-version so a jsEnv sees the pinned Node") {
        val step = plan(Capability.testJoined.withNodeVersion(NodeVersion("22"))).jobs("test").steps.head
        assertTrue(
          step.uses.contains(ZipxComposites.SbtSetupRef),
          step.`with`.get("node-version").contains("22"),
        )
      },
      test("carries node-version into the composite inputs") {
        val step = plan(Capability.testJoined.withNodeVersion(NodeVersion("22.11.0")))
          .jobs("test")
          .steps
          .find(_.uses.contains(ZipxComposites.SbtSetupRef))
        assertTrue(
          step.exists(_.`with`.get("node-version").contains("22.11.0")),
          ZipxComposites.renderSbtSetup(config.actions).toOption.get.contains(config.actions.setupNode.unwrap),
        )
      },
      test("absent by default, so no existing workflow gains a node-version input") {
        val step = plan(Capability.testJoined).jobs("test").steps.head
        assertTrue(step.`with`.get("node-version").contains(""))
      },
      test("only the capability that asked for it, not the affected or cache-rehydrate jobs") {
        val cfg = config.copy(affected = AffectedMode.AffectedOnPR, cacheRehydrateOnMerge = true)
        val wf  = Planner.plan(
          sampleGraph,
          List(Capability.testGraph.withNodeVersion(NodeVersion("22")).withMatrixCollapse(MatrixCollapse.Off)),
          cfg,
        )
        def nodeVer(id: String) =
          wf.jobs
            .get(id)
            .flatMap(_.steps.find(_.uses.contains(ZipxComposites.SbtSetupRef)))
            .flatMap(_.`with`.get("node-version"))
        assertTrue(
          nodeVer("test-core").contains("22"),
          !nodeVer("affected").contains("22"),
          !nodeVer("cache-rehydrate").contains("22"),
        )
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
      test("an overridden pin reaches the generated composite") {
        val pins = ActionPins.Defaults.withField(
          ActionPins.Field.SetupNode,
          zipx.workflow.ActionRef("actions/setup-node@abc123"),
        )
        val yaml = ZipxComposites.renderSbtSetup(pins).toOption.get
        assertTrue(yaml.contains("actions/setup-node@abc123"))
      },
    ),
  )
end NodeVersionSpec
