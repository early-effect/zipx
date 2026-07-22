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

  /** Sunday 00:00 UTC — matches Steward action docs. */
  val DefaultSchedule: Cron = Cron.weekly(DayOfWeek.Sunday)

  def plan(
      pins: ActionPins,
      runnerOs: String,
      schedule: Cron = DefaultSchedule,
  ): Workflow =
    Workflow(
      name = "Scala Steward",
      on = Triggers(schedule = List(schedule), workflowDispatch = true),
      permissions = ListMap("contents" -> "write", "pull-requests" -> "write"),
      jobs = ListMap(
        "scala-steward" -> Job(
          name = Some("Scala Steward"),
          runsOn = List(runnerOs),
          steps = List(
            Step(
              name = Some("Scala Steward"),
              uses = Some(pins.scalaSteward),
            )
          ),
        )
      ),
    )

  def render(
      pins: ActionPins,
      runnerOs: String,
      schedule: Cron = DefaultSchedule,
  ): String =
    ActionPinFile.annotateUses(Render.render(plan(pins, runnerOs, schedule)), pins)

end ScalaStewardWorkflow
