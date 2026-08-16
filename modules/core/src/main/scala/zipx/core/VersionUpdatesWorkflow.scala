package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Scheduled catalog companion: cron + dispatch. Applies every ZipxVersions row kind (`zipxDepUpdate`,
  * `zipxActionUpdate`, `zipxPinUpdate`), writes catalog outputs (`zipxCatalogGenerate`), and opens a PR on
  * `zipx/version-updates-$GITHUB_RUN_ID`.
  *
  * This workflow file is generated once and then left alone. Java and runner come from [[ZipxCiParams]]; java / sbt
  * Action pins live in `zipx-sbt-setup`. Checkout is a major tag (`actions/checkout@v7`) because `uses:` cannot be an
  * expression and `GITHUB_TOKEN` cannot push workflow SHA edits. [[CompanionPr]] stages everything except
  * `.github/workflows`.
  */
object VersionUpdatesWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-version-updates.yml"

  val DefaultSchedule: Cron = Cron.weekly(DayOfWeek.Sunday)

  val UpdateBranch: String = "zipx/version-updates"

  /** `actions/checkout@vN` from the catalog label (`v7.0.1` → `v7`), not the SHA pin. */
  def checkoutMajor(pins: ActionPins): Either[String, ActionRef] =
    val ver   = pins.version(ActionPins.Field.Checkout).getOrElse("v5")
    val major =
      if ver.startsWith("v") then "v" + ver.drop(1).takeWhile(_.isDigit)
      else ver.takeWhile(_.isDigit)
    val tag = if major == "v" || major.isEmpty then ver else major
    ActionRef.make(s"actions/checkout@$tag")

  def plan(
      checkout: ActionRef,
      schedule: Cron = DefaultSchedule,
  ): Workflow =
    val checkoutStep = Step(
      uses = Some(checkout),
      `with` = ListMap("token" -> "${{ secrets.GITHUB_TOKEN }}", "persist-credentials" -> "true"),
    )
    val load = Step
      .run(loadParams)
      .named("Load zipx CI params")
      .withId("zipx-ci")
      .build
    val setup = Step
      .usesRef(ZipxComposites.SbtSetupRef)
      .named("zipx sbt setup")
      .withInputs(
        ListMap(
          "java-version"     -> "${{ steps.zipx-ci.outputs.java-version }}",
          "runner-os"        -> "${{ steps.zipx-ci.outputs.runner-os }}",
          "cache-key-suffix" -> "version-updates",
          "node-version"     -> "",
          "sbt-disk-cache"   -> "false",
          "local-cache"      -> "false",
        )
      )
      .build
    val apply = Step
      .run(
        Script(
          List(
            Exec("sbt", Word.quoted("zipxDepUpdate yes")),
            Exec("sbt", Word.quoted("zipxActionUpdate yes")),
            Exec("sbt", Word.quoted("zipxPinUpdate yes")),
            Exec("sbt", Word.lit("zipxCatalogGenerate")),
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
      permissions = ListMap("contents" -> "write", "pull-requests" -> "write", "issues" -> "write"),
      jobs = ListMap(
        "version-updates" -> Job(
          name = Some("Catalog version updates"),
          runsOn = List("ubuntu-latest"),
          env = ListMap("GITHUB_TOKEN" -> "${{ secrets.GITHUB_TOKEN }}"),
          steps = List(checkoutStep, load, setup, apply, openPr),
        )
      ),
    )
  end plan

  def render(
      pins: ActionPins,
      schedule: Cron = DefaultSchedule,
  ): Either[String, String] =
    checkoutMajor(pins).flatMap(ref => Render.render(plan(ref, schedule)))

  private def loadParams: Script =
    Script
      .strict(
        Exec(".", Word.lit("project/zipx-ci.env")),
        setOutput("java-version", Word.v("ZIPX_JAVA_VERSION")),
        setOutput("runner-os", Word.v("ZIPX_RUNNER_OS")),
      )
      .withTrailingNewline(true)

  private inline def setOutput(inline name: String, value: Word.Quotable): Command =
    Exec("echo", Word.dquote(Word.lit(name + "="), value)).appendTo(Word.vq("GITHUB_OUTPUT"))

  private def updatePrScript: Script =
    CompanionPr.open(
      branchPrefix = "zipx/version-updates",
      commitMessage = "ci: zipx version updates",
      prTitle = "ci: zipx version updates",
      prBody = "Applied zipxDepUpdate, zipxActionUpdate, and zipxPinUpdate.",
      emptyMessage = "No catalog updates to commit.",
    )
end VersionUpdatesWorkflow
