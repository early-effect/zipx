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
  ): Workflow =
    val setupJava = Step(
      name = Some("Setup JDK"),
      uses = Some(pins.setupJava),
      `with` = ListMap("distribution" -> "temurin", "java-version" -> javaVersion),
    )
    val setupSbt = Step(uses = Some(pins.setupSbt))
    val checkout =
      if hasUpdate then
        Step(
          uses = Some(pins.checkout),
          `with` = CompanionAuth.checkoutWith,
        )
      else Step(uses = Some(pins.checkout))
    val check = Step(name = Some("Check pin feeds"), run = Some("sbt zipxPinCheck"))
    val steps =
      if hasUpdate then
        CompanionAuth.steps ++ List(
          checkout,
          setupJava,
          setupSbt,
          check,
          Step.run(updatePrScript).named("Open update PR").build,
        )
      else List(checkout, setupJava, setupSbt, check)
    val permissions =
      if hasUpdate then ListMap("contents" -> "write", "pull-requests" -> "write", "issues" -> "write")
      else ListMap("contents"              -> "read")
    Workflow(
      name = "zipx pin check",
      on = Triggers(schedule = List(schedule), workflowDispatch = true),
      permissions = permissions,
      jobs = ListMap(
        "pin-check" -> Job(
          name = Some("Check pin feeds"),
          runsOn = List(runnerOs),
          steps = steps,
        )
      ),
    )
  end plan

  def render(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      hasUpdate: Boolean,
      schedule: Cron = DefaultSchedule,
  ): Either[String, String] =
    Render.render(plan(pins, javaVersion, runnerOs, hasUpdate, schedule)).map(ActionPinFile.annotateUses(_, pins))

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
