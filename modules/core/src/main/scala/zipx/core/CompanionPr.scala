package zipx.core

import zipx.shell.*

/** Shared `git commit` plus `gh pr create` for scheduled companions that apply catalog rewrites.
  *
  * `GITHUB_TOKEN` can push `project/` and `.github/actions/` (composites). It cannot create or update
  * `.github/workflows/` files: GitHub App tokens need a `workflows` git permission that `permissions:` cannot grant.
  * Scala Steward hits the same reject if an update rewrites a workflow file. Stage everything except
  * `.github/workflows`; a human generate already committed the companion YAML.
  *
  * The branch is `$prefix-$GITHUB_RUN_ID` so a second dispatch cannot force-push an open PR. The PR is labeled
  * [[PlanConfig.DefaultVerifyCleanLabel]] so Verify runs `cleanFull`.
  *
  * Version-updates PRs set [[open]] `workflowRegenHint`: the body names this run's branch and the exact `sbt` / `git`
  * commands to regenerate `ci.yml` onto that branch. The companion never runs `zipxWorkflowGenerate` itself.
  */
object CompanionPr:

  inline def open(
      inline branchPrefix: String,
      inline commitMessage: String,
      inline prTitle: String,
      inline prBody: String,
      inline emptyMessage: String,
      inline workflowRegenHint: Boolean = false,
  ): Script =
    val bodyFile = Word.lit("/tmp/zipx-pr-body.md")
    Script(
      List(
        If(
          ShTest.Empty(Word.dquote(Word.subst(Exec("git", Word.lit("status"), Word.lit("--porcelain"))))),
          Block(
            Exec("echo", Word.quoted(emptyMessage)),
            Exit(),
          ),
        ),
        Exec("git", Word.lit("config"), Word.lit("user.name"), Word.quoted("github-actions[bot]")),
        Exec(
          "git",
          Word.lit("config"),
          Word.lit("user.email"),
          Word.quoted("41898282+github-actions[bot]@users.noreply.github.com"),
        ),
        Exec("git", Word.lit("checkout"), Word.lit("-B"), runBranch(branchPrefix)),
        Exec(
          "git",
          Word.lit("add"),
          Word.lit("--all"),
          Word.lit("--"),
          Word.lit("."),
          Word.quoted(":!.github/workflows"),
        ),
        If(
          ShTest.Empty(
            Word.dquote(Word.subst(Exec("git", Word.lit("diff"), Word.lit("--cached"), Word.lit("--name-only"))))
          ),
          Block(
            Exec("echo", Word.quoted(emptyMessage)),
            Exit(),
          ),
        ),
        Exec("git", Word.lit("commit"), Word.lit("-m"), Word.quoted(commitMessage)),
        Exec("git", Word.lit("push"), Word.lit("-u"), Word.lit("origin"), Word.lit("HEAD")),
        Exec(
          "gh",
          Word.lit("label"),
          Word.lit("create"),
          Word.lit("clean"),
          Word.lit("--force"),
          Word.lit("--description"),
          Word.quoted("zipx: Verify runs cleanFull"),
        ) || Exec("true"),
        Heredoc(
          Exec("cat").writeTo(bodyFile),
          HeredocTag("EOF"),
          ScriptLine(prBody) :: (if workflowRegenHint then workflowRegenLines(branchPrefix) else Nil),
          quoted = !workflowRegenHint,
        ),
        Exec(
          "gh",
          Word.lit("pr"),
          Word.lit("create"),
          Word.lit("--title"),
          Word.quoted(prTitle),
          Word.lit("--body-file"),
          bodyFile,
          Word.lit("--head"),
          runBranch(branchPrefix),
          Word.lit("--label"),
          Word.lit("clean"),
        ) || Exec("true"),
      ),
      trailingNewline = true,
    )
  end open

  /** Copy-paste recipe for a reviewer or agent. Unquoted heredoc expands `GITHUB_RUN_ID` so the PR body names this
    * run's branch. No backticks: those would run as command substitution when the companion writes the file.
    */
  private inline def workflowRegenLines(inline prefix: String): List[ScriptLine] =
    List(
      ScriptLine.empty,
      ScriptLine("## Repo-root workflow YAML is not in this PR"),
      ScriptLine.empty,
      ScriptLine(
        "The bot cannot push repo-root .github/workflows/ (GITHUB_TOKEN has no workflows permission). Composites under .github/actions/ are already in this commit. Nested example YAML (examples/monorepo/.github/, including that tree's ci.yml) is also in this commit when the companion regenerated it."
      ),
      ScriptLine.empty,
      ScriptLine(
        "If zipxWorkflowCheck fails on repo-root ci.yml (typical after an Action pin bump, especially checkout), regenerate workflows on this PR branch and push. Do not commit those files to main."
      ),
      ScriptLine.empty,
      ScriptLine("From a clone of this repo:"),
      ScriptLine.empty,
      ScriptLine("    git fetch origin " + prefix + "-${GITHUB_RUN_ID}"),
      ScriptLine("    git checkout " + prefix + "-${GITHUB_RUN_ID}"),
      ScriptLine("    sbt zipxWorkflowGenerate"),
      ScriptLine("    git add .github/workflows"),
      ScriptLine("""    git commit -m "ci: regenerate workflows""""),
      ScriptLine("    git push origin " + prefix + "-${GITHUB_RUN_ID}"),
      ScriptLine.empty,
      ScriptLine("The PR branch is " + prefix + "-${GITHUB_RUN_ID}."),
    )
  end workflowRegenLines

  private inline def runBranch(inline prefix: String): Word.Dquote =
    Word.dquote(Word.lit(prefix + "-"), Word.vBraced("GITHUB_RUN_ID"))
end CompanionPr
