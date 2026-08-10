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
| M9: Dynver-ci + publishSigned auto-detect | not started |
| M11: "Extend with Scala" docs & org rollout | not started |

## Decisions locked

- **Scope:** whole pipeline (test → build → library publish → docker-image publish on the sbt-native-packager paved path).
- **Workflow generation:** own GHA AST + deterministic YAML + check task (not sbt-github-actions' single-matrix model).
- **Caching:** `CacheBackend` abstraction (local or remote).
- **Action pins:** SHA pins; editable source `.github/zipx/action-pins.yml`; typed fields plus `extra:` for pack/consumer actions.
- **Secrets:** zipx renders secret *references*; packs name org secrets. Values never enter the plugin.
- **Extension language:** Scala (`Capability`, `Steps`, `Expr`, packs), not external YAML soup. Raw escape hatches stay typed and generate-time warned.
- **Refuse rather than drop:** a field the planner cannot honor fails generate with an explaining error.

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

### Design guardrails

1. Topology in zipx; semantics in Scala packs.
2. Generate-time resolution; deterministic YAML for `zipxWorkflowCheck`.
3. Org secrets by name, never value.
4. sbt 2.0 remains the unlock; do not regress to sbt 1.x shapes.
5. No stringly-typed public API where a validated newtype exists; failures are unrepresentable > compile-time check >
   `Either` > throw only at the sbt boundary. `grep -rn "throw \|makeOrThrow\|orThrow" modules/*/src/main` must stay empty.

## Verification (how we prove changes)

Always `testFull`, never plain `test` (sbt 2's `test` is `testQuick`). Prefer Metals for format and focused suite runs
while iterating; CI runs the full matrix including `docs/testFull`, `plugin/scripted`, and `it/test` (Docker).
