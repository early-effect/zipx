package zipx.core

import zio.test.*
import zipx.workflow.Step

object ScriptRenderSpec extends ZIOSpecDefault:

  def spec = suite("migrated scripts render byte-identically to their pre-DSL strings")(
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
          """if [ -z "$(git status --porcelain '.github/zipx/action-pins.yml' '.github/workflows/ci.yml' '.github/workflows/zipx-action-pins-sync.yml' '.github/actions/zipx-sbt-setup/action.yml' '.github/actions/zipx-aws-login/action.yml')" ]; then
            |  echo "No pin/workflow changes to commit."
            |  exit 0
            |fi
            |git config user.name "github-actions[bot]"
            |git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
            |git add '.github/zipx/action-pins.yml' '.github/workflows/ci.yml' '.github/workflows/zipx-action-pins-sync.yml' '.github/actions/zipx-sbt-setup/action.yml' '.github/actions/zipx-aws-login/action.yml'
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
    test("the affected-modules script, with the push branch, indenting the nested assignment to four spaces") {
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
      val matrixed = Planner
        .plan(Fixtures.sampleGraph, List(Capability.testGraph), PlanConfig(verifyCleanLabel = None))
        .jobs("test-schema")
        .steps
        .last
      assertTrue(
        verifyStep(PlanConfig(verifyCleanLabel = None)).run.contains("sbt 'test'"),
        verifyStep(PlanConfig(verifyClean = VerifyClean.CleanFull)).run.contains("sbt 'cleanFull; test'"),
        matrixed.run.contains("sbt '++${{ matrix.scala }}; schema/test'"),
      )
    },
    test("a verify-clean label containing a quote is not a value that can reach a plan") {
      for bad <- typeCheck("""zipx.core.PlanConfig(verifyCleanLabel = zipx.core.PlanConfig.verifyCleanLabel("it's"))""")
      yield assertTrue(
        bad.isLeft,
        PlanConfig.verifyCleanLabelMake("it's clean").isLeft,
        PlanConfig.verifyCleanLabelMake("clean").isRight,
      )
    },
    test("the cache key and restore-keys, assembled from Expr rather than interpolated") {
      val action = ZipxComposites.sbtSetup(ActionPins.Defaults, CacheEpoch.GitTags())
      val cache  =
        action.steps.find(s => s.name.contains("Cache sbt") && s.`if`.exists(_.contains("cache-epoch == ''"))).get
      val fixed =
        action.steps.find(s => s.name.contains("Cache sbt") && s.`if`.exists(_.contains("cache-epoch != ''"))).get
      val key     = cache.`with`("key")
      val restore = cache.`with`("restore-keys")
      assertTrue(
        key.startsWith("${{ inputs.runner-os }}-jdk${{ inputs.java-version }}-sbt-"),
        key.contains("${{ steps.cache-epoch.outputs.epoch }}-${{ github.run_id }}-"),
        key.endsWith("${{ inputs.cache-key-suffix }}"),
        restore.contains("${{ steps.cache-epoch.outputs.epoch }}-${{ github.run_id }}-"),
        restore.contains("${{ steps.cache-epoch.outputs.release }}-"),
        cache.`with`("path") == "~/.sbt\n~/.cache/sbt\n~/.cache/coursier\ntarget",
        fixed.`with`("key").contains("${{ inputs.cache-epoch }}-${{ github.run_id }}-"),
        fixed.`with`("restore-keys").contains("${{ inputs.cache-epoch }}-"),
      )
    },
    test("LocalDir jobs call the zipx-sbt-setup composite") {
      val step = Planner
        .plan(Fixtures.sampleGraph, List(Capability.test), PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.2.3-ci")))
        .jobs("test")
        .steps
        .head
      assertTrue(
        step.uses.contains(ZipxComposites.SbtSetupRef),
        step.`with`.get("cache-epoch").contains("1.2.3-ci"),
      )
    },
    test("a path containing a single quote is reported as a value rather than escaped around") {
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

  private def verifyStep(config: PlanConfig, capability: Capability = Capability.test): Step =
    Planner.plan(Fixtures.sampleGraph, List(capability), config).jobs(capability.name).steps.last

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
