package zipx.core

/** How LocalDir `actions/cache` keys choose their commit-stable "epoch" namespace.
  *
  * The epoch keeps mid-PR pushes sharing hits and rolls when a release lands. Strategies differ in *when* the epoch
  * string is known: bake at `zipxWorkflowGenerate` time ([[Fixed]]), or resolve on the runner ([[GitTags]],
  * [[Script]]).
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
    *   glob passed to `git describe --match` / local tag listing (default `v*`).
    */
  case GitTags(tagMatch: String = "v*")

  /** User-supplied shell that must write `epoch=` and `release=` lines to `$GITHUB_OUTPUT`. */
  case Script(run: String, stepId: String = "cache-epoch")
end CacheEpoch

object CacheEpoch:

  /** Step id used by [[CacheEpoch.GitTags]]. */
  val GitTagsStepId: String = "cache-epoch"

  /** Shell for [[CacheEpoch.GitTags]]: sets `epoch` / `release` outputs; warns on missing or lagging tags. */
  def gitTagsResolveScript(tagMatch: String = "v*"): String =
    // Deterministic for PlannerSpec substring asserts. Avoid `/*` sequences in block comments (Scala 3 nests).
    s"""set -euo pipefail
       |tag_match='$tagMatch'
       |if git remote get-url origin >/dev/null 2>&1; then
       |  remote_count=$$(git ls-remote --tags --refs origin "$$tag_match" 2>/dev/null | wc -l | tr -d ' ')
       |  local_count=$$(git tag -l "$$tag_match" | wc -l | tr -d ' ')
       |  if [ "$$remote_count" -gt 0 ] && [ "$$local_count" -lt "$$remote_count" ]; then
       |    echo "::warning title=zipx cache epoch::Local tags ($$local_count) fewer than origin ($$remote_count). Epoch may be stale; checkout must use fetch-depth: 0 and fetch-tags: true."
       |  fi
       |fi
       |if [[ "$${GITHUB_REF:-}" == refs/tags/v* ]]; then
       |  release="$${GITHUB_REF#refs/tags/v}"
       |  epoch="$$release"
       |elif tag=$$(git describe --tags --abbrev=0 --match "$$tag_match" 2>/dev/null); then
       |  release="$${tag#v}"
       |  epoch="$${release}-ci"
       |else
       |  echo "::warning title=zipx cache epoch::No tags matching '$$tag_match' found; using epoch 0.0.0."
       |  release="0.0.0"
       |  epoch="0.0.0"
       |fi
       |echo "epoch=$$epoch" >> "$$GITHUB_OUTPUT"
       |echo "release=$$release" >> "$$GITHUB_OUTPUT"
       |""".stripMargin

end CacheEpoch
