package zipx.core

import zipx.shell.*

/** Shared `git commit` plus `gh pr create` for scheduled companions that apply catalog rewrites.
  *
  * `GITHUB_TOKEN` can push catalog files, `plugins.sbt`, and `.github/actions/` composites. It cannot create or update
  * files under `.github/workflows/` (that is a GitHub App restriction, not a `permissions:` key). After `git add -A`,
  * restore that directory to HEAD so the PR never includes workflow YAML. Those files already exist from a human
  * generate; the bot leaves them alone, the same shape as Scala Steward.
  */
object CompanionPr:

  inline def open(
      inline branch: String,
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
        Exec("git", Word.lit("checkout"), Word.lit("-B"), Word.lit(branch)),
        Exec("git", Word.lit("add"), Word.lit("-A")),
        Exec(
          "git",
          Word.lit("restore"),
          Word.lit("--staged"),
          Word.lit("--worktree"),
          Word.lit(".github/workflows"),
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
          Word.lit("pr"),
          Word.lit("create"),
          Word.lit("--title"),
          Word.quoted(prTitle),
          Word.lit("--body"),
          Word.quoted(prBody),
          Word.lit("--head"),
          Word.lit(branch),
        ) || Exec("true"),
      ),
      trailingNewline = true,
    )
end CompanionPr
