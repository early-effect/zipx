package zipx.core

import zipx.shell.*

/** How LocalDir `actions/cache` keys choose their commit-stable "epoch" namespace.
  *
  * The epoch keeps mid-PR pushes sharing hits and rolls when a release lands. Strategies differ in *when* the epoch
  * string is known: bake at `zipxWorkflowGenerate` time ([[CacheEpoch.Fixed]]), or resolve on the runner
  * ([[CacheEpoch.GitTags]], [[CacheEpoch.Script]]).
  */
enum CacheEpoch:
  /** Bake `value` into the workflow YAML at generate time (previous default via root `version`). */
  case Fixed(value: String)

  /** Resolve epoch/release from git tags on the runner (default).
    *
    * On `refs/tags/v*`: epoch = release = tag without the leading `v`. Otherwise: latest matching tag → release without
    * `v`, epoch = `${release}-ci`. Warns via Actions annotations when local tags lag `origin`, or when no matching tags
    * exist (falls back to `0.0.0`).
    *
    * @param tagMatch
    *   glob passed to `git describe --match` / local tag listing (default `v*`). A [[zipx.shell.SquoteText]] rather
    *   than a `String` because the generated script single-quotes it, so that `v*` reaches git unglobbed: a value
    *   containing a quote would break out of the quoting, and typing the field is what keeps that from being a
    *   render-time check. Use [[CacheEpoch.gitTags]] to write one as a literal, which is checked while the build
    *   compiles.
    */
  case GitTags(tagMatch: SquoteText = CacheEpoch.DefaultTagMatch)

  /** User-supplied shell that must write `epoch=` and `release=` lines to `$GITHUB_OUTPUT`. */
  case Script(run: String, stepId: String = "cache-epoch")
end CacheEpoch

object CacheEpoch:

  /** Step id used by [[CacheEpoch.GitTags]]. */
  val GitTagsStepId: String = "cache-epoch"

  /** The default `git describe --match` glob: every `v`-prefixed tag. */
  val DefaultTagMatch: SquoteText = SquoteText("v*")

  /** [[GitTags]] with a literal glob, checked at compile time. */
  inline def gitTags(inline tagMatch: String): GitTags = GitTags(SquoteText(tagMatch))

  /** [[GitTags]] from a glob only known at runtime; `Left` names why the glob cannot be single-quoted. */
  def gitTagsMake(tagMatch: String): Either[String, GitTags] = SquoteText.make(tagMatch).map(GitTags(_))

  /** Shell for [[CacheEpoch.GitTags]]: sets `epoch` / `release` outputs; warns on missing or lagging tags. */
  def gitTagsResolveScript(tagMatch: SquoteText = DefaultTagMatch): String =
    gitTagsResolveTypedScript(tagMatch).render

  /** [[gitTagsResolveScript]] as a typed script.
    *
    * The acid test for the shell AST, and the reason it models what it does: this one program needs a pipeline, both
    * bracket forms (`[ ]` for the counts, `[[ ]]` for the glob match), `${VAR:-}` and `${VAR#prefix}` expansions, an
    * assignment used as an `elif` condition, per-command stderr suppression, and two `$GITHUB_OUTPUT` writes. It was
    * previously an s-interpolated string where every shell `$` had to be written `$$`.
    */
  def gitTagsResolveTypedScript(tagMatch: SquoteText = DefaultTagMatch): zipx.shell.Script =
    val tagMatchWord = Word.Squote(tagMatch)

    // `$(… | wc -l | tr -d ' ')`: count matching refs, with the count stripped of the padding `wc` adds on macOS.
    // `InlineCommand` rather than `Command` because the argument becomes a pipeline leg, and only an inline command can.
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
        // An assignment as a condition: `elif tag=$(…); then` branches on git describe's exit status *and* keeps its
        // output. That is why `ShTest.Cmd` takes a whole `Command` rather than just a program name.
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
      // The pre-DSL string ended with a newline, so the block scalar emits a blank line after the last write. Byte
      // parity means keeping that rather than tidying it.
      trailingNewline = true,
    )
  end gitTagsResolveTypedScript

  /** `echo "name=value" >> "$GITHUB_OUTPUT"`: publish a step output. */
  private inline def setOutput(inline name: String, value: Word.Quotable): Command =
    Exec("echo", Word.dquote(Word.lit(name + "="), value)).appendTo(Word.vq("GITHUB_OUTPUT"))

  /** An Actions warning annotation, which is a `::warning` line on stdout rather than an API call. */
  private def warn(message: Word.Quotable*): Command =
    Exec("echo", Word.dquote(Word.lit("::warning title=zipx cache epoch::") :: message.toList*))

end CacheEpoch
