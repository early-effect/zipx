package zipx.core

import zio.test.*

object ScalaStewardWorkflowSpec extends ZIOSpecDefault:

  def spec = suite("ScalaStewardWorkflow")(
    test("renders weekly schedule, workflow_dispatch, and pinned steward action") {
      val pins = ActionPins.Defaults
      val out  = ScalaStewardWorkflow.render(pins, "ubuntu-latest")

      // Version comment is dynamic: annotateUses appends "# <version>" from pins.versions
      val expectedVersionComment =
        pins.versions.get("scalaSteward").map(v => s"# $v").getOrElse("")

      assertTrue(
        out.contains("name: Scala Steward"),
        out.contains("schedule:"),
        out.matches("(?s).*cron:\\s+\"0 0 \\* \\* 0\".*"),
        out.contains("workflow_dispatch: null"),
        out.contains(s"uses: ${pins.scalaSteward}"),
        if expectedVersionComment.nonEmpty then out.contains(expectedVersionComment) else true,
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
      assertTrue(out.matches("(?s).*cron:\\s+\"0 6 \\* \\* 1\".*"))
    },
    test("no configPath renders a single step: no checkout, no with:") {
      val out = ScalaStewardWorkflow.render(ActionPins.Defaults, "ubuntu-latest")
      assertTrue(
        !out.contains("Checkout"),
        !out.contains(ActionPins.Defaults.checkout),
        !out.contains("with:"),
        !out.contains("repo-config"),
      )
    },
    test("configPath adds a checkout step ahead of steward and passes repo-config") {
      val pins = ActionPins.Defaults
      val out  = ScalaStewardWorkflow.render(pins, "ubuntu-latest", configPath = Some(".github/.scala-steward.conf"))
      // The action reads repo-config off the runner filesystem and never checks out itself,
      // so checkout must come first or the config is silently ignored.
      assertTrue(
        out.contains(s"uses: ${pins.checkout}"),
        out.indexOf(pins.checkout) < out.indexOf(pins.scalaSteward),
        out.contains("repo-config: .github/.scala-steward.conf"),
      )
    },
  )
end ScalaStewardWorkflowSpec
