package zipx.core

import zipx.shell.*
import zipx.workflow.StepId

/** The commit-stable namespace in a LocalDir `actions/cache` key: mid-PR pushes keep sharing hits, and a release rolls
  * it. The cases differ in *when* the string is known, at generate time or on the runner.
  */
enum CacheEpoch:
  case Fixed(value: String)

  /** On `refs/tags/v*` the epoch and release are both the tag without its `v`; otherwise the release is the latest
    * matching tag without its `v` and the epoch is `${release}-ci`. Annotates a warning when local tags lag `origin`,
    * or when no tag matches and it falls back to `0.0.0`.
    *
    * @param tagMatch
    *   a [[zipx.shell.SquoteText]] rather than a `String` because the generated script single-quotes it so `v*` reaches
    *   git unglobbed, and a value containing a quote would break out of that quoting. [[CacheEpoch.gitTags]] writes one
    *   as a literal checked while the build compiles.
    */
  case GitTags(tagMatch: SquoteText = CacheEpoch.DefaultTagMatch)

  /** User-supplied shell that must write `epoch=` and `release=` lines to `$GITHUB_OUTPUT`.
    *
    * @param stepId
    *   a [[zipx.workflow.StepId]] rather than a `String` because the planner reads the epoch back out as
    *   `steps.<id>.outputs.epoch`; an id that is not a legal Actions identifier would make that reference unparseable,
    *   and the failure would surface as a broken cache key rather than as a rejected setting.
    */
  case Script(run: String, stepId: StepId = CacheEpoch.GitTagsStepId)
end CacheEpoch

object CacheEpoch:

  val GitTagsStepId: StepId = StepId("cache-epoch")

  val DefaultTagMatch: SquoteText = SquoteText("v*")

  inline def gitTags(inline tagMatch: String): GitTags = GitTags(SquoteText(tagMatch))

  def gitTagsMake(tagMatch: String): Either[String, GitTags] = SquoteText.make(tagMatch).map(GitTags(_))

  def gitTagsResolveScript(tagMatch: SquoteText = DefaultTagMatch): String =
    gitTagsResolveTypedScript(tagMatch).render

  /** The acid test for the shell AST, and the reason it models what it does: one program needing a pipeline, both
    * bracket forms (`[ ]` for the counts, `[[ ]]` for the glob match), `${VAR:-}` and `${VAR#prefix}` expansions, an
    * assignment used as an `elif` condition, per-command stderr suppression, and two `$GITHUB_OUTPUT` writes.
    */
  def gitTagsResolveTypedScript(tagMatch: SquoteText = DefaultTagMatch): zipx.shell.Script =
    val tagMatchWord = Word.Squote(tagMatch)

    // `tr -d ' '` strips the padding `wc` adds on macOS. `InlineCommand` rather than `Command` because the argument
    // becomes a pipeline leg, and only an inline command can be one.
    def countOf(command: InlineCommand): Word =
      Word.subst(command | Exec("wc", Word.lit("-l")) | Exec("tr", Word.lit("-d"), Word.squote(" ")))

    val staleTagWarning = If(
      ShTest.Cmd(Exec("git", Word.lit("remote"), Word.lit("get-url"), Word.lit("origin")).silenced),
      Block(
        Assign(
          "remote_count",
          countOf(
            Exec(
              "git",
              Word.lit("ls-remote"),
              Word.lit("--tags"),
              Word.lit("--refs"),
              Word.lit("origin"),
              Word.vq("tag_match"),
            ).stderrSilenced
          ),
        ),
        Assign("local_count", countOf(Exec("git", Word.lit("tag"), Word.lit("-l"), Word.vq("tag_match")))),
        If(
          ShTest.IntGt(Word.vq("remote_count"), Word.lit("0")) &&
            ShTest.IntLt(Word.vq("local_count"), Word.vq("remote_count")),
          Block(
            warn(
              Word.lit("Local tags ("),
              Word.v("local_count"),
              Word.lit(") fewer than origin ("),
              Word.v("remote_count"),
              Word.lit("). Epoch may be stale; checkout must use fetch-depth: 0 and fetch-tags: true."),
            )
          ),
        ),
      ),
    )

    val resolve = If(
      // `[[ ]]` with an unquoted pattern, so `refs/tags/v*` globs. In `[ ]` it would compare against the literal text.
      ShTest.GlobMatch(Word.dquote(Word.vOrEmpty("GITHUB_REF")), GlobPattern("refs/tags/v*")),
      Block(
        Assign("release", Word.dquote(Word.vStrip("GITHUB_REF", "refs/tags/v"))),
        Assign("epoch", Word.vq("release")),
      ),
      elifs = List(
        // `elif tag=$(…); then` branches on git describe's exit status *and* keeps its output, which is why `ShTest.Cmd`
        // takes a whole `Command` rather than a program name.
        ShTest.Cmd(
          Assign(
            "tag",
            Word.subst(
              Exec(
                "git",
                Word.lit("describe"),
                Word.lit("--tags"),
                Word.lit("--abbrev=0"),
                Word.lit("--match"),
                Word.vq("tag_match"),
              ).stderrSilenced
            ),
          )
        ) ->
          Block(
            Assign("release", Word.dquote(Word.vStrip("tag", "v"))),
            // `${release}` braced: a bare `$release-ci` would be read as the variable `release-ci`.
            Assign("epoch", Word.dquote(Word.vBraced("release"), Word.lit("-ci"))),
          )
      ),
      elseDo = Some(
        Block(
          warn(
            Word.lit("No tags matching '"),
            Word.v("tag_match"),
            Word.lit("' found; using epoch 0.0.0."),
          ),
          Assign("release", Word.quoted("0.0.0")),
          Assign("epoch", Word.quoted("0.0.0")),
        )
      ),
    )

    zipx.shell.Script(
      List(
        SetOpts(),
        Assign("tag_match", tagMatchWord),
        staleTagWarning,
        resolve,
        setOutput("epoch", Word.v("epoch")),
        setOutput("release", Word.v("release")),
      ),
      // Emits a blank line after the last write, which the pre-DSL string did. Kept for byte parity.
      trailingNewline = true,
    )
  end gitTagsResolveTypedScript

  private inline def setOutput(inline name: String, value: Word.Quotable): Command =
    Exec("echo", Word.dquote(Word.lit(name + "="), value)).appendTo(Word.vq("GITHUB_OUTPUT"))

  /** An Actions annotation is a `::warning` line on stdout, not an API call. */
  private def warn(message: Word.Quotable*): Command =
    Exec("echo", Word.dquote(Word.lit("::warning title=zipx cache epoch::") :: message.toList*))

end CacheEpoch
