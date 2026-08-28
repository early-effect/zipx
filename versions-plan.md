# Independent module versioning for zipx consumers

Discussion plan. Product forks are locked. Ready to turn into a zipx design doc when you want to implement.

## Locked

- **Release signal:** a catalog version-row change merged to the default branch. CI publishes only modules whose row moved. No git tag is the version. Optional `core-v1.4.3` tags can be an *output* after a successful publish. Idempotency: skip a GAV that is already on the registry (Central rejects republish of the same version).
- **Where it lives:** inside the zipx repo, on the existing catalog machinery. Not a sibling plugin. Feature flag is presence of `Ship` / `ShipGroup` rows (no rows = today's dynver-ci / `v*` path).
- **Row types:** `Ship("core", "1.4.2")` for one project. `ShipGroup("foo", "1.4.2")("foo-api", "foo-cli", "foo-impl")` for tightly coupled projects that always share a number and publish together. Identity of a `Ship` is the sbt project id. Identity of a group is the group name; members are project ids. `catalog update` never touches these rows. Each `publishes = true` project is in **exactly one** `Ship` or `ShipGroup` (generate fails on missing or duplicate membership).
- **Bump declaration:** in-PR catalog edit of `Ship` / `ShipGroup` rows. Any dirty member dirties the **group**: one version moves, every member publishes.
- **Bump size:** human writes the number. CI **suggests** (MiMa-informed) via a PR comment that contains the `ZipxVersions` edit. The human may over-bump or pick a different legal number. **Undersized or missing bumps fail the check.** Disregard means "bump more than suggested," not "skip" and not "bump less."
- **Dependent propagation:** default `Never`. Built-ins also include `PatchPublished`, `MatchBump` (published reverse-deps inherit at least the triggering bump), and `custom`.
- **Suggestion UX:** one sticky PR comment, updated per push, with a GitHub suggested-change hunk on the `Ship` line (fenced fallback). CI never commits the edit. Fork comments are best-effort; the check is the gate.
- **Lockstep OSS:** unchanged. zipx / specular / chekhov stay on `sbt-dynver-ci` + `ZipxCentral.release` + `v*`. ROADMAP M9 still recommends that path. Independent versioning is opt-in for multi-artifact repos.

## The gap (unchanged)

zipx owns topology, not artifact versions. Today's topology assumes one repo-wide version:

| Piece | Version assumption |
|---|---|
| `sbt-dynver-ci` | One `v*` tag → every module is that version (or `…-ci`) |
| `Gate.OnReleaseTag` | A tag is a repo-wide release |
| `CacheEpoch.GitTags()` | One cache generation for the build |
| `zipxAffectedPublish` | A release tag still publishes **everything** |
| `ZipxVersions` `Lib` / `Plugin` / `Pin` / `Action` | What this repo *depends on*, not what it *ships* |

That is correct for one-product OSS libraries. It is wrong for a monorepo that publishes several artifacts on different cadences.

## Affected is necessary but not sufficient

`zipx.core.Affected` answers "which jobs can we skip." A version manager answers "which coordinates move, to what, and when is upload legal."

| Set | Rule | Failure mode |
|---|---|---|
| Test | reverse-dep closure of owning modules | fail **open** (`all`) |
| Bump | owning **published** modules (own sources / own published POM) | fail **closed** |
| Publish | modules whose catalog row moved vs last successful publish / registry | fail **closed** |

Do not reuse `affectedModules` as the bump/publish set. Add a sibling pure function in `zipx-core` (ownership + `ModuleNode.publishes`, no reverse-dep, no "`.sbt` means all" for version-row edits). Then lift through groups: if any member is in the bump set, the **group** is in the bump set; the publish set is **every member** of a group whose version moved (even members with no source change this PR). Verify's Affected stays as it is.

`core/version.sbt` is rejected: it is a `.sbt` file, so today's Affected would force every module. Versions live in `project/ZipxVersions.scala` as a new row type.

## Why inside zipx, on ZipxVersions

The catalog already has the shape this needs:

- Typed rows (`Lib`, `Plugin`, `Pin`, `Action`) collected from every `val` via `AsCoords` / `AsPins` / `AsActions`.
- Constructor rewrite (not regex, not whole-file regen) used by `zipx-cli catalog update` and in-sbt apply.
- `zipxCheckDeps` fails undeclared GAVs.
- CLI sits *above* the target sbt so a breaking row cannot kill Sunday.

Outbound versions are a fourth collection, not a reuse of `Lib`. A `Lib` is a GAV this build *consumes*. A shipped module is an sbt project id plus the version *this* build assigns. Same file, different row, different apply command.

```scala
// project/ZipxVersions.scala
object MyVersions extends ZipxVersions:
  val zio  = Lib("dev.zio", "zio", "2.1.26")
  val core = Ship("core", "1.4.2")
  val cli  = Ship("cli",  "0.3.0")
  val foo  = ShipGroup("foo", "1.4.2")("foo-api", "foo-cli", "foo-impl")

  def libraries = library(zio)
  // published rows: every Ship / ShipGroup val. Group members share one version constructor.
```

```scala
// build.sbt
MyVersions.settings          // plus: each Ship id gets version := row
lazy val core = project.settings(MyVersions.libraries)
```

Rules that keep the two catalogs from trampling each other:

1. **Sunday `catalog update` never touches `Ship` / `ShipGroup` rows.** Those versions are not Maven-latest; they are this repo's release intent. A new CLI/task (`zipxModverBump core` / `zipxModverBump foo`) rewrites `Ship(` / `ShipGroup(` the same way dep apply rewrites `Lib(`.
2. **Presence of any `Ship` or `ShipGroup` row is the feature flag.** No rows: dynver-ci may own `version`, Aggregate `ZipxCentral.release` on `v*` stays legal. Any row: dynver-ci owning `version` is refused at load/generate; Aggregate world-publish is refused; Graph publish filtered by version-moved is required; every `publishes = true` module is in exactly one row (check-deps analog).
3. **Between releases, apply the dynver-ci trick per row:** if this commit is not a release of that `Ship` / `ShipGroup`, `version` is `<row>-ci`. Jar names stay stable; bumping the row is a new cache generation for those modules without a git tag.
4. **Identity is the sbt project id** (or a group of them), not GAV. `organization` / `name` stay in `build.sbt`. Cross-built platform rows (`core` / `coreJS`) are **not** a `ShipGroup`: they are one product already, so one `Ship("core", …)` applies to every publishing matrix row of that id. `ShipGroup` is for *different* sbt projects that ship as one product (`foo-api` / `foo-cli` / `foo-impl`).

This also means independent versioning is **not** adoptable without zipx. That is the trade you picked: paved path over a standalone plugin. A repo that only wanted per-module `version` and hand-rolled CI is out of scope.

## Version groups (tightly coupled modules)

Two `Ship` rows with the same literal can drift: someone patches `foo-api` and forgets `foo-cli`. A group makes that unrepresentable: one constructor, one number, one release.

```scala
val foo = ShipGroup("foo", "1.4.2")("foo-api", "foo-cli", "foo-impl")
```

| Event | What happens |
|---|---|
| `foo-api` sources change | Group is in the bump set. Suggested edit is the `ShipGroup` constructor. |
| Human bumps `foo` 1.4.2 → 1.4.3 | All three members get `version := 1.4.3` (or `1.4.3-ci` until that commit is the release). |
| Merge to default branch | Publish **all three**, even if `foo-cli` / `foo-impl` had no source change this PR. Consumers take the product as a unit. |
| `foo-cli` `dependsOn` `foo-api` | Not a propagate edge. Same row. |
| `client` `dependsOn` `foo-api`, propagate `MatchBump` | `client` (if it is its own `Ship`) inherits at least `foo`'s bump. |

A group of one is just `Ship`. A group of every published module is lockstep-in-the-catalog (what dynver-ci does with a git tag, without the tag). Mixed repos are the point: `ShipGroup("foo", …)` next to `Ship("unrelated-util", …)`.

Generate refuses: empty member list; member that is not a project id; member with `publish / skip`; the same project in two rows; a `Ship` and a `ShipGroup` overlapping.

Optional output tag after successful publish is `foo-v1.4.3` (group name), not three tags, unless we later add per-member annotations. Deferred with the other output-tag detail.

## Bump size: suggest on the PR, refuse undersized, never silently rewrite

You leaned toward human-written versions and asked CI to **detect + comment with the catalog edit**, using MiMa to suggest, with the human free to disregard, and undersized bumps still failing. That is the product.

Two jobs, not one:

| | Suggest (comment) | Gate (check) |
|---|---|---|
| Missing bump | "core is in the bump set; suggested `Ship(\"core\", \"1.4.3\")`" (or the `ShipGroup` constructor if a member is dirty) | fail until the row moves |
| Undersized | "MiMa vs 1.4.2 is a binary break; suggested `Ship(\"core\", \"2.0.0\")`" | fail until the number is at least that |
| Over-bump | no nag (or a note, not a fail) | pass |
| Legal disregard | human commits a *larger* bump than suggested, or a different legal number | pass |
| Illegal disregard | human leaves the row, or writes a smaller bump | fail |

CI never writes `project/ZipxVersions.scala` on the PR branch. Auto-commit would take away "free to disregard." The comment *is* the ZipxVersions edit (GitHub suggested-change hunk if the lines still match, otherwise a fenced `Ship(...)` line). One sticky comment, updated on each push, not a new comment per SHA.

`zipxModverBump core` locally still defaults to patch so you can apply without waiting on CI. The PR path does not require that command.

### Why MiMa suggests but does not apply

- Binary break ≠ product major (0.x / `early-semver`, experimental modules, "I am taking this break").
- Behavioral breaks and Scala.js-only API: MiMa is silent, so a patch suggestion can still be something the human upgrades.
- zipx refuses rather than guesses. The comment is a guess you can ignore *upward*. The check is the refuse.

### When the suggestion runs

MiMa needs classfiles of this PR and the previous artifact (the `Ship` version on the default branch, which is what Central has after the last publish). So this is not a cheap path-only job. It sits after compile of the bump-set modules (Graph test jobs are the natural home: they already compiled those modules).

Cross-built: one `Ship` row for the matrix root; MiMa on the JVM row is the oracle for that number. JS-only modules with no JVM row skip MiMa and get a patch suggestion unless the human writes more.

`ShipGroup`: min-bump is the **max** of members that have a previous artifact. If `foo-api` is a binary break and `foo-cli` is a patch, the group constructor is suggested as major. The sticky comment edits that one constructor, not three `Ship` lines.

No previous artifact (first `Ship` / first publish): any version is legal; the comment is "add a `Ship` row," not a bump.

Fork PRs: commenting often cannot use `GITHUB_TOKEN`. Comment is best-effort; the check is the gate. Same fail-closed vs fail-open split as publish vs verify.

### `versionScheme`

early-effect already sets `early-semver`. The minimum bump MiMa implies must honor that (a break in 0.y.z is not always "need 1.0.0"). Implementation can speak MiMa directly or sit on `sbt-version-policy`; the product is the min-bump number in the comment and the same number in the gate.

### Propagate belongs in the suggestion

If `zipxModverPropagate` is `MatchBump` or `PatchPublished`, the sticky comment proposes the **whole catalog edit** (core + published reverse-deps), not just the owning module. The gate also runs on the post-propagate set, so a `MatchBump` repo cannot merge `core` minor with `client` still at the old number.

## Dependents: why "never" was the default, and the escape hatch

The plan's "never auto-bump dependents" was **default behavior**, not "no escape hatch." The question options already included configurable. You asked for custom handling too. Here is the rationale, then the built-ins.

Independent versioning means `client`'s version means `client`'s changes. If `core` 1.4.2 → 1.4.3 always patch-bumps `client` 2.3.0 → 2.3.1, then:

- `client`'s changelog becomes "core moved" even when `client` sources did not.
- You have reconstructed lockstep with extra steps.
- If `client` re-exports `core` types, a **core major** is often a **client major**, not a patch. Auto-patch is then *wrong*, not merely noisy.

In-repo `dependsOn` does not care: compile is a project ref. The only consumer who cares is someone who depends on *published* `client` and wants a new POM that points at new `core`. That person can bump `client` in the same PR. That is a real release of `client`.

Safety does **not** come from auto-bumping. It comes from the publish filter: you may only upload modules whose `Ship` row moved. That makes "forget to bump `client` but Aggregate-publish it anyway" unrepresentable. Old `client` 2.3.0 on Central still depends on `core` 1.4.2. Correct.

When auto-patch *is* wanted: an internal platform where the company deploys the whole stack and "everything that POM-depends on a newly released lib gets a new coordinate" is the policy. That is a legitimate other product. It is not the default.

Proposed policy, as Scala in the build (semantics in the build, topology in zipx):

```scala
zipxModverPropagate := ModverPropagate.Never            // default
zipxModverPropagate := ModverPropagate.PatchPublished
zipxModverPropagate := ModverPropagate.MatchBump
zipxModverPropagate := ModverPropagate.custom { (bumps, graph) => ... }
```

- **Never (default):** bump set = owning published modules only.
- **PatchPublished:** every published reverse-dep of a bumped module that did not already move gets a patch. Human (or a bigger bump already in the PR) wins: `max(existing, patch)`.
- **MatchBump:** those dependents inherit *at least* the triggering bump (core minor → client minor, core major → client major). Right for a stack that is consumed as a unit; wrong if `client` does not re-export `core` (a core major is then often still a client patch). That is why it is not the default.
- **custom:** `(Map[ModuleId, BumpKind], ModuleGraph) => Map[ModuleId, BumpKind]`. Allowlists, "core major ⇒ client major but util patch," etc.

Intra-group `dependsOn` is not propagation. `foo-cli` depending on `foo-api` is already the same `ShipGroup` version. `PatchPublished` / `MatchBump` only walk **across** groups (and lone `Ship`s). A group in the bump set is one node: dependents of *any* member are dependents of the group.

`modverCheck` and the sticky comment both run *after* propagate.

## Topology pack (follows from the locks)

Independent mode cannot use today's `ZipxCentral.release`:

- `Gate.OnReleaseTag` is repo-wide `v*`.
- Aggregate `publishSigned` of the whole build re-uploads unchanged coordinates.
- `zipxAffectedPublish` still publishes everything on a tag.

The pack (name TBD, conceptually `ZipxModver.release` or an extended `ZipxCentral`):

- Verify: as today, plus a Once `modverCheck` (+ `modverCompat`) job.
- Publish: Graph only; job runs if that module's `Ship` row moved vs last successful publish (registry or output tag). Fail closed if the diff cannot be computed.
- Generate refuses: `Ship` rows + dynver-owned version; `Ship` rows + Aggregate world-publish; `Ship` rows + `OnReleaseTag` as the only publish gate.
- Docs (`ZipxDocs` on `v*` or dispatch): leave dispatch; do not silently change Pages.
- Cache epoch: `GitTags()` can stay as a conservative repo-wide namespace; not v1-blocking.

## What v1 will not do

- Infer bump size from conventional commits.
- Per-module `version.sbt`.
- Auto-apply a MiMa-suggested version.
- Change Verify Affected to fail closed.
- Docker image tags (sha / moving tags stay a different axis).
- Dogfood on zipx-the-product (it is lockstep on purpose). `examples/monorepo` is the dogfood; give it a `ShipGroup` plus an independent `Ship` so both paths are visible.

## PR-shaped slices (when we design)

1. `zipx-core`: `Ship` / `ShipGroup` rows + `AsShips` collection + fail-closed bump/publish set with group lift.
2. Catalog rewrite for `Ship(` / `ShipGroup(`; `zipxModverBump`; settings that assign `version` per module (group members share); load/generate refuse with dynver-ci and overlapping membership.
3. After compile: min-bump via MiMa / `versionScheme`; sticky PR comment with the `Ship` edit(s); `zipxModverCheck` + `zipxModverCompat` fail on missing or undersized.
4. `ModverPropagate` Never / PatchPublished / MatchBump / custom; comment and check use the post-propagate set.
5. Topology: Graph publish filtered by version-moved; generate refuses; comment step best-effort on forks.
6. Docs: when to pick dynver-ci vs `Ship` rows; example in `examples/monorepo`.

## Deferred (not product forks)

- Cross-built: one `Ship` per matrix root (locked as the default above) vs a row per platform id if someone has a real split.
- MiMa directly vs `sbt-version-policy` for min-bump.
- Pack name (`ZipxModver.release` vs folding into `ZipxCentral`).
- Optional output tags `core-v1.4.3` after successful publish.
- Cache epoch for independent mode (keep `GitTags()` until it hurts).

