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
  */
object CompanionPr:

  inline def open(
      inline branchPrefix: String,
      inline commitMessage: String,
      inline prTitle: String,
      inline prBody: String,
      inline emptyMessage: String,
  ): Script =
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
        Exec(
          "gh",
          Word.lit("pr"),
          Word.lit("create"),
          Word.lit("--title"),
          Word.quoted(prTitle),
          Word.lit("--body"),
          Word.quoted(prBody),
          Word.lit("--head"),
          runBranch(branchPrefix),
          Word.lit("--label"),
          Word.lit("clean"),
        ) || Exec("true"),
      ),
      trailingNewline = true,
    )

  private inline def runBranch(inline prefix: String): Word.Dquote =
    Word.dquote(Word.lit(prefix + "-"), Word.vBraced("GITHUB_RUN_ID"))
end CompanionPr
