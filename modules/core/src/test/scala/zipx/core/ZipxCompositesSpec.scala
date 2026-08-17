package zipx.core

import neotype.unwrap
import zipx.workflow.*
import zio.test.*

import scala.collection.immutable.ListMap

object ZipxCompositesSpec extends ZIOSpecDefault:

  private val pins = ActionPins.Defaults

  private def workflowWith(steps: List[Step]) =
    Workflow(name = "ci", on = Triggers(), jobs = ListMap("job" -> Job(steps = steps)))

  def spec = suite("ZipxComposites")(
    test("sbt-setup action.yml has composite runs and pinned nested uses, but not checkout") {
      val yaml = ZipxComposites.renderSbtSetup(pins).toOption.get
      assertTrue(
        yaml.startsWith(Render.header),
        yaml.contains("using: composite"),
        yaml.contains("zipx sbt setup"),
        // Local composites cannot checkout themselves: GHA resolves `uses: ./…` before the composite runs.
        !yaml.contains("actions/checkout"),
        !yaml.contains("uses: actions/checkout"),
        yaml.contains("actions/setup-java@") || yaml.contains(pins.setupJava.unwrap),
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
    test("artifacts omit aws-login unless includeAwsLogin is true") {
      val off   = ZipxComposites.artifacts(pins).toOption.get
      val on    = ZipxComposites.artifacts(pins, includeAwsLogin = true).toOption.get
      val again = ZipxComposites.artifacts(pins).toOption.get
      assertTrue(
        off == again,
        off.contains(ZipxComposites.SbtSetupPath),
        !off.contains(ZipxComposites.AwsLoginPath),
        on.contains(ZipxComposites.SbtSetupPath),
        on.contains(ZipxComposites.AwsLoginPath),
      )
    },
    test("usesAwsLogin is true only when a step uses the local composite") {
      val withAws = workflowWith(List(ZipxComposites.awsLoginStep()))
      val setup   = ZipxComposites.sbtSetupStep(
        PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.0.0")),
        JobId("test"),
        None,
        localCache = true,
      )
      val without = workflowWith(List(setup))
      assertTrue(ZipxComposites.usesAwsLogin(withAws), !ZipxComposites.usesAwsLogin(without))
    },
    test("leftover aws-login message names the path and generate") {
      val msg = ZipxComposites.leftoverAwsLoginMessage
      assertTrue(
        msg.contains(ZipxComposites.AwsLoginPath),
        msg.contains("unused"),
        msg.contains("zipxWorkflowGenerate"),
      )
    },
    test("sbtSetupStep points at the local composite") {
      val step = ZipxComposites.sbtSetupStep(
        PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.0.0")),
        JobId("test"),
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
