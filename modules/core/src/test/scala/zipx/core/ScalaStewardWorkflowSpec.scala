package zipx.core

import zio.test.*

object ScalaStewardWorkflowSpec extends ZIOSpecDefault:

  def spec = suite("ScalaStewardWorkflow")(
    test("renders weekly schedule, workflow_dispatch, and pinned steward action") {
      val pins = ActionPins.Defaults
      val out  = ScalaStewardWorkflow.render(pins, "ubuntu-latest")
      assertTrue(
        out.contains("name: Scala Steward"),
        out.contains("schedule:"),
        out.contains("cron: 0 0 * * 0") || out.contains("""cron: "0 0 * * 0""""),
        out.contains("workflow_dispatch: null"),
        out.contains(s"uses: ${pins.scalaSteward}"),
        out.contains("# v2.92.0") || pins.versions.get("scalaSteward").isEmpty,
        out.contains("contents: write"),
        out.contains("pull-requests: write"),
      )
    },
    test("custom Cron schedule is respected") {
      val out = ScalaStewardWorkflow.render(
        ActionPins.Defaults,
        "ubuntu-latest",
        schedule = zipx.workflow.Cron.weekly(zipx.workflow.DayOfWeek.Monday, hour = 6),
      )
      assertTrue(out.contains("cron: 0 6 * * 1") || out.contains("""cron: "0 6 * * 1""""))
    },
  )
end ScalaStewardWorkflowSpec
