package zipx.core

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
  *   `before` sha. Off by default: pushes to main build everything (safer — a bad `before`, e.g. a force-push or the
  *   first push to a branch, would otherwise silently under-build). Tags always build everything.
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
  * @param verifyClean
  *   optional `clean` / `cleanFull` prepended to every Verify-phase sbt command (Aggregate, Layer, and Graph).
  * @param cancelSupersededRuns
  *   when true (default), emit workflow-level `concurrency` keyed on ref so pushing again to a PR cancels the still-
  *   running earlier build. Never cancels release-tag runs — the group folds in the ref, and a half-cancelled publish
  *   is worse than a wasted runner.
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
    verifyClean: VerifyClean = VerifyClean.None,
    cancelSupersededRuns: Boolean = true,
)
