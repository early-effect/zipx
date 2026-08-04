package zipx.workflow

import neotype.*

// GitHub Actions syntax rules as types. These are GitHub's rules, which is why they live here rather than in the
// deliberately GHA-agnostic zipx-shell.
//
// Same compile-time contract as zipx.shell: `StepId("check")` validates the literal during compilation, a runtime
// string goes through `make` (Either) or `makeOrThrow`. Validators use only what neotype can evaluate at compile
// time, so character classes are `matches` against `inline val` patterns rather than lambdas or compiled Regexes.

/** A `jobs.<job_id>` key. GitHub: "must start with a letter or `_` and contain only alphanumeric characters, `-`, or
  * `_`". A digit-leading id is a hard workflow parse error, not a warning.
  *
  * Uniqueness is a property of the *collection*, so it is checked where the job map is assembled, not here.
  */
type JobId = JobId.Type
object JobId extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a job id must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else s"invalid job id '$input': must start with a letter or _ and contain only letters, digits, - or _"

/** A `steps[*].id`, referenced by `steps.<id>.outputs.<name>`. Same shape as a [[JobId]]. */
type StepId = StepId.Type
object StepId extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a step id must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else s"invalid step id '$input': must start with a letter or _ and contain only letters, digits, - or _"

/** A secret name for `secrets.<name>`.
  *
  * GitHub's rules for what you may *create*: alphanumerics and `_` only, must not start with a number, and must not
  * start with the reserved `GITHUB_` prefix. Names are stored uppercase and matched case-insensitively, so the prefix
  * check has to be case-insensitive too: `github_token` is the same name as `GITHUB_TOKEN`.
  *
  * `GITHUB_TOKEN` itself is accepted, and only it. The token is not a secret you create, it is injected into every
  * workflow and `secrets.GITHUB_TOKEN` is the documented way to read it.
  */
type SecretName = SecretName.Type
object SecretName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a secret name must be non-empty"
    else if input.matches(Names.GithubToken) then true
    else if !input.matches(Names.SecretName) then
      s"invalid secret name '$input': allowed characters are letters, digits and _, and it must not start with a digit"
    else if input.matches(Names.GithubPrefixed) then
      s"invalid secret name '$input': the GITHUB_ prefix is reserved by GitHub (only GITHUB_TOKEN itself is readable)"
    else true

/** An environment or `vars.` name for `env.<name>` / `vars.<name>`.
  *
  * The shape is exactly `zipx.shell.VarName`'s, and deliberately so: an `env:` key becomes a shell variable in every
  * `run:` step, so the two layers must agree on what a name is. The pattern is shared rather than restated, which is
  * what keeps them from drifting.
  *
  * The `GITHUB_` prefix is reserved here as well, for the same reason it is on secrets.
  */
type EnvName = EnvName.Type
object EnvName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an env name must be non-empty"
    else if !input.matches(zipx.shell.Patterns.Ident) then
      s"invalid env name '$input': must start with a letter or _ and contain only letters, digits and _"
    else if input.matches(Names.GithubPrefixed) then
      s"invalid env name '$input': the GITHUB_ prefix is reserved for GitHub's default variables"
    else true

/** A step or job output name for `steps.<id>.outputs.<name>` / `needs.<id>.outputs.<name>`.
  *
  * Also rejects the two deprecated workflow-command spellings (`set-output`, `save-state`), which GitHub disabled: a
  * name shaped like one means a caller is porting an old script and expecting behaviour that no longer exists.
  */
type OutputName = OutputName.Type
object OutputName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an output name must be non-empty"
    else if input.matches(Names.Deprecated) then
      s"'$input' is a deprecated workflow command, not an output name: write to \\$$GITHUB_OUTPUT instead"
    else if input.matches(Names.ActionsId) then true
    else s"invalid output name '$input': must start with a letter or _ and contain only letters, digits, - or _"

/** A matrix axis name for `matrix.<axis>`.
  *
  * `include` and `exclude` are rejected: they are matrix *directives* that add and remove combinations, so a
  * `matrix.include` reference does not mean what it reads like.
  */
type MatrixAxis = MatrixAxis.Type
object MatrixAxis extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a matrix axis must be non-empty"
    else if input == "include" || input == "exclude" then
      s"'$input' is a matrix directive, not an axis: it adds or removes combinations rather than naming one"
    else if input.matches(Names.ActionsId) then true
    else s"invalid matrix axis '$input': must start with a letter or _ and contain only letters, digits, - or _"

/** A dotted context path, the part after the context name: the `event.pull_request.base.sha` of
  * `github.event.pull_request.base.sha`.
  *
  * Segments are identifiers, joined by `.`, with two extra forms GitHub's expression syntax allows: a `[n]` array index
  * and a `*` wildcard, so `event.pull_request.labels.*.name` is expressible. An empty segment (`github..sha`) or an
  * unbalanced bracket is rejected, since both render as an expression GitHub fails to parse.
  */
type ContextPath = ContextPath.Type
object ContextPath extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a context path must be non-empty"
    else if input.matches(Names.ContextPath) then true
    else
      s"invalid context path '$input': expected dotted identifiers with optional [n] index or * wildcard, as in event.pull_request.base.sha"

