package zipx.core

import neotype.unwrap
import zipx.workflow.Render
import zio.test.*

object ZipxCompositesSpec extends ZIOSpecDefault:

  private val pins = ActionPins.Defaults

  def spec = suite("ZipxComposites")(
    test("sbt-setup action.yml has composite runs and pinned nested uses") {
      val yaml = ZipxComposites.renderSbtSetup(pins).toOption.get
      assertTrue(
        yaml.startsWith(Render.header),
        yaml.contains("using: composite"),
        yaml.contains("zipx sbt setup"),
        yaml.contains("actions/checkout@") || yaml.contains(pins.checkout.unwrap),
        yaml.contains("shell: bash"),
        yaml.contains("java-version:"),
      )
    },
    test("aws-login action.yml parameterizes env key inputs") {
      val yaml = ZipxComposites.renderAwsLogin(pins).toOption.get
      assertTrue(
        yaml.contains("using: composite"),
        yaml.contains("role-env:"),
        yaml.contains("env[inputs.role-env]"),
        yaml.contains("aws-actions/"),
      )
    },
    test("artifacts map is deterministic") {
      val once  = ZipxComposites.artifacts(pins).toOption.get
      val twice = ZipxComposites.artifacts(pins).toOption.get
      assertTrue(once == twice, once.contains(ZipxComposites.SbtSetupPath))
    },
    test("sbtSetupStep points at the local composite") {
      val step = ZipxComposites.sbtSetupStep(
        PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.0.0")),
        zipx.workflow.JobId("test"),
        None,
        localCache = true,
      )
      assertTrue(
        step.uses.contains(ZipxComposites.SbtSetupRef),
        step.`with`.get("cache-epoch").contains("1.0.0"),
        step.`with`.get("local-cache").contains("true"),
      )
    },
  )
end ZipxCompositesSpec
