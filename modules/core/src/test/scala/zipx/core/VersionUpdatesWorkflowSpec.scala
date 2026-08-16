package zipx.core

import neotype.unwrap
import zipx.core.Rendered.yaml
import zipx.workflow.ActionRef
import zio.test.*

object VersionUpdatesWorkflowSpec extends ZIOSpecDefault:

  def spec = suite("VersionUpdatesWorkflow")(
    test("zipx-ci.env is two assignments under the generated header") {
      val text = ZipxCiParams.render("25", "ubuntu-latest")
      assertTrue(
        text.startsWith(ZipxCatalog.GeneratedHeader),
        text.contains("ZIPX_JAVA_VERSION=25"),
        text.contains("ZIPX_RUNNER_OS=ubuntu-latest"),
      )
    },
    test("checkout major is the catalog label's vN, not the SHA") {
      assertTrue(
        VersionUpdatesWorkflow.checkoutMajor(ActionPins.Defaults) == Right(ActionRef("actions/checkout@v7"))
      )
    },
    test("scheduled companion applies catalog outputs without rewriting workflow YAML") {
      val yaml       = VersionUpdatesWorkflow.render(ActionPins.Defaults).yaml
      val checkoutAt = yaml.indexOf("actions/checkout@v7")
      val loadAt     = yaml.indexOf("project/zipx-ci.env")
      val setupAt    = yaml.indexOf("./.github/actions/zipx-sbt-setup")
      val depAt      = yaml.indexOf("zipxDepUpdate yes")
      val actionAt   = yaml.indexOf("zipxActionUpdate yes")
      val pinAt      = yaml.indexOf("zipxPinUpdate yes")
      val genAt      = yaml.indexOf("zipxCatalogGenerate", pinAt)
      val addAt      = yaml.indexOf(":!.github/workflows")
      val prAt       = yaml.indexOf("gh pr create", addAt)
      assertTrue(
        yaml.contains("workflow_dispatch"),
        yaml.contains("cron:") && (yaml.contains("0 0 * * 0") || yaml.contains("\"0 0 * * 0\"")),
        yaml.contains("contents: write") || yaml.contains("contents:write"),
        yaml.contains("pull-requests: write") || yaml.contains("pull-requests:write"),
        yaml.contains("issues: write") || yaml.contains("issues:write"),
        !yaml.contains("git add -A"),
        yaml.contains("git add --all"),
        yaml.contains("sbt zipxCatalogGenerate"),
        yaml.indexOf("zipxWorkflowGenerate") == yaml.lastIndexOf("zipxWorkflowGenerate"),
        !yaml.contains(ActionPins.Defaults.checkout.unwrap),
        yaml.contains("zipx/version-updates-${GITHUB_RUN_ID}"),
        yaml.contains("gh label create"),
        yaml.contains("--label clean"),
        yaml.contains("steps.zipx-ci.outputs.java-version"),
        yaml.contains("steps.zipx-ci.outputs.runner-os"),
        checkoutAt >= 0,
        loadAt > checkoutAt,
        setupAt > loadAt,
        depAt > setupAt,
        actionAt > depAt,
        pinAt > actionAt,
        genAt > pinAt,
        addAt > genAt,
        prAt > addAt,
      )
    },
  )
end VersionUpdatesWorkflowSpec
