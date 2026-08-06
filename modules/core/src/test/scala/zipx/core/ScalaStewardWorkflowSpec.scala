package zipx.core

import neotype.unwrap
import zio.test.*
import zipx.core.Rendered.yaml

/** `.unwrap` on every pin compared against rendered YAML, not decoration: `String.contains` and `String.indexOf` widen
  * their argument to `Any` through `StringOps`, so passing an `ActionRef` compiles and then never matches. An assertion
  * written that way passes for `!out.contains(pin)` and fails with `-1 < -1` for `indexOf`.
  */
object ScalaStewardWorkflowSpec extends ZIOSpecDefault:

  def spec = suite("ScalaStewardWorkflow")(
    test("renders weekly schedule, workflow_dispatch, and pinned steward action") {
      val pins = ActionPins.Defaults
      val out  = ScalaStewardWorkflow.render(pins, "ubuntu-latest").yaml

      val expectedVersionComment =
        pins.versions.get("scalaSteward").map(v => s"# $v").getOrElse("")

      assertTrue(
        out.contains("name: Scala Steward"),
        out.contains("schedule:"),
        out.matches("(?s).*cron:\\s+\"0 0 \\* \\* 0\".*"),
        out.contains("workflow_dispatch: null"),
        out.contains(s"uses: ${pins.scalaSteward.unwrap}"),
        if expectedVersionComment.nonEmpty then out.contains(expectedVersionComment) else true,
        out.contains("contents: write"),
        out.contains("pull-requests: write"),
      )
    },
    test("custom Cron schedule is respected") {
      val out = ScalaStewardWorkflow
        .render(
          ActionPins.Defaults,
          "ubuntu-latest",
          schedule = zipx.workflow.Cron.weekly(zipx.workflow.DayOfWeek.Monday, hour = 6),
        )
        .yaml
      assertTrue(out.matches("(?s).*cron:\\s+\"0 6 \\* \\* 1\".*"))
    },
    test("no configPath renders a single step: no checkout, no with:") {
      val out = ScalaStewardWorkflow.render(ActionPins.Defaults, "ubuntu-latest").yaml
      assertTrue(
        !out.contains("Checkout"),
        !out.contains(ActionPins.Defaults.checkout.unwrap),
        !out.contains("with:"),
        !out.contains("repo-config"),
      )
    },
    test("configPath adds a checkout step ahead of steward and passes repo-config") {
      val pins = ActionPins.Defaults
      val out  =
        ScalaStewardWorkflow.render(pins, "ubuntu-latest", configPath = Some(".github/.scala-steward.conf")).yaml
      assertTrue(
        out.contains(s"uses: ${pins.checkout.unwrap}"),
        out.indexOf(pins.checkout.unwrap) < out.indexOf(pins.scalaSteward.unwrap),
        out.contains("repo-config: .github/.scala-steward.conf"),
      )
    },
  )
end ScalaStewardWorkflowSpec
