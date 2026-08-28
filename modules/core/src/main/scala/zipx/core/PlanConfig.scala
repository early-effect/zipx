package zipx.core

import neotype.Subtype
import zipx.workflow.{ExprLiteral, Step}

enum AffectedMode:
  case Always, AffectedOnPR

/** The workflow's `name:`, which is also the first segment of its `concurrency` group so sibling workflows never
  * contend.
  *
  * Single-line and control-character-free, because both of those positions are YAML scalars zipx emits without quoting.
  * Nothing narrower: a workflow name is display text, so spaces and punctuation are the point.
  */
type WorkflowName = WorkflowName.Type
object WorkflowName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.trim.isEmpty then "a workflow name must be non-empty"
    else if input.contains("\n") || input.contains("\r") then "a workflow name must be a single line"
    else if !input.matches(zipx.shell.Patterns.NoControlChars) then
      "a workflow name must not contain control characters"
    else true

/** A `runs-on` label, as in `ubuntu-latest`, `macos-14` or a self-hosted label.
  *
  * Constrained to [[PlanText.KeySegment]] rather than merely to printable text because the same value is the leading
  * segment of every `actions/cache` key: a comma there would split one key into two, and whitespace would make the
  * restore-keys list ambiguous. GitHub's own labels satisfy this, and so does every conventional self-hosted one.
  */
type RunnerOs = RunnerOs.Type
object RunnerOs extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a runner label must be non-empty"
    else if input.matches(PlanText.KeySegment) then true
    else s"invalid runner label '$input': allowed characters are letters, digits and . _ -"

/** A `setup-java` `java-version` value: `21`, `21.0.2`, `17.0.11+9`, `21-ea`.
  *
  * Also a cache-key segment, so [[PlanText.VersionSegment]] is [[PlanText.KeySegment]] plus the `+` a build number uses
  * and the `@` of setup-java's `temurin@21` form.
  *
  * Named `JdkVersion` and not `JavaVersion` because sbt 2.0 has a `sbt.JavaVersion` of its own, and the plugin
  * re-exports this one into `build.sbt`'s scope: two `JavaVersion`s there is an ambiguous reference, not a shadow.
  */
type JdkVersion = JdkVersion.Type
object JdkVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a java version must be non-empty"
    else if input.matches(PlanText.VersionSegment) then true
    else s"invalid java version '$input': allowed characters are letters, digits and . _ - + @"

/** A `setup-node` `node-version` value: `22`, `22.11.0`, `latest`, or an `lts` alias.
  *
  * The same character set as [[JdkVersion]] plus the slash and star an `lts` alias needs. Unlike a JDK version this
  * never reaches a cache key, so the segment rules do not apply.
  */
type NodeVersion = NodeVersion.Type
object NodeVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a node version must be non-empty"
    else if input.matches(PlanText.NodeVersionSegment) then true
    else s"invalid node version '$input': allowed characters are letters, digits and . _ - + @ / *"

/** Patterns as `inline val` Strings so `validate` can evaluate them while a consumer's build compiles, the same
  * arrangement as `zipx.workflow.Names`.
  */
object PlanText:

  /** One segment of an `actions/cache` key: no whitespace (the restore-keys list is newline-separated) and no comma
    * (GitHub splits a key on it).
    */
  inline val KeySegment = "[A-Za-z0-9._-]+"

  /** [[KeySegment]] plus `+` and `@`, which version strings use. */
  inline val VersionSegment = "[A-Za-z0-9._+@-]+"

  /** [[VersionSegment]] plus the slash and star of setup-node's `lts` aliases. */
  inline val NodeVersionSegment = "[A-Za-z0-9._+@*/-]+"
end PlanText

