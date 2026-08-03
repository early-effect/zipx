package zipx.core

import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Opt-in Scala Steward companion workflow: weekly (plus manual) dependency update PRs via
  * `scala-steward-org/scala-steward-action` with the default GitHub Actions token.
  *
  * Requires the repo/org setting **Allow GitHub Actions to create and approve pull requests**.
  */
object ScalaStewardWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-scala-steward.yml"

  /** Where the generated Steward defaults config goes. Must match the action's own `repo-config` default: when the file
    * is missing the action silently ignores it *only* at that exact path, and fails loudly at any other.
    */
  val DefaultConfigPath: String = ".github/.scala-steward.conf"

  /** Sunday 00:00 UTC, matching Steward action docs. */
  val DefaultSchedule: Cron = Cron.weekly(DayOfWeek.Sunday)

  /** @param configPath
    *   when set, check the repo out and pass this path as the action's `repo-config`. The action reads that file from
    *   the runner filesystem, not from Steward's own clone, and it does not check out anything itself, so the checkout
    *   step is required for the config to be seen at all.
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

  def render(
      pins: ActionPins,
      runnerOs: String,
      schedule: Cron = DefaultSchedule,
      configPath: Option[String] = None,
  ): String =
    ActionPinFile.annotateUses(Render.render(plan(pins, runnerOs, schedule, configPath)), pins)

end ScalaStewardWorkflow
