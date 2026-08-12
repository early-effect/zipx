package zipx.central

import neotype.unwrap
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

  /** sampleGraph's Graph publishSigned cannot collapse: cross `+publishSigned` vs single-version `publishSigned` are
    * not isomorphic, and DependencyOrdered publishers have same-cap needs. Auto/Off expand; Strict/Coarse refuse at
    * generate time.
    */
  private val gExpandingMode: Gen[Any, MatrixCollapse] =
    Gen.elements(MatrixCollapse.Auto, MatrixCollapse.Off)

  private def publishSigned(mode: MatrixCollapse): Capability =
    ZipxCentral.publishSigned.withMatrixCollapse(mode)

  private def downloadsOk(download: Option[zipx.workflow.Step]): Boolean =
    download.exists { d =>
      d.uses.exists(_.unwrap.startsWith("actions/download-artifact@")) &&
      d.`with`.get("pattern").contains("sona-staging-*") &&
      d.`with`.get("path").contains(ZipxCentral.StagingDir) &&
      d.`with`.get("merge-multiple").contains("true")
    }

  def spec = suite("ZipxCentral")(
    test("the typed gpg import script renders the exact bytes the hand-written one did") {
      val importRun = ZipxCentral.gpgImportSteps(stepContext).head.run.getOrElse("")
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
    test("Strict and Coarse refuse Graph publishSigned on the sample diamond") {
      check(Gen.elements(MatrixCollapse.Strict, MatrixCollapse.Coarse)) { mode =>
        val err =
          try
            Planner.plan(sampleGraph, List(publishSigned(mode)), config)
            None
          catch case e: RuntimeException => Some(e.getMessage)
        assertTrue(err.exists(_.startsWith("zipx:")))
      }
    },
    test("gpg import uses $PGP_SECRET (NOT $$) so bash expands the env var instead of the PID") {
      check(gExpandingMode) { mode =>
        val cap        = publishSigned(mode)
        val wf         = Planner.plan(sampleGraph, List(cap), config)
        val importRuns =
          Planner
            .allJobIds(cap, sampleGraph, config)
            .map(id => id: String)
            .flatMap(id => wf.jobs(id).steps)
            .collect { case s if s.name.contains("Import signing key") => s.run.getOrElse("") }
        assertTrue(
          importRuns.nonEmpty,
          importRuns.forall(_.contains("""echo "$PGP_SECRET" | base64 --decode | gpg --batch --import""")),
          importRuns.forall(!_.contains("$$PGP_SECRET")),
        )
      }
    },
    test("publishSigned jobs carry publishSigned, org secrets, and GPG import under expanding collapse modes") {
      check(gExpandingMode) { mode =>
        val cap  = publishSigned(mode)
        val wf   = Planner.plan(sampleGraph, List(cap), config)
        val jobs = Planner.allJobIds(cap, sampleGraph, config).map(id => id: String).map(wf.jobs(_))
        assertTrue(
          jobs.nonEmpty,
          jobs.forall { job =>
            val run = job.steps.find(_.name.contains("publish")).flatMap(_.run).getOrElse("")
            run.contains("publishSigned") &&
            !run.contains("/publish'") &&
            job.env.get("PGP_PASSPHRASE").contains("${{ secrets.PGP_PASSPHRASE }}") &&
            job.env.get("SONATYPE_USERNAME").contains("${{ secrets.SONATYPE_USERNAME }}") &&
            !job.env.contains("PGP_SECRET") &&
            job.steps.exists(s =>
              s.name.contains("Import signing key") &&
                s.env.get("PGP_SECRET").contains("${{ secrets.PGP_SECRET }}")
            ) &&
            job.`if`.exists(_.contains("refs/tags/v"))
          },
        )
      }
    },
    test("cross-built modules get +publishSigned; single-version do not") {
      check(gExpandingMode) { mode =>
        val cap  = publishSigned(mode)
        val wf   = Planner.plan(sampleGraph, List(cap), config)
        val runs = Planner
          .allJobIds(cap, sampleGraph, config)
          .map(id => id: String)
          .flatMap(id => wf.jobs(id).steps.find(_.name.contains("publish")).flatMap(_.run))
        val joined = runs.mkString("\n")
        assertTrue(
          joined.contains("+api/publishSigned") || joined.contains("api/publishSigned"),
          joined.contains("legacyClient/publishSigned"),
          !joined.contains("+legacyClient"),
        )
      }
    },
    test("staging upload/download and release needs track allJobIds under expanding collapse modes") {
      check(gExpandingMode) { mode =>
        val pub      = publishSigned(mode)
        val wf       = Planner.plan(sampleGraph, List(pub, ZipxCentral.releaseOnce), config)
        val pubIds   = Planner.allJobIds(pub, sampleGraph, config).map(id => id: String)
        val rel      = wf.jobs("central-release")
        val download = rel.steps.find(_.name.contains("Download sona staging"))
        val dlIdx    = rel.steps.indexWhere(_.name.contains("Download sona staging"))
        val runIdx   = rel.steps.indexWhere(_.run.exists(_.contains("sonaRelease")))
        val uploads  = pubIds.map { id =>
          val job    = wf.jobs(id)
          val upload = job.steps.find(_.name.contains("Upload sona staging"))
          val pubIdx = job.steps.indexWhere(_.name.contains("publish"))
          val upIdx  = job.steps.indexWhere(_.name.contains("Upload sona staging"))
          (id, job, upload, pubIdx, upIdx)
        }
        assertTrue(
          pubIds.nonEmpty,
          pubIds.forall(wf.jobs.contains),
          rel.needs.sorted == pubIds.sorted,
          !rel.needs.exists(_.startsWith("test-")),
          downloadsOk(download),
          dlIdx >= 0,
          runIdx > dlIdx,
          uploads.forall { case (_, _, upload, pubIdx, upIdx) =>
            upload.exists(_.uses.exists(_.unwrap.startsWith("actions/upload-artifact@"))) &&
            upload.exists(_.`with`.get("path").contains(ZipxCentral.StagingDir)) &&
            pubIdx >= 0 && upIdx > pubIdx
          },
          rel.`if`.exists(_.contains("refs/tags/v")),
          rel.env.get("SONATYPE_PASSWORD").contains("${{ secrets.SONATYPE_PASSWORD }}"),
        )
      }
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
        assertTrue(wf.jobs("notify").needs.sorted == expected)
      }
    },
    test(
      "release is one Aggregate publish job: every publisher's publishSigned then sonaRelease, no staging artifacts"
    ) {
      check(gMode) { mode =>
        val wf  = Planner.plan(sampleGraph, List(ZipxCentral.release.withMatrixCollapse(mode)), config)
        val job = wf.jobs("publish")
        val run = job.steps.find(_.name.contains("publish")).flatMap(_.run).getOrElse("")
        assertTrue(
          wf.jobs.keys.filter(_.startsWith("publish")).toList == List("publish"),
          run.contains("schema/publishSigned"),
          run.contains("api/publishSigned"),
          run.endsWith("sonaRelease'") || run.contains("; sonaRelease"),
          !wf.jobs.contains("central-release"),
          !job.steps.exists(_.name.contains("Upload sona staging")),
          job.steps.exists(_.name.contains("Import signing key")),
          job.env.get("SONATYPE_USERNAME").contains("${{ secrets.SONATYPE_USERNAME }}"),
          job.`if`.exists(_.contains("refs/tags/v")),
        )
      }
    },
  )
end ZipxCentralSpec
