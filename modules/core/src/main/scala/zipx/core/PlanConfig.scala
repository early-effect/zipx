package zipx.core

import zipx.workflow.Step

/** Whether Verify-phase jobs (test/build) run for every module or only for affected modules on pull requests. */
enum AffectedMode:
  case Always, AffectedOnPR

/** Build-level configuration for the workflow the planner produces. Everything here is what the build genuinely cannot
  * infer from the graph (triggers, matrix axes, cache choice, action pins); module identity and edges are always
  * derived.
  *
  * @param workflowName
  *   the GitHub Actions workflow `name:`.
  * @param scalaMatrix
  *   whether to expand a per-module build matrix over each module's `crossScalaVersions`.
  * @param javaVersion
  *   the JDK major version used for `actions/setup-java` and folded into the cache key.
  * @param runnerOs
  *   the runner label (e.g. "ubuntu-latest"), also folded into the cache key.
  * @param affected
  *   whether Verify jobs run for every module or only affected ones on PRs.
  * @param affectedOnPush
  *   when affected mode is on, also restrict pushes (not just PRs) to affected modules by diffing against the push
  *   `before` sha. Off by default: pushes to main build everything (safer, since a bad `before`, e.g. a force-push or
  *   the first push to a branch, would otherwise silently under-build). Tags always build everything.
  * @param cache
  *   the cache backend strategy.
  * @param cacheEpoch
  *   how LocalDir picks its commit-stable cache namespace ([[CacheEpoch]]; default [[CacheEpoch.GitTags]]). Mid-PR
  *   commits share hits; a release tag rolls the namespace. Prefer runtime tags so keys stay fresh without regenerating
  *   the workflow; use [[CacheEpoch.Fixed]] to bake a literal at generate time.
  * @param pushBranches
  *   branches whose pushes trigger the workflow.
  * @param releaseTagPattern
  *   the tag glob that gates publishing (e.g. "v[0-9]+.[0-9]+.[0-9]+").
  * @param actions
  *   hash-pinned GitHub Actions (`uses:` values). Prefer `.github/zipx/action-pins.yml` (see [[ActionPinFile]]);
  *   one-off override via `zipxActions`. Jar [[ActionPins.Defaults]] embed the pin file from the zipx release.
  * @param workflowDispatch
  *   when true, emit `on.workflow_dispatch` so the workflow can be run manually (useful for docs Pages deploys).
  * @param skipMergedPrPush
  *   when true (default), Verify jobs skip on a push to a branch if that commit already belongs to a PR merged into the
  *   same branch (merge or squash). Direct pushes to main still run; PRs, tags, and `workflow_dispatch` are unaffected.
  *   Avoids running tests twice after a PR merge.
  * @param cacheRehydrateOnMerge
  *   when true (default) and [[skipMergedPrPush]] is on with [[CacheBackend.LocalDir]], emit a minimal
  *   `cache-rehydrate` job that runs only when verify-gate skips Verify (merged-PR push). Recreates a default-branch
  *   `actions/cache` save so the next PR can restore from main; GitHub does not share PR-scoped caches across refs.
  *   Inert for remote cache backends and when skip-on-merge is off.
  * @param cacheRehydrateTask
  *   sbt command for the rehydrate job (default `compile`). Not a full Verify: no `zipxTestTask`, no [[verifyClean]].
  * @param cacheRehydrateExtraSteps
  *   optional steps on the rehydrate job only, after LocalDir cache restore and before [[cacheRehydrateTask]] (default
  *   empty). Same shape as capability `extraSteps`, so a [[Steps]] bundle fits here too. Not copied from Verify
  *   capabilities; assign the same bundle explicitly when you want parity (e.g. Playwright browser install under
  *   `target/`). That is what `Steps` is for: `zipxCacheRehydrateExtraSteps := OrgSteps.playwright` and the matching
  *   capability field name the same value instead of duplicating a lambda.
  * @param cacheRehydrateEnv
  *   optional job `env` for the rehydrate job only (default empty). Overlay on [[env]]; wins on key clash.
  * @param env
  *   build-wide job `env` merged into normal generated jobs (default empty). Typed [[EnvValue]]s. Capability and target
  *   env overlay this (more specific wins). Prefer this for vars needed on Verify **and** rehydrate (e.g. Playwright
  *   browsers path under `target/`). Not applied to reusable-workflow caller jobs ([[Capability.workflowCall]]); GHA
  *   forbids job-level `env` alongside `uses:`.
  * @param verifyClean
  *   optional `clean` / `cleanFull` prepended to every Verify-phase sbt command (Aggregate, Layer, and Graph).
  * @param verifyCleanLabel
  *   when [[verifyClean]] is [[VerifyClean.None]], optionally prepend `cleanFull` at workflow runtime if the PR has
  *   this GitHub label (default `Some("clean")`). One-off LocalDir/action-cache bust without a permanent clean setting.
  *   `None` disables the label check. Ignored when [[verifyClean]] is already `Clean` / `CleanFull`.
  * @param cancelSupersededRuns
  *   when true (default), emit workflow-level `concurrency` keyed on ref so pushing again to a PR cancels the still-
  *   running earlier build. Never cancels release-tag runs: the group folds in the ref, and a half-cancelled publish is
  *   worse than a wasted runner.
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
    cacheRehydrateTask: String = "compile",
    cacheRehydrateExtraSteps: StepContext => List[Step] = _ => Nil,
    cacheRehydrateEnv: Map[String, EnvValue] = Map.empty,
    env: Map[String, EnvValue] = Map.empty,
    verifyClean: VerifyClean = VerifyClean.None,
    verifyCleanLabel: Option[String] = Some("clean"),
    cancelSupersededRuns: Boolean = true,
)
