package zipx.central

import zio.test.*
import zipx.core.*

object ZipxCentralSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(
    workflowName = "CI",
    cacheEpoch = "1.0.0",
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  def spec = suite("ZipxCentral")(
    test("publishSigned replaces bare publish with publishSigned + org secret env + GPG import") {
      val wf  = Planner.plan(sampleGraph, List(ZipxCentral.publishSigned), config)
      val job = wf.jobs("publish-schema")
      val run = job.steps.find(_.name.contains("zipx publish")).flatMap(_.run).getOrElse("")
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
      def runOf(id: String) =
        wf.jobs(id).steps.find(_.name.contains("zipx publish")).flatMap(_.run).getOrElse("")
      assertTrue(
        runOf("publish-api").contains("+api/publishSigned"),
        runOf("publish-legacyClient").contains("legacyClient/publishSigned"),
        !runOf("publish-legacyClient").contains("+legacyClient"),
      )
    },
    test("publish jobs upload target/sona-staging; central-release downloads and merges before sonaRelease") {
      val wf       = Planner.plan(sampleGraph, List(ZipxCentral.publishSigned, ZipxCentral.releaseOnce), config)
      val pub      = wf.jobs("publish-schema")
      val rel      = wf.jobs("central-release")
      val upload   = pub.steps.find(_.name.contains("Upload sona staging"))
      val download = rel.steps.find(_.name.contains("Download sona staging"))
      val pubIdx   = pub.steps.indexWhere(_.name.contains("zipx publish"))
      val upIdx    = pub.steps.indexWhere(_.name.contains("Upload sona staging"))
      val dlIdx    = rel.steps.indexWhere(_.name.contains("Download sona staging"))
      val runIdx   = rel.steps.indexWhere(_.run.exists(_.contains("sonaRelease")))
      assertTrue(
        upload.exists(_.uses.exists(_.startsWith("actions/upload-artifact@"))),
        upload.exists(_.`with`.get("name").contains("sona-staging-publish-schema")),
        upload.exists(_.`with`.get("path").contains(ZipxCentral.StagingDir)),
        download.exists(_.uses.exists(_.startsWith("actions/download-artifact@"))),
        download.exists(_.`with`.get("pattern").contains("sona-staging-*")),
        download.exists(_.`with`.get("path").contains(ZipxCentral.StagingDir)),
        download.exists(_.`with`.get("merge-multiple").contains("true")),
        pubIdx >= 0,
        upIdx > pubIdx, // upload after publishSigned
        dlIdx >= 0,
        runIdx > dlIdx, // download before sonaRelease
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
    test("release is one publish job with publishSigned; sonaRelease and no staging artifacts") {
      val wf  = Planner.plan(sampleGraph, List(ZipxCentral.release), config)
      val job = wf.jobs("publish")
      val run = job.steps.find(_.name.contains("zipx publish")).flatMap(_.run).getOrElse("")
      assertTrue(
        wf.jobs.keys.toList == List("publish"),
        run.contains("publishSigned; sonaRelease"),
        !wf.jobs.contains("central-release"),
        !job.steps.exists(_.name.contains("Upload sona staging")),
        job.steps.exists(_.name.contains("Import signing key")),
        job.env.get("SONATYPE_USERNAME").contains("${{ secrets.SONATYPE_USERNAME }}"),
        job.`if`.exists(_.contains("refs/tags/v")),
      )
    },
  )
end ZipxCentralSpec
