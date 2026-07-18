package zipx.central

import zio.test.*
import zipx.core.*

object ZipxCentralSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(workflowName = "CI", cacheEpoch = "1.0.0", affected = AffectedMode.Always)

  def spec = suite("ZipxCentral")(
    test("publishSigned replaces bare publish with publishSigned + org secret env + GPG import") {
      val wf  = Planner.plan(sampleGraph, List(ZipxCentral.publishSigned), config)
      val job = wf.jobs("publish-schema")
      val run = job.steps.last.run.getOrElse("")
      assertTrue(
        run.contains("publishSigned"),
        !run.contains("/publish'"), // not the unsigned task as the sole command
        job.env.get("PGP_PASSPHRASE").contains("${{ secrets.PGP_PASSPHRASE }}"),
        job.env.get("SONATYPE_USERNAME").contains("${{ secrets.SONATYPE_USERNAME }}"),
        !job.env.contains("PGP_SECRET"), // secret stays on the import step, not job env
        job.steps.exists(s =>
          s.name.contains("Import signing key") && s.env.get("PGP_SECRET").contains("${{ secrets.PGP_SECRET }}")
        ),
        job.`if`.exists(_.contains("refs/tags/v")),
      )
    },
    test("gpg import uses $PGP_SECRET (NOT $$) so bash expands the env var instead of the PID") {
      val importRun = Planner
        .plan(sampleGraph, List(ZipxCentral.publishSigned), config)
        .jobs("publish-schema")
        .steps
        .find(_.name.contains("Import signing key"))
        .flatMap(_.run)
        .getOrElse("")
      assertTrue(
        importRun.contains("""echo "$PGP_SECRET" | base64 --decode | gpg --batch --import"""),
        !importRun.contains("$$PGP_SECRET"),
      )
    },
    test("cross-built modules get +publishSigned; single-version do not") {
      val wf                = Planner.plan(sampleGraph, List(ZipxCentral.publishSigned), config)
      def runOf(id: String) = wf.jobs(id).steps.last.run.getOrElse("")
      assertTrue(
        runOf("publish-api").contains("+api/publishSigned"),
        runOf("publish-legacyClient").contains("legacyClient/publishSigned"),
        !runOf("publish-legacyClient").contains("+legacyClient"),
      )
    },
    test("releaseOnce needs every publish job and runs sonaRelease") {
      val wf  = Planner.plan(sampleGraph, List(ZipxCentral.publishSigned, ZipxCentral.releaseOnce), config)
      val rel = wf.jobs("central-release")
      assertTrue(
        rel.steps.last.run.exists(_.contains("sonaRelease")),
        rel.needs.contains("publish-schema"),
        rel.needs.contains("publish-api"),
        rel.needs.contains("publish-clientA"),
        rel.needs.contains("publish-legacyClient"),
        !rel.needs.exists(_.startsWith("test-")),
        rel.`if`.exists(_.contains("refs/tags/v")),
        rel.env.get("SONATYPE_PASSWORD").contains("${{ secrets.SONATYPE_PASSWORD }}"),
      )
    },
    test("Once needsCapabilities fans out over all per-target jobs of a dependency") {
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val multiDocker = Capability.custom(
        name = "docker",
        command = n => s"${n.id}/Docker/publish",
        participates = _.docker,
        targets = _ => List(Target("us"), Target("eu")),
      )
      val after = Capability.once(
        name = "notify",
        command = "echo done",
        phase = Phase.Publish,
        gate = Gate.Always,
        needsCapabilities = List("docker"),
      )
      val needs = Planner.plan(graph, List(multiDocker, after), config).jobs("notify").needs
      assertTrue(needs.contains("docker-serviceA-us"), needs.contains("docker-serviceA-eu"))
    },
    test("OrgSecretNames covers the five early-effect secrets") {
      assertTrue(
        ZipxCentral.OrgSecretNames.toSet ==
          Set("PGP_KEY_HEX", "PGP_SECRET", "PGP_PASSPHRASE", "SONATYPE_USERNAME", "SONATYPE_PASSWORD")
      )
    },
  )
end ZipxCentralSpec
