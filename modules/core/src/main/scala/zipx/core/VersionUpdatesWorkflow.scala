package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Scheduled catalog companion: cron + dispatch. Applies Lib / Plugin / Action rows via `zipx-cli` (above the target
  * sbt), then `zipxPinUpdate` / `zipxCatalogGenerate` once the session can load, and opens a PR on
  * `zipx/version-updates-$GITHUB_RUN_ID`.
  *
  * This workflow file is generated once and then left alone. Java and runner come from [[ZipxCiParams]]; java / sbt
  * Action pins live in `zipx-sbt-setup`. Checkout is a major tag (`actions/checkout@v7`) because `uses:` cannot be an
  * expression and `GITHUB_TOKEN` cannot push workflow SHA edits. [[CompanionAuth]] may mint an installation token
  * before checkout when `ZIPX_APP_ID` / `ZIPX_APP_PRIVATE_KEY` are set; otherwise checkout and `gh` keep
  * `GITHUB_TOKEN`. [[CompanionPr]] stages everything except repo-root `.github/workflows`. Nested trees such as
  * `examples/monorepo/.github/workflows/` are committed when `zipxVersionUpdatesExtraSteps` regenerates them.
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
      extraSteps: List[Step] = Nil,
      preSteps: List[Step] = Nil,
  ): Workflow =
    val checkoutStep = Step(
      uses = Some(checkout),
      `with` = CompanionAuth.checkoutWith,
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
          "local-cache"      -> "true",
          "coursier"         -> "true",
        )
      )
      .build
    val apply = Step
      .run(applyScript)
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
          steps =
            CompanionAuth.steps ++ List(checkoutStep, load, setup) ++ preSteps ++ List(apply) ++ extraSteps ++ List(
              openPr
            ),
        )
      ),
    )
  end plan

  def render(
      pins: ActionPins,
      schedule: Cron = DefaultSchedule,
      extraSteps: List[Step] = Nil,
      preSteps: List[Step] = Nil,
  ): Either[String, String] =
    checkoutMajor(pins).flatMap(ref => Render.render(plan(ref, schedule, extraSteps, preSteps)))

  private def loadParams: Script =
    Script
      .strict(
        Exec(".", Word.lit("project/zipx-ci.env")),
        setOutput("java-version", Word.v("ZIPX_JAVA_VERSION")),
        setOutput("runner-os", Word.v("ZIPX_RUNNER_OS")),
      )
      .withTrailingNewline(true)

  private def applyScript: Script =
    Script
      .strict(
        Exec(".", Word.lit("project/zipx-ci.env")),
        If(
          ShTest.Empty(Word.Dquote(List(Word.vOrEmpty("ZIPX_CLI_VERSION")))),
          Block(
            Exec(
              "echo",
              Word.quoted(
                "zipx: ZIPX_CLI_VERSION is unset. A release writes it to project/zipx-ci.env; dogfood sets it from zipxVersionUpdatesPreSteps."
              ),
            ),
            Exit(ExitCode.Failure),
          ),
        ),
        Exec(
          "cs",
          Word.lit("launch"),
          Word.lit("--ttl"),
          Word.lit("Inf"),
          Word.lit("--repository"),
          Word.lit("m2Local"),
          Word.lit("--repository"),
          Word.lit("ivy2Local"),
          Word.lit("--repository"),
          Word.lit("central"),
          Word.dquote(Word.lit("rocks.earlyeffect:zipx-cli_3:"), Word.v("ZIPX_CLI_VERSION")),
          Word.lit("--"),
          Word.lit("catalog"),
          Word.lit("update"),
          Word.lit("--yes"),
          Word.lit("--verify-load"),
        ),
        Exec("sbt", Word.quoted("zipxPinUpdate yes")),
        Exec("sbt", Word.lit("zipxCatalogGenerate")),
      )
      .withTrailingNewline(true)

  private inline def setOutput(inline name: String, value: Word.Quotable): Command =
    Exec("echo", Word.dquote(Word.lit(name + "="), value)).appendTo(Word.vq("GITHUB_OUTPUT"))

  private def updatePrScript: Script =
    CompanionPr.open(
      branchPrefix = "zipx/version-updates",
      commitMessage = "ci: zipx version updates",
      prTitle = "ci: zipx version updates",
      prBody = "Applied zipx-cli catalog update and zipxPinUpdate.",
      emptyMessage = "No catalog updates to commit.",
      workflowRegenHint = true,
    )
end VersionUpdatesWorkflow
