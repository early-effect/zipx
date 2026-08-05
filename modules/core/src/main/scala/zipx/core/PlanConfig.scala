package zipx.core

import zipx.workflow.{ExprLiteral, Step}

enum AffectedMode:
  case Always, AffectedOnPR

/** What the planner needs that the module graph cannot supply: triggers, matrix axes, cache choice, action pins. Module
  * identity and edges are always derived from the build.
  *
  * @param affectedOnPush
  *   restricts pushes as well as PRs to affected modules, by diffing against the push `before` sha. Off by default,
  *   because a bad `before` (a force-push, a branch's first push) would silently under-build. Tags always build all.
  * @param cacheEpoch
  *   how [[CacheBackend.LocalDir]] picks its commit-stable cache namespace: mid-PR commits share hits and a release tag
  *   rolls the namespace. Prefer the runtime-tag default so keys stay fresh without regenerating the workflow.
  * @param actions
  *   prefer `.github/zipx/action-pins.yml` (see [[ActionPinFile]]) over setting this; [[ActionPins.Defaults]] are the
  *   pins embedded in the zipx release jar.
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
  */
final case class PlanConfig(
    workflowName: String = "CI",
    scalaMatrix: Boolean = true,
    javaVersion: String = "21",
    runnerOs: String = "ubuntu-latest",
    affected: AffectedMode = AffectedMode.AffectedOnPR,
    affectedOnPush: Boolean = false,
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
)

object PlanConfig:

  val DefaultVerifyCleanLabel: ExprLiteral = ExprLiteral("clean")

  val DefaultCacheRehydrateTask: SbtCommand = SbtCommand("compile")

  inline def verifyCleanLabel(inline label: String): Option[ExprLiteral] = Some(ExprLiteral(label))

  def verifyCleanLabelMake(label: String): Either[String, Option[ExprLiteral]] =
    ExprLiteral.make(label).map(Some(_))

end PlanConfig
