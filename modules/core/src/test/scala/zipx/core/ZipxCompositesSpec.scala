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
        cacheMode = LocalCacheMode.Save,
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
        cacheMode = LocalCacheMode.Save,
      )
      assertTrue(
        step.uses.contains(ZipxComposites.SbtSetupRef),
        step.`with`.get("cache-epoch").contains("1.0.0"),
        step.`with`.get("cache-mode").contains("save"),
      )
    },
    test("only a save step writes the cache; restore steps use actions/cache/restore at the same pin") {
      val steps                    = ZipxComposites.sbtSetup(pins).steps
      def cacheSteps(mode: String) = steps.filter(_.`if`.exists(_.contains(s"inputs.cache-mode == '$mode'")))
      assertTrue(
        cacheSteps("save").nonEmpty,
        cacheSteps("save").forall(_.uses.contains(pins.cache)),
        cacheSteps("restore").nonEmpty,
        cacheSteps("restore").forall(_.uses.contains(pins.cacheRestore)),
        pins.cacheRestore.unwrap == pins.cache.unwrap.replace("actions/cache@", "actions/cache/restore@"),
        !steps.exists(_.`if`.exists(_.contains("inputs.cache-mode == 'off'"))),
      )
    },
    test("every cache key and restore key but the OS+JDK fallback carries the build role") {
      val prefix = "${{ inputs.runner-os }}-jdk${{ inputs.java-version }}-sbt-"
      val keys   = ZipxComposites
        .sbtSetup(pins)
        .steps
        .filter(_.`with`.contains("key"))
        .flatMap(s => s.`with`("key") :: s.`with`("restore-keys").linesIterator.toList)
      assertTrue(
        keys.nonEmpty,
        keys.filterNot(_ == prefix).forall(_.contains("-build-")),
        keys.contains(prefix),
      )
    },
  )
end ZipxCompositesSpec
