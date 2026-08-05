package zipx.core

import zio.test.*
import zipx.workflow.Step

/** The byte-stability proof for the typed-script migration.
  *
  * Every expected string here is what the site emitted *before* it was a `Script`, lifted verbatim out of the source it
  * came from. This repo has no golden `.yml` fixtures, so this spec is where the old literals live on: a change to the
  * shell AST that moves a single character fails here, with a diff, rather than surfacing as a mystery
  * `zipxWorkflowCheck` failure in a consumer's repo.
  *
  * The dogfood zero-diff check (`zipxWorkflowGenerate` then `git diff --exit-code .github/`) is the end-to-end half of
  * the same proof. This spec is the half that says *which* script moved.
  */
object ScriptRenderSpec extends ZIOSpecDefault:

  def spec = suite("migrated scripts render byte-identically")(
    test("the action-pins sync commit script") {
      val step = ActionPinsSyncWorkflow
        .plan(ActionPins.Defaults, javaVersion = "25", runnerOs = "ubuntu-latest")
        .fold(error => throw AssertionError(s"unexpected plan failure: $error"), identity)
        .jobs("sync")
        .steps
        .find(_.name.contains("Commit pin file and workflows"))
        .getOrElse(throw AssertionError("commit step missing"))
      assertTrue(
        step.run.contains(
          """if [ -z "$(git status --porcelain '.github/zipx/action-pins.yml' '.github/workflows/ci.yml' '.github/workflows/zipx-action-pins-sync.yml')" ]; then
            |  echo "No pin/workflow changes to commit."
            |  exit 0
            |fi
            |git config user.name "github-actions[bot]"
            |git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
            |git add '.github/zipx/action-pins.yml' '.github/workflows/ci.yml' '.github/workflows/zipx-action-pins-sync.yml'
            |git commit -m "ci: sync zipx action pins from Dependabot"
            |git push
            |""".stripMargin
        )
      )
    },
    test("the git-tags cache-epoch resolver, the acid test") {
      assertTrue(
        CacheEpoch.gitTagsResolveScript() ==
          """set -euo pipefail
            |tag_match='v*'
            |if git remote get-url origin >/dev/null 2>&1; then
            |  remote_count=$(git ls-remote --tags --refs origin "$tag_match" 2>/dev/null | wc -l | tr -d ' ')
            |  local_count=$(git tag -l "$tag_match" | wc -l | tr -d ' ')
            |  if [ "$remote_count" -gt 0 ] && [ "$local_count" -lt "$remote_count" ]; then
            |    echo "::warning title=zipx cache epoch::Local tags ($local_count) fewer than origin ($remote_count). Epoch may be stale; checkout must use fetch-depth: 0 and fetch-tags: true."
            |  fi
            |fi
            |if [[ "${GITHUB_REF:-}" == refs/tags/v* ]]; then
            |  release="${GITHUB_REF#refs/tags/v}"
            |  epoch="$release"
            |elif tag=$(git describe --tags --abbrev=0 --match "$tag_match" 2>/dev/null); then
            |  release="${tag#v}"
            |  epoch="${release}-ci"
            |else
            |  echo "::warning title=zipx cache epoch::No tags matching '$tag_match' found; using epoch 0.0.0."
            |  release="0.0.0"
            |  epoch="0.0.0"
            |fi
            |echo "epoch=$epoch" >> "$GITHUB_OUTPUT"
            |echo "release=$release" >> "$GITHUB_OUTPUT"
            |""".stripMargin,
        // A non-default tag match still goes through single quotes, so a glob cannot leak into the shell unquoted.
        CacheEpoch.gitTagsResolveScript(CacheEpoch.gitTags("release-*").tagMatch).contains("tag_match='release-*'"),
        CacheEpoch.gitTagsResolveTypedScript().rawFragments.isEmpty,
      )
    },
    test("the verify-gate merged-PR check, with its nested quoting and line continuation") {
      val step = Planner
        .plan(Fixtures.sampleGraph, List(Capability.test), PlanConfig(skipMergedPrPush = true))
        .jobs("verify-gate")
        .steps
        .head
      assertTrue(
        step.run.contains(
          """# Commits landed by merging/squashing a PR are associated with that PR via the API.
            |prs=$(gh api "repos/${{ github.repository }}/commits/${{ github.sha }}/pulls" \
            |  --jq "[.[] | select(.merged_at != null and .base.ref == \"${{ github.ref_name }}\")] | length")
            |if [ "$prs" -gt 0 ]; then
            |  echo "Merged PR push, skipping redundant Verify (already ran on the PR)"
            |  echo "run=false" >> "$GITHUB_OUTPUT"
            |else
            |  echo "run=true" >> "$GITHUB_OUTPUT"
            |fi""".stripMargin
        ),
        // No trailing newline here, unlike the cache-epoch script. The two differ, and the type is what records that.
        !step.run.exists(_.endsWith("\n")),
      )
    },
    test("the affected-modules script, PR-only") {
      assertTrue(
        affectedScript(affectedOnPush = false).contains(
          """if [ "${{ github.event_name }}" = "pull_request" ]; then
            |  BASE="${{ github.event.pull_request.base.sha }}"
            |  sbt -batch --error "zipxAffectedModules $BASE"
            |  modules=$(cat target/zipx-affected.json)
            |else
            |  modules='["all"]'
            |fi
            |echo "modules=$modules" >> "$GITHUB_OUTPUT"""".stripMargin
        )
      )
    },
    test("the affected-modules script, with the push branch") {
      // One deliberate byte change from the pre-DSL output: the nested `modules=$(cat …)` was emitted at two spaces
      // where four is correct, because one interpolated fragment was spliced into two different nesting depths. The
      // AST owns depth, so it lines up, and the `.replace("\n\n", "\n")` that papered over the empty branch is gone.
      assertTrue(
        affectedScript(affectedOnPush = true).contains(
          """if [ "${{ github.event_name }}" = "pull_request" ]; then
            |  BASE="${{ github.event.pull_request.base.sha }}"
            |  sbt -batch --error "zipxAffectedModules $BASE"
            |  modules=$(cat target/zipx-affected.json)
            |elif [ "${{ github.event_name }}" = "push" ]; then
            |  before="${{ github.event.before }}"
            |  if [ -z "$before" ] || [ "$before" = "0000000000000000000000000000000000000000" ]; then
            |    modules='["all"]'
            |  else
            |    BASE="$before"
            |    sbt -batch --error "zipxAffectedModules $BASE"
            |    modules=$(cat target/zipx-affected.json)
            |  fi
            |else
            |  modules='["all"]'
            |fi
            |echo "modules=$modules" >> "$GITHUB_OUTPUT"""".stripMargin
        )
      )
    },
    test("the verify step's runtime cleanFull branch, and the env expression that decides it") {
      // Three layers meet in one step and each keeps its own form: an `if` with a quoted `$VAR` comparison (shell), an
      // sbt command jointed into one single-quoted argument (not shell structure), and a *wrapped* `${{ }}` env value,
      // because an `env:` entry is a plain field the runner substitutes rather than an expression position.
      val step = verifyStep(PlanConfig(verifyCleanLabel = PlanConfig.verifyCleanLabel("clean")))
      assertTrue(
        step.run.contains(
          """if [ "$ZIPX_VERIFY_CLEAN_FULL" = "true" ]; then
            |  sbt 'cleanFull; test'
            |else
            |  sbt 'test'
            |fi""".stripMargin
        ),
        step.env
          .get("ZIPX_VERIFY_CLEAN_FULL")
          .contains(
            "${{ github.event_name == 'pull_request' && " +
              "contains(github.event.pull_request.labels.*.name, 'clean') }}"
          ),
      )
    },
    test("the verify step without the label is one sbt line, and with a static mode carries the prefix") {
      // The matrixed case is the interesting one: `++${{ matrix.scala }} ` is jointed into the *same* single quoted
      // argument as the task, so the expression has to survive the shell layer without being escaped.
      val matrixed = Planner
        .plan(Fixtures.sampleGraph, List(Capability.testGraph), PlanConfig(verifyCleanLabel = None))
        .jobs("test-schema")
        .steps
        .last
      assertTrue(
        verifyStep(PlanConfig(verifyCleanLabel = None)).run.contains("sbt 'test'"),
        verifyStep(PlanConfig(verifyClean = VerifyClean.CleanFull)).run.contains("sbt 'cleanFull; test'"),
        matrixed.run.contains("sbt '++${{ matrix.scala }} schema/test'"),
      )
    },
    test("a verify-clean label containing a quote is not a value that can reach a plan") {
      // The label lands inside `'…'` in an expression, where GitHub offers no escape, so the field is an `ExprLiteral`
      // rather than a `String`. There is no plan-time check left to test: a literal is rejected while this spec
      // compiles, and a label that arrives as runtime data comes back as a `Left` that never becomes a `PlanConfig`.
      for bad <- typeCheck("""zipx.core.PlanConfig(verifyCleanLabel = zipx.core.PlanConfig.verifyCleanLabel("it's"))""")
      yield assertTrue(
        bad.isLeft,
        PlanConfig.verifyCleanLabelMake("it's clean").isLeft,
        PlanConfig.verifyCleanLabelMake("clean").isRight,
      )
    },
    test("the cache key and restore-keys, assembled from Expr rather than interpolated") {
      // `key` is one line, `restore-keys` is newline-joined for the block-scalar printer, and `path` is plain data. All
      // three used to be `s"…$${{ … }}"` strings; the bytes are unchanged.
      val prefix = "ubuntu-latest-jdk21-sbt-"
      val cache  = cacheStep(PlanConfig(cacheEpoch = CacheEpoch.GitTags()))
      val fixed  = cacheStep(PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.2.3-ci")))
      assertTrue(
        cache.`with`("key") ==
          s"$prefix$${{ steps.cache-epoch.outputs.epoch }}-$${{ github.run_id }}-test",
        cache.`with`("restore-keys") ==
          s"""$prefix$${{ steps.cache-epoch.outputs.epoch }}-$${{ github.run_id }}-
             |$prefix$${{ steps.cache-epoch.outputs.epoch }}-
             |$prefix$${{ steps.cache-epoch.outputs.release }}-
             |$prefix""".stripMargin,
        cache.`with`("path") == "~/.sbt\n~/.cache/sbt\n~/.cache/coursier\ntarget",
        fixed.`with`("key") == s"${prefix}1.2.3-ci-$${{ github.run_id }}-test",
        fixed.`with`("restore-keys") ==
          s"""${prefix}1.2.3-ci-$${{ github.run_id }}-
             |${prefix}1.2.3-ci-
             |${prefix}1.2.3-
             |$prefix""".stripMargin,
      )
    },
    test("a path containing a single quote is reported as a value rather than escaped around") {
      // Not a hypothetical: the paths are `plan` parameters fed from sbt settings, so they are genuinely runtime data.
      // A quote would break out of the surrounding '…' and the shell would see a different argument list than the
      // caller wrote, so `plan` reports it and names the offending path instead of producing a workflow.
      val bad = ActionPinsSyncWorkflow.plan(
        ActionPins.Defaults,
        javaVersion = "25",
        runnerOs = "ubuntu-latest",
        actionsPath = ".github/it's-a-trap.yml",
      )
      assertTrue(
        bad.isLeft,
        bad.left.exists(_.contains("it's-a-trap.yml")),
        bad.left.exists(_.contains("single quote")),
      )
    },
  )

  /** The verify capability's sbt step, the last step of its job. */
  private def verifyStep(config: PlanConfig, capability: Capability = Capability.test): Step =
    Planner.plan(Fixtures.sampleGraph, List(capability), config).jobs(capability.name).steps.last

  /** The `actions/cache` step of the `test` job. */
  private def cacheStep(config: PlanConfig): Step =
    Planner
      .plan(Fixtures.sampleGraph, List(Capability.test), config)
      .jobs("test")
      .steps
      .find(_.uses.exists(_.startsWith("actions/cache@")))
      .getOrElse(throw AssertionError("cache step missing"))

  /** The `compute` step's script, which is `private` in the planner and so is read back off the plan. */
  private def affectedScript(affectedOnPush: Boolean): Option[String] =
    Planner
      .plan(
        Fixtures.sampleGraph,
        List(Capability.testGraph),
        PlanConfig(affected = AffectedMode.AffectedOnPR, affectedOnPush = affectedOnPush),
      )
      .jobs(Planner.affectedJobId)
      .steps
      .find(_.id.contains("compute"))
      .flatMap(_.run)

end ScriptRenderSpec
