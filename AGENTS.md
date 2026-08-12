# AGENTS.md

Guidance for agents working in **zipx**. Human roadmap and design constraints: [ROADMAP.md](ROADMAP.md). Contributing layout: Specular **Developing**.

## What this repo is

sbt plugin that **generates** GitHub Actions (and in-repo composites under `.github/actions/zipx-*`) from the real module graph. Generated YAML is an output: commit it, drift-gate with `zipxWorkflowCheck`. Prefer typed APIs over stringly YAML.

## Verification blast radius

sbt 2: always **`testFull`** for proof (`test` is `testQuick` and can skip). Prefer Metals compile-file → Metals test while iterating; use sbt for scripted, workflow generate/check, and full-module proof.

When you change **emission shape** (Planner, composites, packs, pins, MatrixCollapse), re-run every layer that asserts on rendered YAML, not only the module you edited:

| Change touches | Also prove |
| --- | --- |
| `modules/core` planner / composites / collapse | `core/testFull`, then **`docs/testFull`** (Specular examples lock job YAML) |
| `modules/aws` / `central` packs | pack `testFull` **and** docs pages that show that pack (`Packs`, `ActionPinsDoc`, `Caching`, …) |
| Generated `.github/**` or plugin emission | `zipxWorkflowCheck`, **`plugin/scripted`** (asserts on ci.yml + composites), monorepo example check when those change |

Pack specs and Specular examples are **different suites**. Updating `ZipxAwsSpec` does not update `docs/.../Packs.scala`. CI Aggregate `test` runs docs; a green local pack suite is not enough before push.

## Laws vs examples

- **Property suites** (core / packs): encode laws (`allJobIds` matches emitted job keys for every mode that plans; Auto expands when collapse is infeasible). Generate only inputs that exercise the law. If some `MatrixCollapse` modes refuse generate on a fixture, use a narrower gen (e.g. Auto/Off) or a separate refusal test with a real `zipx:` error assert. Never `case Left(_) => assertTrue(true)`.
- **Specular DocSpecs**: one concrete render per teaching point. Keep them example-locked to the page story. Do not turn docs examples into MatrixCollapse PBTs; put those laws in module specs.

## Planner invariants agents break most often

- **`Planner.allJobIds` must match what `plan` emits** under the same `MatrixCollapse.effective` mode. `MatrixCollapse.Auto` soft-fails (expands) when collapse is not feasible; dependents' `needs` must list the expanded ids, not the collapsed capability name.
- **Checkout before local composites:** workflows must `actions/checkout` before `uses: ./.github/actions/zipx-*`. Composites must not assume the repo is already on disk for nested pins alone.
- After planner output changes: `reload` (or fresh sbt), then `zipxWorkflowGenerate` / check dogfood + examples as needed.

## Docs preview

```
sbt docsDev    # ~docs/specularPreview on http://127.0.0.1:8765/
```

Killing the sbt server kills `docsDev`. Restart deliberately; do not assume the preview still serves.

## Format and PRs

Metals format while editing; before push run `sbt scalafmtCheckAll` (pre-commit may skip under the agent sandbox) then test (as fmt could break code). No AI attribution in commits or PR text. Do not force-push `main`.
