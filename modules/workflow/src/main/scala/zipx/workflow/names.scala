package zipx.workflow

import neotype.*

// GitHub's syntax rules, kept here rather than in the deliberately GHA-agnostic zipx-shell.

/** Uniqueness is a property of the collection, so it is checked where the job map is assembled.
  *
  * A `Subtype` (`JobId <: String`) because an id is read in far more positions than it is built: `Workflow.jobs` keys,
  * `needs` elements and step names are all plain `String`.
  */
type JobId = JobId.Type
object JobId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a job id must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else s"invalid job id '$input': must start with a letter or _ and contain only letters, digits, - or _"

type StepId = StepId.Type
object StepId extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a step id must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else s"invalid step id '$input': must start with a letter or _ and contain only letters, digits, - or _"

/** The reserved `GITHUB_` prefix is matched case-insensitively, as GitHub does. `GITHUB_TOKEN` alone is accepted: it is
  * injected rather than created, and `secrets.GITHUB_TOKEN` is the documented way to read it.
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

/** Shares `zipx.shell.Patterns.Ident` with `VarName`: an `env:` key becomes a shell variable in every `run:` step, so
  * the two layers must agree on what a name is.
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

/** Rejects `set-output` and `save-state`, the workflow commands GitHub disabled: they signal a ported script expecting
  * behaviour GitHub removed.
  */
type OutputName = OutputName.Type
object OutputName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an output name must be non-empty"
    else if input.matches(Names.Deprecated) then
      s"'$input' is a deprecated workflow command, not an output name: write to \\$$GITHUB_OUTPUT instead"
    else if input.matches(Names.ActionsId) then true
    else s"invalid output name '$input': must start with a letter or _ and contain only letters, digits, - or _"

type InputName = InputName.Type
object InputName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an input name must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else s"invalid input name '$input': must start with a letter or _ and contain only letters, digits, - or _"

type PropertyName = PropertyName.Type
object PropertyName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a property name must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else s"invalid property name '$input': must start with a letter or _ and contain only letters, digits, - or _"

/** `include` and `exclude` are rejected: they are directives that add and remove combinations, so `matrix.include` does
  * not mean what it reads like.
  */
type MatrixAxis = MatrixAxis.Type
object MatrixAxis extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a matrix axis must be non-empty"
    else if input == "include" || input == "exclude" then
      s"'$input' is a matrix directive, not an axis: it adds or removes combinations rather than naming one"
    else if input.matches(Names.ActionsId) then true
    else s"invalid matrix axis '$input': must start with a letter or _ and contain only letters, digits, - or _"

/** The part after the context name, as in `event.pull_request.labels.*.name`, with the `[n]` index and `*` wildcard
  * GitHub allows.
  */
type ContextPath = ContextPath.Type
object ContextPath extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a context path must be non-empty"
    else if input.matches(Names.ContextPath) then true
    else
      s"invalid context path '$input': expected dotted identifiers with optional [n] index or * wildcard, as in event.pull_request.base.sha"

/** A bare `owner/repo` with no `@ref` is rejected: GitHub requires the ref, and an unpinned action is what
  * `ActionPinFile` exists to prevent.
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

/** Shape only: GitHub adds event types, so a fixed list would reject a valid workflow the day a new event ships. */
type EventName = EventName.Type
object EventName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an event name must be non-empty"
    else if input.matches(Names.SecretName) then true
    else s"invalid event name '$input': must start with a letter or _ and contain only letters, digits and _"

/** Checked against GitHub's documented list, since the language has no user-defined functions, and case-insensitively
  * as the language matches them. [[Expr.raw]] is the escape hatch for a function this list predates.
  */
type FunctionName = FunctionName.Type
object FunctionName extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a function name must be non-empty"
    else if input.matches(Names.Functions) then true
    else s"unknown expression function '$input': GitHub Actions has no user-defined functions"

/** Emitted between `'…'` with no escaping, so quotes, `$` and whitespace are rejected: each either closes the quote
  * early or turns the literal into a nested expression.
  */
