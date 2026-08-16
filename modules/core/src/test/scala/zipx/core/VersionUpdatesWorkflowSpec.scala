package zipx.core

import neotype.unwrap
import zipx.core.Rendered.yaml
import zio.test.*

object VersionUpdatesWorkflowSpec extends ZIOSpecDefault:

  def spec = suite("VersionUpdatesWorkflow")(
    test("scheduled companion applies catalog updates, generates, and opens zipx/version-updates") {
      val yaml       = VersionUpdatesWorkflow.render(ActionPins.Defaults, "21", "ubuntu-latest").yaml
      val checkoutAt = yaml.indexOf(ActionPins.Defaults.checkout.unwrap)
      val depAt      = yaml.indexOf("zipxDepUpdate yes")
      val actionAt   = yaml.indexOf("zipxActionUpdate yes")
      val pinAt      = yaml.indexOf("zipxPinUpdate yes")
      val genAt      = yaml.indexOf("zipxWorkflowGenerate", pinAt)
      val prAt       = yaml.indexOf("gh pr create", genAt)
      assertTrue(
        yaml.contains("workflow_dispatch"),
        yaml.contains("cron:") && (yaml.contains("0 0 * * 0") || yaml.contains("\"0 0 * * 0\"")),
        yaml.contains("contents: write") || yaml.contains("contents:write"),
        yaml.contains("pull-requests: write") || yaml.contains("pull-requests:write"),
        yaml.contains("zipx/version-updates"),
        checkoutAt >= 0,
        depAt > checkoutAt,
        actionAt > depAt,
        pinAt > actionAt,
        genAt > pinAt,
        prAt > genAt,
      )
    }
  )
end VersionUpdatesWorkflowSpec
