package zipx.core

import zipx.workflow.{CancelInProgress, Job, JobEnvironment}
import zio.test.*

object ReleaseWorkflowSpec extends ZIOSpecDefault:

  private val config  = PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.2.3-SNAPSHOT"))
  private val signing = Map[String, EnvValue]("PGP_PASSPHRASE" -> EnvValue.secret("PGP_PASSPHRASE"))
  private val central = ReleaseWorkflow(ArtifactRegistry.MavenCentral, env = signing)

  private val docs = Capability
    .steps(name = CapabilityName("docs"), steps = _ => Nil, phase = Phase.Deploy, gate = Gate.Always)
    .copy(
      workflowCall = Some(
        WorkflowCall(uses = zipx.workflow.ActionRef("early-effect/.github/.github/workflows/pages.yml@main"))
      )
    )

  private def releaseJob(release: ReleaseWorkflow = central): Option[Job] =
    ReleaseWorkflow.plan(release, config).jobs.get("release")

  private def runs(job: Job): List[String] = job.steps.flatMap(_.run)

  def spec = suite("ReleaseWorkflow")(
    test("a GitHub Release's tag or a dispatch starts it, and a release in flight is never cancelled") {
      val wf = ReleaseWorkflow.plan(central, config)
      assertTrue(
        wf.on.push.map(_.tags).contains(List("v*", "*/v*")),
        wf.on.workflowDispatch.isDefined,
        wf.on.pullRequest.isEmpty,
        wf.concurrency.map(_.cancelInProgress).contains(CancelInProgress.Never),
        wf.permissions.get("contents").contains("write"),
      )
    },
    test("the job runs for a tag push, or for a dispatch on the default branch only") {
      assertTrue(
        releaseJob()
          .flatMap(_.`if`)
          .contains(
            "github.event_name == 'push' || github.ref_name == github.event.repository.default_branch"
          )
      )
    },
    test("it binds the release environment, carries the secrets and the ref, and restores the build cache") {
      val job = releaseJob()
      assertTrue(
        job.flatMap(_.environment).contains(JobEnvironment("zipx-release")),
        job.flatMap(_.env.get("PGP_PASSPHRASE")).contains("${{ secrets.PGP_PASSPHRASE }}"),
        job.flatMap(_.env.get("ZIPX_RELEASE_REF")).contains("${{ github.ref }}"),
        job.exists(_.steps.exists(_.`with`.get("cache-mode").contains("restore"))),
        !job.exists(_.steps.exists(_.`with`.get("cache-mode").contains("save"))),
      )
    },
    test("no environment binds when the build opts out") {
      assertTrue(releaseJob(central.copy(environment = None)).exists(_.environment.isEmpty))
    },
    test("zipxRelease runs once, after a tag is proven to be on the default branch") {
      val scripts = releaseJob().toList.flatMap(runs)
      val release = scripts.indexWhere(_.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""))
      val onMain  = scripts.indexWhere(_.contains("if ! git merge-base --is-ancestor \"$GITHUB_SHA\""))
      assertTrue(
        scripts.count(_.contains("zipxRelease")) == 1,
        onMain >= 0,
        onMain < release,
      )
    },
    test("only a dispatch creates the tags and GitHub Releases, with the workflow's own token") {
      val publish = releaseJob().flatMap(_.steps.find(_.run.exists(_.contains("gh release create"))))
      assertTrue(
        publish.flatMap(_.`if`).contains("github.event_name == 'workflow_dispatch'"),
        publish.flatMap(_.env.get("GH_TOKEN")).contains("${{ github.token }}"),
        publish.flatMap(_.run).exists(_.contains("target/zipx-release-tags.txt")),
      )
    },
    test("a docs capability deploys after a dispatched release; a tag push leaves docs to ci.yml") {
      val job = ReleaseWorkflow.plan(central, config, Some(docs)).jobs.get("docs")
      assertTrue(
        job.map(_.needs).contains(List("release")),
        job.flatMap(_.`if`).contains("github.event_name == 'workflow_dispatch'"),
        job.flatMap(_.uses).isDefined,
        ReleaseWorkflow.plan(central, config).jobs.get("docs").isEmpty,
      )
    },
    test("it renders valid workflow YAML") {
      assertTrue(ReleaseWorkflow.render(central, config, Some(docs)).exists(_.contains("name: zipx release")))
    },
  )
end ReleaseWorkflowSpec