type ExprLiteral = ExprLiteral.Type
object ExprLiteral extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an expression literal must be non-empty"
    else if input.length > Names.MaxLiteral then s"an expression literal must be at most ${Names.MaxLiteral} characters"
    else if !input.matches(Names.ExprLiteral) then
      s"invalid expression literal '$input': allowed characters are letters, digits and _ . / @ + : -"
    else true

/** **Escape hatch.** Validated enough to keep it from emitting YAML GitHub cannot parse, not enough to make it mean
  * what the caller intended. The control-character rule is what makes [[Expr.renderShText]] total.
  */
type RawExpr = RawExpr.Type
object RawExpr extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.trim.isEmpty then "a raw expression must be non-empty"
    else if input.contains("\n") || input.contains("\r") then "a raw expression must be a single line"
    else if !input.matches(zipx.shell.Patterns.NoControlChars) then
      "a raw expression must not contain control characters"
    else if input.length > Names.MaxRawExpr then s"a raw expression must be at most ${Names.MaxRawExpr} characters"
    else if input.split("\\$\\{\\{", -1).length != input.split("\\}\\}", -1).length then
      s"unbalanced \\$${{ }} in raw expression '$input'"
    else true
end RawExpr

/** UTC: GitHub runs schedules in UTC and offers no timezone field. */
type CronHour = CronHour.Type
object CronHour extends Newtype[Int]:
  override inline def validate(input: Int): Boolean | String =
    if input < 0 || input > 23 then s"a cron hour must be 0 to 23, got $input"
    else true

  val Midnight: CronHour = CronHour(0)

type CronMinute = CronMinute.Type
object CronMinute extends Newtype[Int]:
  override inline def validate(input: Int): Boolean | String =
    if input < 0 || input > 59 then s"a cron minute must be 0 to 59, got $input"
    else true

  val Zero: CronMinute = CronMinute(0)

/** **Escape hatch** for [[Cron.Raw]]. Field contents are unvalidated: a step value, a `1-5` range and `MON` are what
  * the typed variants cannot say. Untrimmed input is rejected rather than trimmed, so what renders is what was written.
  */
type CronExpr = CronExpr.Type
object CronExpr extends Newtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a cron expression must be non-empty"
    else if input != input.trim then s"cron expression '$input' has leading or trailing whitespace"
    else if input.matches(Names.CronFields) then true
    else s"invalid cron '$input': expected five whitespace-separated fields (minute hour dom month dow)"

/** Patterns as `inline val` Strings so `validate` can evaluate them during compilation; a compiled `Regex` cannot be.
  */
object Names:

  inline val ActionsId = "[A-Za-z_][A-Za-z0-9_-]*"

  inline val SecretName = "[A-Za-z_][A-Za-z0-9_]*"

  inline val GithubPrefixed = "(?i)GITHUB_.*"
  inline val GithubToken    = "(?i)GITHUB_TOKEN"

  inline val Deprecated = "(?i)(set-output|save-state)"

  inline val ContextPath =
    "[A-Za-z_][A-Za-z0-9_-]*(\\[[0-9]+\\])*(\\.([A-Za-z_][A-Za-z0-9_-]*|\\*)(\\[[0-9]+\\])*)*"

  inline val ExprLiteral = "[A-Za-z0-9_./@+:-]+"

  inline val CronFields = "\\S+(\\s+\\S+){4}"

  inline val Functions =
    "(?i)(contains|startsWith|endsWith|format|join|toJSON|fromJSON|hashFiles|" +
      "success|always|cancelled|failure)"

  inline val RemoteAction   = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/[A-Za-z0-9_./-]+)?@[A-Za-z0-9_./-]+"
  inline val UnpinnedAction = "[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+"
  inline val LocalAction    = "\\./[A-Za-z0-9_./-]+"
  inline val DockerAction   = "docker://[A-Za-z0-9_.:/@-]+"

  inline val MaxLiteral = 256

  /** Generous: a hand-written condition can legitimately be long, and the point is a bound, not a tight one. */
  inline val MaxRawExpr = 1024
end Names
