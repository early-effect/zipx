package zipx.core

import neotype.unwrap
import zipx.core.Rendered.yaml
import zio.test.*

object CompanionAuthSpec extends ZIOSpecDefault:

  def spec = suite("CompanionAuth")(
    test("detect, mint, and export sit in that order; mint is skipped unless both secrets are set") {
      val steps     = CompanionAuth.steps
      val detect    = steps.head
      val mint      = steps(1)
      val exported  = steps(2)
      val detectRun = detect.run.getOrElse("")
      val exportRun = exported.run.getOrElse("")
      val mintIf    = mint.`if`.getOrElse("")
      val exportIf  = exported.`if`.getOrElse("")
      val token     = CompanionAuth.checkoutWith("token")
      assertTrue(
        steps.length == 3,
        detect.id.contains(CompanionAuth.DetectId.unwrap),
        mint.id.contains(CompanionAuth.TokenStepId.unwrap),
        mint.uses.contains(CompanionAuth.AppTokenRef),
        mintIf == CompanionAuth.present.unwrapped,
        exportIf == mintIf,
        !mintIf.contains("secrets."),
        detectRun.contains("present=true"),
        detectRun.contains("present=false"),
        detectRun.contains("zipx: ZIPX_APP_ID and ZIPX_APP_PRIVATE_KEY must both be set, or neither."),
        detect.env.contains(CompanionAuth.AppId.env.unwrap),
        detect.env.contains(CompanionAuth.AppKey.env.unwrap),
        exportRun.contains("GITHUB_TOKEN="),
        exportRun.contains("GH_TOKEN="),
        exportRun.contains("GITHUB_ENV"),
        token.contains("steps.zipx-app-token.outputs.token"),
        token.contains("secrets.GITHUB_TOKEN"),
        CompanionAuth.checkoutWith("persist-credentials") == "true",
      )
    },
    test("xor of App secrets is a zipx error in the detect script, not a silent GITHUB_TOKEN fallback") {
      val detect = CompanionAuth.steps.head.run.getOrElse("")
      val both   = detect.indexOf("[ -n \"$ZIPX_APP_ID\" ] && [ -n \"$ZIPX_APP_PRIVATE_KEY\" ]")
      val xor    = detect.indexOf("[ -n \"$ZIPX_APP_ID\" ] || [ -n \"$ZIPX_APP_PRIVATE_KEY\" ]")
      val err    = detect.indexOf("must both be set, or neither.")
      val fail   = detect.indexOf("exit 1", err)
      assertTrue(both >= 0, xor > both, err > xor, fail > err)
    },
    test("version-updates companion mints before checkout and searches m2Local") {
      val yaml       = VersionUpdatesWorkflow.render(ActionPins.Defaults).yaml
      val detectAt   = yaml.indexOf("Detect GitHub App credentials")
      val mintAt     = yaml.indexOf("Mint GitHub App token")
      val exportAt   = yaml.indexOf("Export GitHub App token")
      val checkoutAt = yaml.indexOf("actions/checkout@v7")
      val csAt       = yaml.indexOf("cs launch")
      assertTrue(
        detectAt >= 0,
        mintAt > detectAt,
        exportAt > mintAt,
        checkoutAt > exportAt,
        yaml.contains("actions/create-github-app-token@v3"),
        yaml.contains("steps.zipx-app-token.outputs.token || secrets.GITHUB_TOKEN") ||
          yaml.contains("steps.zipx-app-token.outputs.token || secrets.GITHUB_TOKEN".replace(" ", "")),
        yaml.contains("--repository m2Local"),
        yaml.contains("--repository ivy2Local"),
        yaml.contains("--repository central"),
        yaml.contains("secrets.GITHUB_TOKEN"),
        yaml.contains("ZIPX_APP_ID"),
        yaml.contains("ZIPX_APP_PRIVATE_KEY"),
        csAt > checkoutAt,
      )
    },
  )
end CompanionAuthSpec
