package zipx.core

import neotype.unwrap
import zipx.core.Rendered.yaml
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{ActionRef, Step}
import zio.test.*

object VersionUpdatesWorkflowSpec extends ZIOSpecDefault:

  def spec = suite("VersionUpdatesWorkflow")(
    test("zipx-ci.env is two assignments under the generated header") {
      val text = ZipxCiParams.render("25", "ubuntu-latest")
      assertTrue(
        text.startsWith(ZipxCatalog.GeneratedHeader),
        text.contains("ZIPX_JAVA_VERSION=25"),
        text.contains("ZIPX_RUNNER_OS=ubuntu-latest"),
        !text.contains("ZIPX_CLI_VERSION"),
      )
    },
    test("zipx-ci.env includes ZIPX_CLI_VERSION when generate knows the jar") {
      val text = ZipxCiParams.render("25", "ubuntu-latest", cliVersion = "0.7.0")
      assertTrue(text.contains("ZIPX_CLI_VERSION=0.7.0"))
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
      val csAt       = yaml.indexOf("cs launch")
      val pinAt      = yaml.indexOf("zipxPinUpdate yes")
      val genAt      = yaml.indexOf("zipxCatalogGenerate", pinAt)
      val applyAt    = yaml.indexOf("Apply catalog updates")
      val openAt     = yaml.indexOf("Open update PR")
      val addAt      = yaml.indexOf(":!.github/workflows")
      val hintAt     = yaml.indexOf("sbt zipxWorkflowGenerate", openAt)
      val prAt       = yaml.indexOf("gh pr create", hintAt)
      assertTrue(
        yaml.contains("workflow_dispatch"),
        yaml.contains("cron:") && (yaml.contains("0 0 * * 0") || yaml.contains("\"0 0 * * 0\"")),
        yaml.contains("contents: write") || yaml.contains("contents:write"),
        yaml.contains("pull-requests: write") || yaml.contains("pull-requests:write"),
        yaml.contains("issues: write") || yaml.contains("issues:write"),
        !yaml.contains("git add -A"),
        yaml.contains("git add --all"),
        yaml.contains("sbt zipxCatalogGenerate"),
        yaml.contains("--body-file"),
        yaml.contains("git fetch origin zipx/version-updates-${GITHUB_RUN_ID}"),
        yaml.contains("git checkout zipx/version-updates-${GITHUB_RUN_ID}"),
        yaml.contains("git add .github/workflows"),
        yaml.contains("git push origin zipx/version-updates-${GITHUB_RUN_ID}"),
        // Apply runs catalog generate only. The workflow-generate string after Open update PR is the PR-body hint
        // (the generated-file header is the other mention), never a companion apply step.
        hintAt > openAt,
        !yaml.contains(ActionPins.Defaults.checkout.unwrap),
        yaml.contains("zipx/version-updates-${GITHUB_RUN_ID}"),
        yaml.contains("gh label create"),
        yaml.contains("--label clean"),
        yaml.contains("steps.zipx-ci.outputs.java-version"),
        yaml.contains("steps.zipx-ci.outputs.runner-os"),
        yaml.contains("coursier: \"true\"") || yaml.contains("coursier: true"),
        yaml.contains("catalog update"),
        yaml.contains("--verify-load"),
        yaml.contains("rocks.earlyeffect:zipx-cli_3:"),
        !yaml.contains("zipxDepUpdate yes"),
        !yaml.contains("zipxActionUpdate yes"),
        checkoutAt >= 0,
        loadAt > checkoutAt,
        setupAt > loadAt,
        csAt > setupAt,
        pinAt > csAt,
        genAt > pinAt,
        applyAt >= 0,
        openAt > applyAt,
        addAt > openAt,
        hintAt > addAt,
        prAt > hintAt,
      )
    },
    test("extra steps run after catalog generate and can regenerate a nested example") {
      val extra = List(
        Step
          .run(Script.strict(Exec("echo", Word.quoted("nested"))))
          .named("Generate example workflow")
          .in("examples/monorepo")
          .build
      )
      val yaml    = VersionUpdatesWorkflow.render(ActionPins.Defaults, extraSteps = extra).yaml
      val applyAt = yaml.indexOf("Apply catalog updates")
      val extraAt = yaml.indexOf("Generate example workflow")
      val openAt  = yaml.indexOf("Open update PR")
      assertTrue(
        extraAt > applyAt,
        extraAt < openAt,
        yaml.contains("working-directory: examples/monorepo") || yaml.contains("working-directory:"),
        yaml.contains("zipxCatalogGenerate"),
      )
    },
  )
end VersionUpdatesWorkflowSpec
