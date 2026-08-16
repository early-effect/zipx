package zipx.core

import zio.test.*

object CompanionPrSpec extends ZIOSpecDefault:

  def spec = suite("CompanionPr")(
    test("version-updates PR body names the PR branch and the regen commands") {
      val script = CompanionPr
        .open(
          branchPrefix = "zipx/version-updates",
          commitMessage = "ci: zipx version updates",
          prTitle = "ci: zipx version updates",
          prBody = "Applied zipxDepUpdate, zipxActionUpdate, and zipxPinUpdate.",
          emptyMessage = "No catalog updates to commit.",
          workflowRegenHint = true,
        )
        .render
      val branch = "zipx/version-updates-${GITHUB_RUN_ID}"
      assertTrue(
        script.contains("--body-file"),
        script.contains("sbt zipxWorkflowGenerate"),
        script.contains(s"git fetch origin $branch"),
        script.contains(s"git checkout $branch"),
        script.contains("git add .github/workflows"),
        script.contains("""git commit -m "ci: regenerate workflows""""),
        script.contains(s"git push origin $branch"),
        script.contains("The PR branch is " + branch),
        !script.contains("sbt zipxWorkflowGenerate yes"),
        script.contains("Repo-root workflow YAML is not in this PR"),
      )
    },
    test("pin-updates PR body has no workflow-regen recipe") {
      val script = CompanionPr
        .open(
          branchPrefix = "zipx/pin-updates",
          commitMessage = "ci: apply zipx pin feed updates",
          prTitle = "ci: zipx pin feed updates",
          prBody = "Applied pin feed Update policy.",
          emptyMessage = "No pin updates to commit.",
        )
        .render
      assertTrue(
        script.contains("--body-file"),
        script.contains("Applied pin feed Update policy."),
        !script.contains("sbt zipxWorkflowGenerate"),
        !script.contains("Repo-root workflow YAML is not in this PR"),
      )
    },
  )
end CompanionPrSpec
