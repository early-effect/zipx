package zipx.central

import zio.test.*
import zipx.core.*

object ZipxCentralSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val stepContext = StepContext(ModuleNode(id = ModuleId("schema")), None, matrixed = false)

  private val config = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.0.0"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  private val gMode: Gen[Any, MatrixCollapse] =
    Gen.elements(MatrixCollapse.values.toList*)

  def spec = suite("ZipxCentral")(
    test("plusExtraSteps keeps gpg-import and appends the new bundle in order") {
      val clean = Steps.of("clean-full")(zipx.workflow.Step(name = Some("cleanFull"), run = Some("echo cleanFull")))
      val cap   = ZipxCentral.release.plusExtraSteps(clean)
      val names = cap.extraSteps(stepContext).flatMap(_.name)
      val wf    = Planner.plan(sampleGraph, List(cap), config)
      val shown = wf.jobs.get("publish").toList.flatMap(_.steps.flatMap(_.name))
      assertTrue(
        names == List("Import signing key", "cleanFull"),
        shown.indexOf("Import signing key") >= 0,
        shown.indexOf("cleanFull") > shown.indexOf("Import signing key"),
      )
    },
    test("withExtraSteps still replaces the pack extras") {
      val clean = Steps.of("clean-full")(zipx.workflow.Step(name = Some("cleanFull"), run = Some("echo cleanFull")))
      val names = ZipxCentral.release.withExtraSteps(clean).extraSteps(stepContext).flatMap(_.name)
      assertTrue(names == List("cleanFull"))
    },
    test("the typed gpg import script renders the exact bytes the hand-written one did") {
      val importRun = ZipxCentral.gpgImportSteps(stepContext).headOption.flatMap(_.run).getOrElse("")
      assertTrue(
        importRun ==
          """mkdir -p ~/.gnupg && chmod 700 ~/.gnupg
            |echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
            |echo "pinentry-mode loopback"   >> ~/.gnupg/gpg.conf
            |gpgconf --kill gpg-agent || true
            |echo "$PGP_SECRET" | base64 --decode | gpg --batch --import""".stripMargin,
        !importRun.startsWith("set -"),
        ZipxCentral.gpgImportSteps.rawFragments.isEmpty,
      )
    },
    test("OrgSecretNames covers the five early-effect secrets") {
      assertTrue(
        ZipxCentral.OrgSecretNames.toSet ==
          Set("PGP_KEY_HEX", "PGP_SECRET", "PGP_PASSPHRASE", "SONATYPE_USERNAME", "SONATYPE_PASSWORD")
      )
    },
    test("Once needsCapabilities fans out over allJobIds of the dependency under every collapse mode") {
      check(gMode) { mode =>
        val graph = sampleGraph.mapNodes {
          case n if n.id == "serviceA" => n.copy(docker = true)
          case n                       => n
        }
        val multiDocker = Capability
          .custom(
            name = Capability.DockerName,
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("Docker/publish")),
            participates = _.docker,
            targets = _ => List(Target(TargetName("us")), Target(TargetName("eu"))),
            scope = CapabilityScope.Aggregate,
          )
          .withMatrixCollapse(mode)
        val after = Capability.once(
          name = CapabilityName("notify"),
          command = SbtCommand.unsafeTask("echo done"),
          phase = Phase.Publish,
          gate = Gate.Always,
          needsCapabilities = List(Capability.DockerName),
        )
        val wf       = Planner.plan(graph, List(multiDocker, after), config)
        val expected = Planner.allJobIds(multiDocker, graph, config).map(id => id: String).sorted
        assertTrue(wf.jobs.get("notify").exists(_.needs.sorted == expected))
      }
    },
    test(
      "release is one Aggregate publish job: every publisher's publishSigned, cross only where built, then sonaRelease"
    ) {
      check(gMode) { mode =>
        val wf  = Planner.plan(sampleGraph, List(ZipxCentral.release.withMatrixCollapse(mode)), config)
        val job = wf.jobs.get("publish")
        val run = job.flatMap(_.steps.find(_.name.contains("publish")).flatMap(_.run)).getOrElse("")
        assertTrue(
          wf.jobs.keys.filter(_.startsWith("publish")).toList == List("publish"),
          run.contains("+schema/publishSigned"),
          run.contains("+api/publishSigned"),
          run.contains("legacyClient/publishSigned"),
          !run.contains("+legacyClient"),
          run.endsWith("sonaRelease'") || run.contains("; sonaRelease"),
          job.exists(_.`if`.exists(_.contains("refs/tags/v"))),
        )
      }
    },
    test("release signs with org secrets by name, and only the key import step sees PGP_SECRET") {
      val job        = Planner.plan(sampleGraph, List(ZipxCentral.release), config).jobs.get("publish")
      val env        = job.map(_.env).getOrElse(Map.empty)
      val signingKey = job.flatMap(_.steps.find(_.name.contains("Import signing key")))
      assertTrue(
        env.get("PGP_PASSPHRASE").contains("${{ secrets.PGP_PASSPHRASE }}"),
        env.get("SONATYPE_USERNAME").contains("${{ secrets.SONATYPE_USERNAME }}"),
        !env.contains("PGP_SECRET"),
        signingKey.exists(_.env.get("PGP_SECRET").contains("${{ secrets.PGP_SECRET }}")),
        signingKey.exists(_.run.exists(_.contains("""echo "$PGP_SECRET" | base64 --decode | gpg --batch --import"""))),
        signingKey.exists(_.run.forall(!_.contains("$$PGP_SECRET"))),
      )
    },
  )
end ZipxCentralSpec
