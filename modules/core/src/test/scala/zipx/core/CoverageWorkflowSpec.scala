package zipx.core

import zipx.core.Rendered.yaml
import zipx.workflow.{CancelInProgress, Cron, Job, PullRequestActivity, Workflow}
import zio.test.*

object CoverageWorkflowSpec extends ZIOSpecDefault:

  private val config = PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"))

  private val coverageLabel = CoverageTrigger.prLabel("coverage")
  private val fullCiLabel   = CoverageTrigger.prLabel("full-ci")

  private val genTrigger: Gen[Any, CoverageTrigger] =
    Gen.oneOf(
      Gen.int(0, 23).map(Cron.dailyMake(_, 0)).collect { case Right(cron) => CoverageTrigger.Scheduled(cron) },
      Gen.const(CoverageTrigger.Dispatch),
      Gen.elements(coverageLabel, fullCiLabel),
    )

  private def planned(first: CoverageTrigger, rest: CoverageTrigger*): Workflow =
    CoverageWorkflow.plan(Coverage.workflow(first, rest*), config)

  private def job(wf: Workflow): Job = wf.jobs("coverage")

  private def cacheModeOf(job: Job): Option[String] =
    job.steps.find(_.uses.contains(ZipxComposites.SbtSetupRef)).flatMap(_.`with`.get("cache-mode"))

  private def commandOf(job: Job): Option[String] =
    job.steps.flatMap(_.run).find(_.startsWith("sbt "))

  def spec = suite("CoverageWorkflow")(
    suite("for any trigger list")(
      test("one coverage job that restores the build snapshot and never saves it") {
        check(Gen.listOf1(genTrigger)) { triggers =>
          val wf = CoverageWorkflow.plan(CoverageWorkflow(triggers), config)
          assertTrue(wf.jobs.keySet == Set("coverage"), cacheModeOf(job(wf)).contains("restore"))
        }
      },
      test("each trigger kind maps to its own event, and only a label gates the job") {
        check(Gen.listOf1(genTrigger)) { triggers =>
          val wf      = CoverageWorkflow.plan(CoverageWorkflow(triggers), config)
          val labeled = triggers.exists { case CoverageTrigger.PrLabel(_) => true; case _ => false }
          assertTrue(
            wf.on.pullRequest.isDefined == labeled,
            job(wf).`if`.isDefined == labeled,
            wf.on.workflowDispatch.isDefined == triggers.contains(CoverageTrigger.Dispatch),
            wf.on.schedule == triggers.collect { case CoverageTrigger.Scheduled(cron) => cron }.distinct,
            wf.on.push.isEmpty,
          )
        }
      },
      test("renders") {
        check(Gen.listOf1(genTrigger)) { triggers =>
          assertTrue(CoverageWorkflow.render(CoverageWorkflow(triggers), config).isRight)
        }
      },
    ),
    test("runs coverage, the task, and coverageAggregate in one session, then uploads the report") {
      val steps = job(planned(CoverageTrigger.Dispatch)).steps
      assertTrue(
        commandOf(job(planned(CoverageTrigger.Dispatch))).contains("sbt 'coverage; testFull; coverageAggregate'"),
        steps.last.uses.contains(ActionPins.Defaults.uploadArtifact),
        steps.last.`with`.get("if-no-files-found").contains("error"),
      )
    },
    test("a custom task replaces testFull") {
      val custom = Coverage.workflow(CoverageTrigger.Dispatch).copy(task = SbtCommand.unsafeTask("Test/testFull"))
      assertTrue(
        commandOf(job(CoverageWorkflow.plan(custom, config)))
          .contains("sbt 'coverage; Test/testFull; coverageAggregate'")
      )
    },
    test("a PR label listens for the label going on as well as GitHub's default activities") {
      assertTrue(
        planned(coverageLabel).on.pullRequest
          .map(_.types)
          .contains(
            List(
              PullRequestActivity.Opened,
              PullRequestActivity.Synchronize,
              PullRequestActivity.Reopened,
              PullRequestActivity.Labeled,
            )
          )
      )
    },
    test("the label gate skips a PR without the label, and a labeled event for some other label") {
      assertTrue(
        job(planned(coverageLabel)).`if`.contains(
          "github.event_name != 'pull_request' || (contains(github.event.pull_request.labels.*.name, 'coverage') && " +
            "(github.event.action != 'labeled' || github.event.label.name == 'coverage'))"
        )
      )
    },
    test("several labels are alternatives") {
      assertTrue(
        job(planned(coverageLabel, fullCiLabel)).`if`.contains(
          "github.event_name != 'pull_request' || ((contains(github.event.pull_request.labels.*.name, 'coverage') || " +
            "contains(github.event.pull_request.labels.*.name, 'full-ci')) && (github.event.action != 'labeled' || " +
            "github.event.label.name == 'coverage' || github.event.label.name == 'full-ci'))"
        )
      )
    },
    test("a remote cache backend turns the LocalDir cache off") {
      val remote = CoverageWorkflow
        .plan(Coverage.workflow(CoverageTrigger.Dispatch), config.copy(cache = RemoteCacheProof.sidecar))
      assertTrue(cacheModeOf(job(remote)).contains("off"))
    },
    test("build-wide zipxEnv reaches the job, since coverage runs the same suites as test") {
      val withEnv = config.copy(env = Map("GITHUB_TOKEN" -> EnvValue.secret("GH_PACKAGES_TOKEN")))
      val wf      = CoverageWorkflow.plan(Coverage.workflow(CoverageTrigger.Dispatch), withEnv)
      assertTrue(job(wf).env.get("GITHUB_TOKEN").contains("${{ secrets.GH_PACKAGES_TOKEN }}"))
    },
    test("reads contents only, and a new run on the same ref cancels the old one") {
      val wf = planned(CoverageTrigger.Dispatch)
      assertTrue(
        wf.permissions == Map("contents" -> "read"),
        wf.concurrency.map(_.group).contains("zipx-coverage-${{ github.ref }}"),
        wf.concurrency.map(_.cancelInProgress).contains(CancelInProgress.Always),
      )
    },
    test("renders the pinned upload-artifact with its version label") {
      val out = CoverageWorkflow.render(Coverage.workflow(coverageLabel), config).yaml
      assertTrue(
        out.contains("  pull_request:\n    types:\n      - opened\n"),
        out.contains("  cancel-in-progress: true\n"),
        out.contains(s"uses: ${ActionPins.Defaults.uploadArtifact} #"),
      )
    },
  )
end CoverageWorkflowSpec
