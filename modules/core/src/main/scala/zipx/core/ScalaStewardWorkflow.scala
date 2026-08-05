package zipx.core

import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Opt-in Scala Steward companion workflow: weekly and manual dependency update PRs, using the default Actions token.
  *
  * Requires the repo or org setting **Allow GitHub Actions to create and approve pull requests**.
  */
object ScalaStewardWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-scala-steward.yml"

  /** Must match the action's own `repo-config` default: a missing file is silently ignored at exactly this path, and
    * fails the run at any other.
    */
  val DefaultConfigPath: String = ".github/.scala-steward.conf"

  val DefaultSchedule: Cron = Cron.weekly(DayOfWeek.Sunday)

  /** @param configPath
    *   passed as the action's `repo-config`, which it reads from the runner filesystem rather than from Steward's own
    *   clone. The action checks out nothing itself, so setting this also adds the checkout step the file needs to
    *   exist.
    */
  def plan(
      pins: ActionPins,
      runnerOs: String,
      schedule: Cron = DefaultSchedule,
      configPath: Option[String] = None,
  ): Workflow =
    val stewardStep = Step(
      name = Some("Scala Steward"),
      uses = Some(pins.scalaSteward),
      `with` = configPath.fold(ListMap.empty[String, String])(path => ListMap("repo-config" -> path)),
    )
    Workflow(
      name = "Scala Steward",
      on = Triggers(schedule = List(schedule), workflowDispatch = true),
      permissions = ListMap("contents" -> "write", "pull-requests" -> "write"),
      jobs = ListMap(
        "scala-steward" -> Job(
          name = Some("Scala Steward"),
          runsOn = List(runnerOs),
          steps = configPath.fold(List(stewardStep)) { _ =>
            List(Step(name = Some("Checkout"), uses = Some(pins.checkout)), stewardStep)
          },
        )
      ),
    )
  end plan

  /** As YAML, with `# vX.Y.Z` comments annotated onto its `uses:` lines. */
  def render(
      pins: ActionPins,
      runnerOs: String,
      schedule: Cron = DefaultSchedule,
      configPath: Option[String] = None,
  ): Either[String, String] =
    Render.render(plan(pins, runnerOs, schedule, configPath)).map(ActionPinFile.annotateUses(_, pins))

end ScalaStewardWorkflow
