package zipx.core

import neotype.Subtype
import zipx.workflow.{Expr, ExprLiteral, Step}

enum AffectedMode:
  case Always, AffectedOnPR

/** The workflow's `name:`, also the first segment of its `concurrency` group so sibling workflows never contend.
  * Single-line and control-character-free because zipx emits both positions unquoted; spaces and punctuation are fine.
  */
type WorkflowName = WorkflowName.Type
object WorkflowName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.trim.isEmpty then "a workflow name must be non-empty"
    else if input.contains("\n") || input.contains("\r") then "a workflow name must be a single line"
    else if !input.matches(zipx.shell.Patterns.NoControlChars) then
      "a workflow name must not contain control characters"
    else true

/** A `runs-on` label. Constrained to [[PlanText.KeySegment]] because it also leads every `actions/cache` key. */
type RunnerOs = RunnerOs.Type
object RunnerOs extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a runner label must be non-empty"
    else if input.matches(PlanText.KeySegment) then true
    else s"invalid runner label '$input': allowed characters are letters, digits and . _ -"

/** A `setup-java` `java-version`, build number and `temurin@` forms included. Also a cache-key segment.
  *
  * Not `JavaVersion`: the plugin re-exports this into `build.sbt`, where `sbt.JavaVersion` would make it ambiguous.
  */
type JdkVersion = JdkVersion.Type
object JdkVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a java version must be non-empty"
    else if input.matches(PlanText.VersionSegment) then true
    else s"invalid java version '$input': allowed characters are letters, digits and . _ - + @"

/** A `setup-node` `node-version`, `latest` and `lts` aliases included. Never part of a cache key. */
type NodeVersion = NodeVersion.Type
object NodeVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a node version must be non-empty"
    else if input.matches(PlanText.NodeVersionSegment) then true
    else s"invalid node version '$input': allowed characters are letters, digits and . _ - + @ / *"

/** `inline val` patterns so `validate` can evaluate them while a consumer's build compiles. */
object PlanText:

  /** One segment of an `actions/cache` key: no whitespace (the restore-keys list is newline-separated) and no comma
    * (GitHub splits a key on it).
    */
  inline val KeySegment = "[A-Za-z0-9._-]+"

  inline val VersionSegment = "[A-Za-z0-9._+@-]+"

  inline val NodeVersionSegment = "[A-Za-z0-9._+@*/-]+"
end PlanText

/** What the planner needs that the module graph cannot supply: triggers, matrix axes, cache choice, action pins.
  *
  * @param affectedOnPush
  *   gates pushes on affected modules by diffing against `before`. Off by default: a force-push or a branch's first
  *   push has a bad `before` and would silently under-build. Tags always build all.
  * @param affectedPublish
  *   affected-gates [[Phase.Publish]] Graph jobs. Separate from [[affected]] because under-verifying is silently unsafe
  *   while under-publishing fails loudly. A release tag always publishes everything.
  * @param affectedDeploy
  *   affected-gates [[Phase.Deploy]] Graph jobs, so a deploy skips when its publish skipped. Separate from
  *   [[affectedPublish]] so narrowed pushes can still reconcile every destination on every run.
  *
  * An Aggregate or Layer deploy spans every module, so it cannot be gated; pairing one with an affected-gated Graph
  * publish is rejected (`Planner.validateCapabilities`) because it would deploy an artifact nobody built.
  * @param cacheEpoch
  *   how [[CacheBackend.LocalDir]] picks its namespace: mid-PR commits share hits, a release tag rolls it.
  * @param actions
  *   catalog [[Action]] rows overlay [[ActionPins.Defaults]]; set this only for a one-off hatch.
  * @param skipMergedPrPush
  *   skips Verify on a branch push whose commit belongs to a PR already merged into that branch.
  * @param cacheRehydrateOnMerge
  *   when [[skipMergedPrPush]] skips Verify, a minimal job re-saves the default-branch `actions/cache` entry, since
  *   GitHub does not share PR-scoped caches across refs. Inert for remote backends.
  * @param cacheRehydrateTask
  *   not a full Verify: no `zipxTestTask` and no [[verifyClean]].
  * @param cacheRehydrateExtraSteps
  *   runs after the LocalDir restore. Not copied from Verify; name the same [[Steps]] bundle in both for parity.
  * @param cacheRehydrateEnv
  *   overlays [[env]] and wins on a key clash.
  * @param env
  *   overlaid by capability and target env. Not applied to [[Capability.workflowCall]] jobs: GHA forbids job-level
  *   `env` alongside `uses:`.
  * @param verifyCleanLabel
  *   a PR with this label prepends `cleanFull` (sbt outputs, not the LocalDir restore). Ignored when [[verifyClean]] is
  *   set. An [[zipx.workflow.ExprLiteral]] because GitHub cannot escape a quote inside `contains('…')`.
  * @param cachePurgeLabel
  *   a PR with this label skips every LocalDir restore; the save owner saves without restore-keys so the new entry does
  *   not fall back onto the dropped one.
  * @param cancelSupersededRuns
  *   ref-keyed workflow `concurrency`, so a new push cancels the running build. Release-tag runs are never cancelled.
  * @param matrixCollapse
  *   per-capability defaults; [[Capability.matrixCollapse]] overrides these.
  * @param defaultMatrixCollapse
  *   used when neither the capability nor [[matrixCollapse]] names a mode.
  */