/** What the planner needs that the module graph cannot supply: triggers, matrix axes, cache choice, action pins. Module
  * identity and edges are always derived from the build.
  *
  * @param affectedOnPush
  *   restricts pushes as well as PRs to affected modules, by diffing against the push `before` sha. Off by default,
  *   because a bad `before` (a force-push, a branch's first push) would silently under-build. Tags always build all.
  * @param affectedPublish
  *   extends affected-gating to [[Phase.Publish]] jobs under [[CapabilityScope.Graph]], so one changed module does not
  *   rebuild and push every image. A separate knob from [[affected]] rather than a widening of it, because the two
  *   phases carry opposite risks: **under-verifying is silently unsafe** (a green PR whose code was never tested),
  *   while **under-publishing is loudly broken** (the deploy that wants the missing artifact fails immediately). One
  *   switch for both would price Publish's narrowing at Verify's risk. Off by default. Fail-open carries over
  *   unchanged, and a release tag always publishes everything.
  * @param affectedDeploy
  *   extends affected-gating to [[Phase.Deploy]] jobs under [[CapabilityScope.Graph]], so a deploy skips exactly when
  *   the publish it consumes skipped. Its own knob rather than a widening of [[affectedPublish]], because narrowing
  *   image pushes while still reconciling every destination on every run is a legitimate combination, and one switch
  *   would take it away. Off by default: a deploy that does not run leaves a destination on its previous version, which
  *   is correct only when that module's artifacts really are unchanged.
  *
  * An [[CapabilityScope.Aggregate]] or [[CapabilityScope.Layer]] deploy is never gated by this and cannot be: its one
  * job spans every participating module, so there is no per-module decision available. Such a deploy paired with an
  * affected-gated Graph publish is therefore rejected outright rather than gated (see `Planner.validateCapabilities`),
  * because it would run alongside a skipped publish and reference an artifact nobody built.
  * @param cacheEpoch
  *   how [[CacheBackend.LocalDir]] picks its commit-stable cache namespace: mid-PR commits share hits and a release tag
  *   rolls the namespace. Prefer the runtime-tag default so keys stay fresh without regenerating the workflow.
  * @param actions
  *   catalog [[Action]] rows overlay [[ActionPins.Defaults]]; set this only for a one-off hatch. YAML is jar/generate
  *   output, not an input.
  * @param skipMergedPrPush
  *   skips Verify on a branch push whose commit already belongs to a PR merged into that branch, so tests do not run
  *   twice after a merge. Direct pushes still run, as do PRs, tags and `workflow_dispatch`.
  * @param cacheRehydrateOnMerge
  *   emits a minimal `cache-rehydrate` job that runs exactly when [[skipMergedPrPush]] skips Verify, recreating a
  *   default-branch `actions/cache` save so the next PR can restore from main. GitHub does not share PR-scoped caches
  *   across refs. Inert for remote backends and when skip-on-merge is off.
  * @param cacheRehydrateTask
  *   not a full Verify: no `zipxTestTask` and no [[verifyClean]].
  * @param cacheRehydrateExtraSteps
  *   runs after the LocalDir restore and before [[cacheRehydrateTask]]. Deliberately *not* copied from Verify
  *   capabilities; naming the same [[Steps]] bundle in both places is how you get parity without duplicating a lambda.
  * @param cacheRehydrateEnv
  *   overlays [[env]] and wins on a key clash.
  * @param env
  *   overlaid in turn by capability and target env. Not applied to reusable-workflow caller jobs
  *   ([[Capability.workflowCall]]), since GHA forbids job-level `env` alongside `uses:`.
  * @param verifyCleanLabel
  *   prepends `cleanFull` at workflow runtime when the PR carries this label, a one-off cache bust that needs no
  *   permanent [[verifyClean]] setting. Ignored when [[verifyClean]] is already set; `None` disables the check. An
  *   [[zipx.workflow.ExprLiteral]] because the label is emitted between `'…'` inside `contains(…)`, where GitHub offers
  *   no escaping, so a label containing a quote must be unrepresentable rather than reported at generate time.
  * @param cancelSupersededRuns
  *   emits workflow-level `concurrency` keyed on ref, so pushing again to a PR cancels the running build. Release-tag
  *   runs are never cancelled: the group folds in the ref, and a half-cancelled publish is worse than a wasted runner.
  * @param matrixCollapse
  *   per-capability defaults for [[MatrixCollapse]]; capability [[Capability.matrixCollapse]] overrides these.
  * @param defaultMatrixCollapse
  *   used when neither the capability nor [[matrixCollapse]] names a mode. [[MatrixCollapse.Auto]] by default.
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
    cancelSupersededRuns: Boolean = true,
    matrixCollapse: Map[CapabilityName, MatrixCollapse] = Map.empty,
    defaultMatrixCollapse: MatrixCollapse = MatrixCollapse.Auto,
    /** True when the build has Ship rows. Library Graph publish is version-moved, not fail-open affected. */
    modverPublish: Boolean = false,
    /** SHA-256 prefix baked into LocalDir keys when [[cacheEpoch]] is [[CacheEpoch.ShipCatalog]]. */
    shipEpochHash: Option[String] = None,
)

object PlanConfig:

  val DefaultWorkflowName: WorkflowName = WorkflowName("CI")
  val DefaultJdkVersion: JdkVersion     = JdkVersion("21")
  val DefaultRunnerOs: RunnerOs         = RunnerOs("ubuntu-latest")

  val DefaultVerifyCleanLabel: ExprLiteral = ExprLiteral("clean")

  /** Wire-form placeholder for planner unit tests; the sbt plugin always overwrites from zipxTasks.of. Not an sbt API
    * surface.
    */
  val DefaultCacheRehydrateTask: SbtCommand = SbtCommand.unsafeTask("compile")

  inline def verifyCleanLabel(inline label: String): Option[ExprLiteral] = Some(ExprLiteral(label))

  def verifyCleanLabelMake(label: String): Either[String, Option[ExprLiteral]] =
    ExprLiteral.make(label).map(Some(_))

end PlanConfig
