package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Scheduled pin-feed companion: weekly + dispatch. Apply/PR only when some feed uses [[PinAction.Update]]. */
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
          `with` = ListMap("token" -> "${{ secrets.GITHUB_TOKEN }}", "persist-credentials" -> "true"),
        )
      else Step(uses = Some(pins.checkout))
    val check = Step(name = Some("Check pin feeds"), run = Some("sbt zipxPinCheck"))
    val steps =
      if hasUpdate then
        List(checkout, setupJava, setupSbt, check, Step.run(updatePrScript).named("Open update PR").build)
      else List(checkout, setupJava, setupSbt, check)
    val permissions =
      if hasUpdate then ListMap("contents" -> "write", "pull-requests" -> "write")
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
    Script(
      List(
        If(
          ShTest.Empty(Word.dquote(Word.subst(Exec("git", Word.lit("status"), Word.lit("--porcelain"))))),
          Block(
            Exec("echo", Word.quoted("No pin updates to commit.")),
            Exit(),
          ),
        ),
        Exec("git", Word.lit("config"), Word.lit("user.name"), Word.quoted("github-actions[bot]")),
        Exec(
          "git",
          Word.lit("config"),
          Word.lit("user.email"),
          Word.quoted("41898282+github-actions[bot]@users.noreply.github.com"),
        ),
        Exec("git", Word.lit("checkout"), Word.lit("-B"), Word.lit("zipx/pin-updates")),
        Exec("git", Word.lit("add"), Word.lit("-A")),
        Exec("git", Word.lit("commit"), Word.lit("-m"), Word.quoted("ci: apply zipx pin feed updates")),
        Exec("git", Word.lit("push"), Word.lit("-u"), Word.lit("origin"), Word.lit("HEAD")),
        Exec(
          "gh",
          Word.lit("pr"),
          Word.lit("create"),
          Word.lit("--title"),
          Word.quoted("ci: zipx pin feed updates"),
          Word.lit("--body"),
          Word.quoted("Applied pin feed Update policy."),
          Word.lit("--head"),
          Word.lit("zipx/pin-updates"),
        ) || Exec("true"),
      ),
      trailingNewline = true,
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
