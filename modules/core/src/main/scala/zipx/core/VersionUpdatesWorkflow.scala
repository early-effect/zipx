package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Scheduled catalog companion: cron + dispatch. Applies every ZipxVersions row kind (`zipxDepUpdate`,
  * `zipxActionUpdate`, `zipxPinUpdate`), regenerates, and opens `zipx/version-updates`.
  *
  * Opening that PR uses `GITHUB_TOKEN` and needs the repo or org setting **Allow GitHub Actions to create and approve
  * pull requests**.
  */
object VersionUpdatesWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-version-updates.yml"

  val DefaultSchedule: Cron = Cron.weekly(DayOfWeek.Sunday)

  val UpdateBranch: String = "zipx/version-updates"

  def plan(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      schedule: Cron = DefaultSchedule,
  ): Workflow =
    val setupJava = Step(
      name = Some("Setup JDK"),
      uses = Some(pins.setupJava),
      `with` = ListMap("distribution" -> "temurin", "java-version" -> javaVersion),
    )
    val checkout = Step(
      uses = Some(pins.checkout),
      `with` = ListMap("token" -> "${{ secrets.GITHUB_TOKEN }}", "persist-credentials" -> "true"),
    )
    val apply = Step
      .run(
        Script(
          List(
            Exec("sbt", Word.quoted("zipxDepUpdate yes")),
            Exec("sbt", Word.quoted("zipxActionUpdate yes")),
            Exec("sbt", Word.quoted("zipxPinUpdate yes")),
            Exec("sbt", Word.lit("zipxWorkflowGenerate")),
          ),
          trailingNewline = true,
        )
      )
      .named("Apply catalog updates")
      .build
    val openPr = Step.run(updatePrScript).named("Open update PR").build
    Workflow(
      name = "zipx version updates",
      on = Triggers(schedule = List(schedule), workflowDispatch = true),
      permissions = ListMap("contents" -> "write", "pull-requests" -> "write"),
      jobs = ListMap(
        "version-updates" -> Job(
          name = Some("Catalog version updates"),
          runsOn = List(runnerOs),
          env = ListMap("GITHUB_TOKEN" -> "${{ secrets.GITHUB_TOKEN }}"),
          steps = List(checkout, setupJava, Step(uses = Some(pins.setupSbt)), apply, openPr),
        )
      ),
    )
  end plan

  def render(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      schedule: Cron = DefaultSchedule,
  ): Either[String, String] =
    Render.render(plan(pins, javaVersion, runnerOs, schedule)).map(ActionPinFile.annotateUses(_, pins))

  private def updatePrScript: Script =
    CompanionPr.open(
      branch = "zipx/version-updates",
      commitMessage = "ci: zipx version updates",
      prTitle = "ci: zipx version updates",
      prBody = "Applied zipxDepUpdate, zipxActionUpdate, and zipxPinUpdate.",
      emptyMessage = "No catalog updates to commit.",
    )
end VersionUpdatesWorkflow
