package zipx.core

import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{CancelInProgress, Job, JobEnvironment, Step}
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
    ReleaseWorkflow.plan(release, config, TagScheme.PerRow).jobs.get("release")

  private def runs(job: Job): List[String] = job.steps.flatMap(_.run)

  def spec = suite("ReleaseWorkflow")(
    test("a GitHub Release's tag or a dispatch starts it, and a release in flight is never cancelled") {
      val wf = ReleaseWorkflow.plan(central, config, TagScheme.PerRow)
      assertTrue(
        wf.on.push.map(_.tags).contains(List("*/v*")),
        ReleaseWorkflow.plan(central, config, TagScheme.Bare).on.push.map(_.tags).contains(List("v*")),
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
        job.exists(_.env.get("ZIPX_RELEASE_REF").isEmpty),
        job.exists(_.steps.exists(_.`with`.get("cache-mode").contains("restore"))),
        !job.exists(_.steps.exists(_.`with`.get("cache-mode").contains("save"))),
      )
    },
    test("no environment binds when the build opts out") {
      assertTrue(releaseJob(central.copy(environment = None)).exists(_.environment.isEmpty))
    },
    test("zipxRelease runs once, and a tag is proven to be on the default branch before any secret step") {
      val gpg     = Steps.built("gpg-import")(Step.run(Script(Exec("gpg", Word.lit("--import")))).named("Import key"))
      val scripts = releaseJob(central.copy(steps = gpg)).toList.flatMap(runs)
      val release = scripts.indexWhere(_.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""))
      val onMain  = scripts.indexWhere(_.contains("if ! git merge-base --is-ancestor \"$GITHUB_SHA\""))
      val keyed   = scripts.indexWhere(_.contains("gpg --import"))
      assertTrue(
        scripts.count(_.contains("zipxRelease")) == 1,
        onMain >= 0,
        onMain < keyed,
        keyed < release,
      )
    },
    test("a dispatch names ships, default all, and a tag push still passes github.ref") {
      val wf    = ReleaseWorkflow.plan(central, config, TagScheme.PerRow)
      val bind  = releaseJob().flatMap(_.steps.find(_.name.contains("Release request")))
      val input = wf.on.workflowDispatch.flatMap(_.inputs.get(zipx.workflow.InputName("ships")))
      assertTrue(
        input.contains(
          zipx.workflow.DispatchInput.Text(
            description = ReleaseWorkflow.ShipsDescription,
            default = Some("all"),
            required = true,
          )
        ),
        bind.flatMap(_.env.get("ZIPX_SHIPS")).contains("${{ inputs.ships }}"),
        bind.flatMap(_.run).exists { script =>
          script.contains("""[ "$GITHUB_EVENT_NAME" = "workflow_dispatch" ]""") &&
          script.contains("""echo "ZIPX_RELEASE_REF=$ZIPX_SHIPS" >> "$GITHUB_ENV"""") &&
          script.contains("""echo "ZIPX_RELEASE_REF=$GITHUB_REF" >> "$GITHUB_ENV"""")
        },
        !wf.permissions.contains("packages"),
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
    test("docs deploy after every release ci.yml cannot see: a dispatch, or any <row>/v* tag") {
      val bare   = ReleaseWorkflow.plan(central, config, TagScheme.Bare, Some(docs)).jobs.get("docs")
      val perRow = ReleaseWorkflow.plan(central, config, TagScheme.PerRow, Some(docs)).jobs.get("docs")
      assertTrue(
        bare.map(_.needs).contains(List("release")),
        bare.flatMap(_.`if`).contains("github.event_name == 'workflow_dispatch'"),
        bare.flatMap(_.uses).isDefined,
        perRow.map(_.needs).contains(List("release")),
        perRow.exists(_.`if`.isEmpty),
        ReleaseWorkflow.plan(central, config, TagScheme.PerRow).jobs.get("docs").isEmpty,
      )
    },
    test("it renders valid workflow YAML") {
      assertTrue(
        ReleaseWorkflow.render(central, config, TagScheme.PerRow, Some(docs)).exists(_.contains("name: zipx release"))
      )
    },
  )
end ReleaseWorkflowSpec
