package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Scheduled pin-feed companion: cron + dispatch. Apply/PR only when some feed uses [[PinAction.Update]].
  *
  * Opening that PR uses `GITHUB_TOKEN` by default, or a GitHub App installation token when [[CompanionAuth]] secrets
  * are set. Needs the repo or org setting **Allow GitHub Actions to create and approve pull requests**.
  */
object PinCheckWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-pin-check.yml"

  val DefaultSchedule: Cron = Cron.weekly(DayOfWeek.Sunday)

  val UpdateBranch: String = "zipx/pin-updates"

  def plan(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      hasUpdate: Boolean,
      schedule: Cron = DefaultSchedule,
      lookup: RegistryCredentials = RegistryCredentials.Anonymous,
      readPackages: Boolean = false,
      sbtImage: Option[SbtImagePlan] = None,
  ): Workflow =
    val checkout =
      if hasUpdate then
        Step(
          uses = Some(pins.checkout),
          `with` = CompanionAuth.checkoutWith,
        )
      else Step(uses = Some(pins.checkout))
    val setup   = SbtImageWorkflow.toolchain(pins, javaVersion, runnerOs, "pin-check", sbtImage)
    val check   = Step(name = Some("Check pin feeds"), run = Some("sbt zipxPinCheck"))
    val refresh = Step(
      name = Some("Refresh generated env"),
      run = Some("sbt \"zipxAgentEnvGenerate; zipxSbtEnvGenerate\""),
    )
    val steps =
      if hasUpdate then
        CompanionAuth.steps ++ List(checkout) ++ setup ++ List(
          check,
          refresh,
          Step.run(updatePrScript).named("Open update PR").build,
        )
      else List(checkout) ++ setup ++ List(check)
    val packages    = if readPackages then ListMap("packages" -> "read") else ListMap.empty
    val permissions =
      if hasUpdate then ListMap("contents" -> "write", "pull-requests" -> "write", "issues" -> "write") ++ packages
      else ListMap("contents" -> "read") ++ packages
    val token =
      if hasUpdate then ListMap("GITHUB_TOKEN" -> "${{ secrets.GITHUB_TOKEN }}")
      else ListMap.empty
    SbtImageWorkflow.use(
      Workflow(
        name = "zipx pin check",
        on = Triggers(schedule = List(schedule), workflowDispatch = Some(WorkflowDispatch())),
        permissions = permissions,
        jobs = ListMap(
          "pin-check" -> Job(
            name = Some("Check pin feeds"),
            runsOn = List(runnerOs),
            env = EnvValue.renderAll(SnapshotPins.ciEnv(lookup.env)) ++ token,
            steps = steps,
          )
        ),
      ),
      sbtImage,
      pins.checkout,
      runnerOs,
    )
  end plan

  def render(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      hasUpdate: Boolean,
      schedule: Cron = DefaultSchedule,
      lookup: RegistryCredentials = RegistryCredentials.Anonymous,
      readPackages: Boolean = false,
      sbtImage: Option[SbtImagePlan] = None,
  ): Either[String, String] =
    Render
      .render(plan(pins, javaVersion, runnerOs, hasUpdate, schedule, lookup, readPackages, sbtImage))
      .map(ActionPinFile.annotateUses(_, pins))

  private def updatePrScript: Script =
    CompanionPr.open(
      branchPrefix = "zipx/pin-updates",
      commitMessage = "ci: apply zipx pin feed updates",
      prTitle = "ci: zipx pin feed updates",
      prBody = "Applied pin feed Update policy.",
      emptyMessage = "No pin updates to commit.",
    )
end PinCheckWorkflow

/** PR `pin-check` extras: env name and the fetch step [[PinPrGate.Introduced]] needs. */
object PinCheck:

  val BaseShaEnv: String = "ZIPX_PIN_BASE_SHA"

  val fetchBaseSha: Steps =
    Steps.of("fetch-pin-base")(
      Step
        .run(
          Script(
            Exec(
              "git",
              Word.lit("fetch"),
              Word.lit("--no-tags"),
              Word.lit("origin"),
              Word.vq("ZIPX_PIN_BASE_SHA"),
            )
          )
        )
        .named("Fetch PR base SHA")
        .build
    )
end PinCheck