/** A `uses:` value: the three forms GitHub accepts and nothing else.
  *
  *   - `owner/repo@ref` or `owner/repo/path@ref`, a published action or reusable workflow
  *   - `./path`, an action in this repository
  *   - `docker://image`, a container action
  *
  * A bare `owner/repo` with no `@ref` is rejected. GitHub requires the ref, and an unpinned action is precisely what
  * `ActionPinFile` exists to prevent, so the type refuses to express it.
  */
type ActionRef = ActionRef.Type
object ActionRef extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a uses: value must be non-empty"
    else if input.matches(Names.LocalAction) || input.matches(Names.DockerAction) then true
    else if input.matches(Names.RemoteAction) then true
    else if input.matches(Names.UnpinnedAction) then
      s"invalid uses: value '$input': add an @ref (a commit SHA pin); GitHub requires one and an unpinned action is a supply-chain risk"
    else s"invalid uses: value '$input': expected owner/repo[/path]@ref, ./local/path, or docker://image"

/** A webhook event name for `github.event_name`, as in `push` or `pull_request`.
  *
  * Shape only. GitHub adds event types, so validating against a fixed list would reject a valid workflow the day a new
  * event ships; the shape check is what catches the actual mistake, a quoted expression or a typo with punctuation in
  * it.
  */
type EventName = EventName.Type
object EventName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an event name must be non-empty"
    else if input.matches(Names.SecretName) then true
    else s"invalid event name '$input': must start with a letter or _ and contain only letters, digits and _"

/** A single-quoted literal inside an expression: the `refs/tags/v` of `startsWith(github.ref, 'refs/tags/v')`.
  *
  * Quotes, `$` and whitespace are rejected because the literal is emitted between `'…'` with no escaping, so any of
  * them either closes the quote early or turns the literal into a nested expression. The character set is what refs,
  * `owner/repo` slugs and PR labels actually use, and the length is bounded so a pathological value cannot produce an
  * unreadable `if:` line.
  */
type ExprLiteral = ExprLiteral.Type
object ExprLiteral extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an expression literal must be non-empty"
    else if input.length > Names.MaxLiteral then s"an expression literal must be at most ${Names.MaxLiteral} characters"
    else if !input.matches(Names.ExprLiteral) then
      s"invalid expression literal '$input': allowed characters are letters, digits and _ . / @ + : -"
    else true

/** **Escape hatch.** A raw GitHub Actions expression.
  *
  * Non-empty, single-line, length-bounded, and with balanced `${{ … }}` delimiters if any are present. That is enough
  * to keep a raw expression from emitting YAML GitHub cannot parse; it is *not* enough to make the expression mean what
  * the caller intended, which is why the typed [[Expr]] cases exist.
  */
type RawExpr = RawExpr.Type
object RawExpr extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.trim.isEmpty then "a raw expression must be non-empty"
    else if input.contains("\n") || input.contains("\r") then "a raw expression must be a single line"
    else if input.length > Names.MaxRawExpr then s"a raw expression must be at most ${Names.MaxRawExpr} characters"
    else if input.split("\\$\\{\\{", -1).length != input.split("\\}\\}", -1).length then
      s"unbalanced \\$${{ }} in raw expression '$input'"
    else true

/** Patterns as `inline val` Strings so `validate` can evaluate them during compilation; a compiled `Regex` cannot be.
  */
object Names:

  /** GitHub's id rule for jobs and steps: letter or `_`, then alphanumerics, `-`, `_`. */
  inline val ActionsId = "[A-Za-z_][A-Za-z0-9_-]*"

  /** Secret / variable names: alphanumerics and `_`, not starting with a digit. */
  inline val SecretName = "[A-Za-z_][A-Za-z0-9_]*"

  /** Case-insensitive, because GitHub matches these names case-insensitively. */
  inline val GithubPrefixed = "(?i)GITHUB_.*"
  inline val GithubToken    = "(?i)GITHUB_TOKEN"

  /** The workflow commands GitHub disabled; a name shaped like one is a ported-script mistake. */
  inline val Deprecated = "(?i)(set-output|save-state)"

  /** Dotted identifiers with optional `[n]` index or `*` wildcard segments. */
  inline val ContextPath =
    "[A-Za-z_][A-Za-z0-9_-]*(\\[[0-9]+\\])*(\\.([A-Za-z_][A-Za-z0-9_-]*|\\*)(\\[[0-9]+\\])*)*"

  /** Single-quoted literal contents: no quote, `$` or whitespace, since none of those can be escaped there. */
  inline val ExprLiteral = "[A-Za-z0-9_./@+:-]+"

  inline val RemoteAction   = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[A-Za-z0-9_./-]+)?@[A-Za-z0-9_./-]+"
  inline val UnpinnedAction = "[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+"
  inline val LocalAction    = "\\./[A-Za-z0-9_./-]+"
  inline val DockerAction   = "docker://[A-Za-z0-9_.:/@-]+"

  /** Matches `JobCondition.MaxLiteralLen`, which these newtypes replaced. */
  inline val MaxLiteral = 256

  /** Generous, since a hand-written condition can legitimately be long; the point is to bound it, not to be tight. */
  inline val MaxRawExpr = 1024
end Names
