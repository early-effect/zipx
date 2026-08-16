# zipx Roadmap

A self-describing CI plugin for Scala monorepos: Scala 3 libraries plus an **sbt 2.x AutoPlugin** that lets a build
describe its own dependency-ordered GitHub Actions pipeline (test, library publish, docker publish, deploy) with
pluggable caching.

**zipx thesis:** the sbt build is the single source of truth. An sbt task introspects the real graph and generates (and
check-verifies) a GHA workflow. Topology lives in zipx; what to run lives in the build as typed `Capability` values and
Scala packs.

Live behavior is documented in Specular (`docs/`), not here. Git history records what shipped. This file stays
**forward-looking**.

**Status legend:** done · in progress · not started

| Milestone | Status |
|---|---|
| M0–M8, M9a, M10 (skeleton through zipx-aws, Aggregate/Layer/Graph, typed DSL, Central) | done |
| M12a: In-repo composites + MatrixCollapse.Auto (default size reduction) | done |
| M9: Dynver-ci + publishSigned auto-detect | not started |
| M11: "Extend with Scala" docs & org rollout | not started |
| M12: Typed SbtCommand (keys in plugin, wire form in core) | in progress |

## Decisions locked

- **Scope:** whole pipeline (test → build → library publish → docker-image publish on the sbt-native-packager paved path).
- **Workflow generation:** own GHA AST + deterministic YAML + check task (not sbt-github-actions' single-matrix model).
- **Caching:** `CacheBackend` abstraction (local or remote).
- **Action pins:** SHA pins; editable source is ZipxVersions `Action` vals. YAML is jar/generate output, never an input.
- **Secrets:** zipx renders secret *references*; packs name org secrets. Values never enter the plugin.
- **Extension language:** Scala (`Capability`, `Steps`, `Expr`, packs), not external YAML soup. Raw escape hatches stay typed and generate-time warned.
- **Refuse rather than drop:** a field the planner cannot honor fails generate with an explaining error.
- **sbt owns its API:** core holds `SbtCommand` wire form only; the plugin builds commands from real `TaskKey` / `Command` values (`zipxTasks.of`, `ZipxCentral.release` from `publishSigned` + `sonaRelease`).

## Central design principle

**zipx owns topology; the build owns what to run.** Topology = graph, layers, `needs`, matrix, gating, environments,
env injection, target fan-out, cache wiring. Semantics live in Scala packs on the meta-build classpath.

## Forward

### M9: Dynver-ci + publishSigned auto-detect

- Recommend `sbt-dynver-ci` alongside zipx for a PR-stable *build* version (cache epoch already has `CacheEpoch.GitTags`).
- When `sbt-pgp` is on the classpath, default publish toward `publishSigned` with an explicit override (`zipx-central`
  already covers the pack path).

### M11: "Extend with Scala" docs & org rollout

- First-class guide: typed config, `zipxTasks`, `Expr` / secrets, `Steps`, packs (`zipx-central`, `zipx-aws`).
- Org: publish `0.1.0`, adopt in early-effect libraries, prefer generated topology over hand-maintained release YAML,
  adopt `zipx-aws` instead of copied OIDC/ECR blocks.

### M12: Typed SbtCommand

- **Done / landing:** step wire form in core; `CommandSource` + `runningEach` / `thenOnce`; plugin `zipxTasks.of` /
  project-axis honouring; `zipxTestTask` as `SbtCommand` defaulting to `testFull`; plugin `ZipxCentral.release` from
  real `publishSigned` + `sonaRelease`; `zipxCheckCommandNames`; Specular **Composing sbt commands** page.
- **Still open:** move Coverage / VerifyClean / docker command construction fully onto plugin keys where those plugins
  are on the classpath; consumer migrations (mechanoid / saferis / marklit) after release.

### Pin feeds

Issue [#105](https://github.com/early-effect/zipx/issues/105). Typed `PinFeed` so zipx owns topology, Ignore / Report /
Update policy, OSV, and catalog rewrite. Inventory is catalog `Pin` vals (`zipxPins`), not a list on the feed. Lookup
returns a `PinCandidate` (version plus checksum / PURL). Optional `materialize` writes extra files after the catalog
rewrite. The PR advisory merge gate folds into the builtin **advisories** job (`zipxAdvisoryCheck`). Scheduled outdated/apply and snapshot
submit stay companion workflows (cron is not a `Gate`; PR snapshots pollute the Security tab). Local
`zipxPinUpdate` lists outdated pins and rewrites `Pin(...)` after approval, ignoring `PinAction` so alert-only feeds can
still bump before a PR. Not an M12 item. First consumer feed is sbt-splice, in that repo.

### Versions catalog

Typed `Lib` / `Plugin` / `Pin` / `Action` rows in `project/ZipxVersions.scala`. Apply rewrites those constructors (not a regex over
the build, not a whole-file regen); generate writes `plugins.sbt` / `build.properties`; `zipxCheckDeps` fails generate
when `libraryDependencies` contain a GAV that is not a `Lib` row. A `Pin` with no matching `PinFeed` fails generate.
Default `zipxVersionUpdates` emits `.github/workflows/zipx-version-updates.yml`: schedule plus dispatch, `zipxDepUpdate yes` /
`zipxActionUpdate yes` / `zipxPinUpdate yes`, `zipxCatalogGenerate` (not workflow YAML; `GITHUB_TOKEN` cannot push `.github/workflows`), `gh pr create` on `zipx/version-updates-$GITHUB_RUN_ID` labeled `clean`. Local `zipxDepUpdate` / `zipxPinUpdate` /
`zipxActionUpdate` are the same apply. Pin policy (lookup, OSV, Update) still lives on `PinFeed`. ZipxVersions is required;
leftover `zipx-scala-steward.yml` fails generate. Not an M12 item.

### Design guardrails

1. Topology in zipx; semantics in Scala packs.
2. Generate-time resolution; deterministic YAML for `zipxWorkflowCheck`.
3. Org secrets by name, never value.
4. sbt 2.0 remains the unlock; do not regress to sbt 1.x shapes.
5. No stringly-typed public API where a validated newtype exists; failures are unrepresentable > compile-time check >
   `Either` > throw only at the sbt boundary. `grep -rn "throw \|makeOrThrow\|orThrow" modules/*/src/main` must stay empty.

## Verification (how we prove changes)

Always `testFull`, never plain `test` (sbt 2's `test` is `testQuick`). Prefer Metals for format and focused suite runs
while iterating; CI Aggregate `test` runs unit/IT (including live remote-cache via Testcontainers; Docker required),
`plugin/scripted`, and the examples/monorepo workflow check.

Agent-oriented blast radius (docs vs packs vs `allJobIds` laws): **[AGENTS.md](AGENTS.md)**.