final case class PlanConfig(
    workflowName: WorkflowName = PlanConfig.DefaultWorkflowName,
    scalaMatrix: Boolean = true,
    javaVersion: JdkVersion = PlanConfig.DefaultJdkVersion,
    runnerOs: RunnerOs = PlanConfig.DefaultRunnerOs,
    affected: AffectedMode = AffectedMode.AffectedOnPR,
    affectedOnPush: Boolean = false,
    affectedPublish: Boolean = false,
    affectedDeploy: Boolean = false,
    cache: CacheBackend = CacheBackend.LocalDir,
    cacheEpoch: CacheEpoch = CacheEpoch.GitTags(),
    pushBranches: List[String] = List("main"),
    releaseTagPattern: String = "v[0-9]+.[0-9]+.[0-9]+",
    actions: ActionPins = ActionPins.Defaults,
    workflowDispatch: Boolean = false,
    skipMergedPrPush: Boolean = true,
    cacheRehydrateOnMerge: Boolean = true,
    cacheRehydrateTask: SbtCommand = PlanConfig.DefaultCacheRehydrateTask,
    cacheRehydrateExtraSteps: StepContext => List[Step] = _ => Nil,
    cacheRehydrateEnv: Map[String, EnvValue] = Map.empty,
    env: Map[String, EnvValue] = Map.empty,
    verifyClean: VerifyClean = VerifyClean.None,
    verifyCleanLabel: Option[ExprLiteral] = Some(PlanConfig.DefaultVerifyCleanLabel),
    cachePurgeLabel: Option[ExprLiteral] = Some(PlanConfig.DefaultCachePurgeLabel),
    cancelSupersededRuns: Boolean = true,
    matrixCollapse: Map[CapabilityName, MatrixCollapse] = Map.empty,
    defaultMatrixCollapse: MatrixCollapse = MatrixCollapse.Auto,
    /** SHA-256 prefix baked into LocalDir keys when [[cacheEpoch]] is [[CacheEpoch.ShipCatalog]]. */
    shipEpochHash: Option[String] = None,
)

object PlanConfig:

  val DefaultWorkflowName: WorkflowName = WorkflowName("CI")
  val DefaultJdkVersion: JdkVersion     = JdkVersion("21")
  val DefaultRunnerOs: RunnerOs         = RunnerOs("ubuntu-latest")

  val DefaultVerifyCleanLabel: ExprLiteral = ExprLiteral("clean")
  val DefaultCachePurgeLabel: ExprLiteral  = ExprLiteral("purge")

  /** Reads the event payload: `ci.yml` does not run on `labeled`, and a rerun repeats the payload it started with. */
  def pullRequestHasLabel(label: ExprLiteral): Expr =
    (Expr.github("event_name") === Expr.quoted("pull_request")) &&
      Expr.contains(Expr.github("event.pull_request.labels.*.name"), Expr.Quoted(label))

  /** Placeholder for planner unit tests; the plugin always overwrites it from `zipxTasks`. */
  val DefaultCacheRehydrateTask: SbtCommand = SbtCommand.unsafeTask("Test/compile")

  inline def verifyCleanLabel(inline label: String): Option[ExprLiteral] = Some(ExprLiteral(label))

  def verifyCleanLabelMake(label: String): Either[String, Option[ExprLiteral]] =
    ExprLiteral.make(label).map(Some(_))

  inline def cachePurgeLabel(inline label: String): Option[ExprLiteral] = Some(ExprLiteral(label))

  def cachePurgeLabelMake(label: String): Either[String, Option[ExprLiteral]] =
    ExprLiteral.make(label).map(Some(_))

end PlanConfig
