# zipx — Roadmap

A self-describing CI plugin for Scala monorepos: a set of Scala 3 libraries plus an **sbt 2.x (2.0.1) AutoPlugin** that lets a Scala monorepo *describe its own* fast, concurrent, dependency-ordered GitHub Actions pipeline — test, library publish, and docker-image publish — with pluggable Bazel-style caching.

**Status legend:** ✅ done · 🚧 in progress · ⬜ not started

| Milestone | Status |
|---|---|
| M0 — Skeleton & trust | ✅ |
| M1 — Vertical slice (test workflow) | ✅ |
| M2 — Dependency-ordered library publish | ✅ |
| M3 — Affected-only execution | ✅ |
| M4 — Docker paved path + POC | ✅ |
| M5 — Remote caches | ✅ |
| M6 — Environments, approval & multi-target deploys | ✅ |
| M7 — Typed secrets & capability env | ✅ |
| M8 — `zipx-central` + dogfood Central publish | ✅ |
| M9a — Aggregate-first + Layer + deploy-by-target | ✅ |
| M9 — Dynver-ci + publishSigned auto-detect | ⬜ |
| M10 — `zipx-aws` (on second consumer) | ⬜ |
| M11 — "Extend with Scala" docs & org rollout | ⬜ |

## Context

A common way to drive CI for a Scala monorepo is a hand-maintained external config (a YAML file, often plus a resolver script) that re-declares the module list, their inter-dependencies, and per-module test/build/publish/deploy recipes as free-text command strings. That approach has four recurring failure modes zipx exists to eliminate:

1. **Two sources of truth that drift** — the module set + edges live in *both* the external config and `build.sbt`'s `dependsOn`. Add, rename, or re-wire a module and the two silently desync.
2. **Publish order not modeled in CI** — the real dependency graph exists only in sbt, so the release step publishes every library **in parallel with no `needs`**, relying on upstream artifacts already existing in the registry (or recompiling them from source).
3. **No affected detection** — every PR builds/tests *all* modules; caching is the only mitigation.
4. **Stringly-typed module ids** copy-pasted across test/build/publish steps; a typo yields a green no-op instead of an error.

**zipx thesis:** the sbt build is the single source of truth. An sbt task introspects the real build graph and **generates** (and **check-verifies** in CI) a GHA workflow that fans out per-module jobs wired by `needs` derived from `dependsOn`, publishes in true dependency order, runs affected-only on PRs, and wires caching — all configured through sbt settings, **no external YAML config**.

## Decisions locked

- **Scope:** model the *whole* pipeline (test → build → library publish → **docker-image publish**), with images on the **paved sbt path** (sbt-native-packager `Docker / publish`) — the build describes its own image, rather than a separate `sbt assembly` + external `docker build` jar-copy. A POC example repo demonstrates this.
- **Workflow generation:** **build our own** GHA AST + deterministic YAML renderer + check task. Do **not** depend on sbt-github-actions' `GenerativePlugin` — its single-matrixed-job model can't express per-job `needs`.
- **Caching:** an **abstraction** (`CacheBackend`) — local-dir or remote selectable by config/availability.
- **Publishing:** a **registry-agnostic abstraction** — any publish mechanism plugs in; zipx owns ordering/gating, not the command.
- **Commit-stable cache keys:** the `actions/cache` primary key tracks a **commit-stable "cache epoch"** (`zipxCacheEpoch`, defaults to `version`) so mid-PR commits reuse the sbt action cache; integrates with the sibling `sbt-dynver-ci` plugin.
- **Action pins:** generated `uses:` values are **commit-SHA pins** (`ActionPins` / `zipxActions`), not floating tags. Defaults ship with zipx; consumers override to bump without waiting on a release.
- **Secrets:** zipx renders secret *references* into job `env:` / steps; it never stores secret *values*. Named GitHub secrets (org- or repo-scoped) are selected in Scala; convenience packs (e.g. `zipx-central`) name the early-effect org secrets and supply GPG-import steps. Semantics stay out of core.
- **Extension language:** people extend zipx with **actual Scala** — `Capability` values, typed `zipxTasks` / `cmd"…"`, `project/*.scala` typed config, and published meta-build libraries — not external YAML or stringly `${{ secrets.X }}` soup.

## Central design principle

**zipx owns *topology*; the build owns *what to run*.** Topology = the graph, topological layers, `needs` edges, matrix axes, gating, environment binding, env injection, target fan-out, and cache wiring — all derived. "What to run" is delegated to sbt tasks the build already defines, modeled as a pluggable **Capability**. Test, library-publish, and docker-publish are all `Capability` instances; a user can define custom ones. Cloud, registry, and Central-signing semantics live in **Scala packs on the meta-build classpath**, not in the planner.

## Module layout

- **`modules/workflow`** — `zipx.workflow`. GHA AST (`Workflow`, `Job`, `Step`, `Triggers`, `Strategy`, `Concurrency`) + deterministic YAML printer. Uses zio-blocks' schema-derived codecs to build the `Yaml` AST; **our own `YamlPrinter`** serializes it (adds literal block scalars zio-blocks' writer can't emit).
- **`modules/core`** — `zipx.core`. Graph model (`ModuleId`, `ModuleNode`, `ModuleGraph`), own deterministic toposort + layers + affected-closure, the `Capability` model, `CacheBackend`, `PlanConfig`, and the `Planner` (`ModuleGraph => Workflow`). Pure, sbt-free, unit-tested against a fixture mirroring the real graph. (M7 adds typed `EnvValue` / secret refs here.)
- **`modules/sbt-plugin`** — `zipx.sbt.ZipxPlugin`. The only module touching `sbt.*`: adapts build `State`/`structure`/`buildDependencies` into a `ModuleGraph`, defines `autoImport`, wires tasks.
- **Planned convenience packs** (meta-build Scala libraries, not more plugin magic):
  - **`zipx-central`** (M8) — early-effect / Maven Central org secrets, GPG import steps, `publishSigned` capability.
  - **`zipx-aws`** (M10, deferred) — OIDC + ECR helpers extracted from `examples/monorepo` once a second consumer needs them.

## Milestones

### M0 — Skeleton & trust ✅
Three modules; `zipxGraph` prints the resolved graph + topological layers. Introspection validated against a representative multi-module graph shape.

### M1 — Vertical slice (shippable) ✅
PerModule parallel **test** workflow, correct `needs` from `classpathRefs`, `LocalDir` cache with the **commit-stable `zipxCacheEpoch` key + `restore-keys` fallback**, deterministic generate+check round-trip.

### M2 — Dependency-ordered library publish ✅
Publish capability, publish-edge contraction (nearest same-capability ancestors), release-tag gating, per-module cross-scala matrix (including a 2.13-only publisher). Publishes in true dependency order instead of a flat parallel matrix — **headline feature**. Verified against the sample graph: `schema → {api, legacyClient} → {clientA, clientB}`.

### M3 — Affected-only ✅
A leading `affected` setup job (checkout `fetch-depth: 0`, run `zipxAffectedModules <base>`, output a JSON module array); Verify jobs gated with `if: contains(fromJson(needs.affected.outputs.modules), '<id>') || contains(..., 'all')`. On push/tag the job emits the `"all"` sentinel ⇒ full build. A `.sbt` change or anything under a `project` dir ⇒ full build; unowned files ignored. **The skipped-`needs` hazard** handled with `!cancelled()` + `needs.X.result != 'failure'` so an affected module still runs when an unaffected upstream is skipped. Pure file→module mapping (`Affected`, longest base-dir prefix) is unit-tested including pathological prefix/superstring/diamond cases; the git-diff path (`zipxAffectedModules`) verified against a scratch git repo. Controlled by `zipxAffectedOnPR` (default true).

### M4 — Docker paved path + POC ✅
`Capability.docker` runs `<module>/Docker/publish` (sbt-native-packager), release-gated, dependency-ordered, never matrixed. A module opts in simply by enabling `DockerPlugin` — zipx auto-detects it (`thisProject.autoPlugins`) and adds the docker stage only when some module uses it. Demonstrated in [examples/monorepo](examples/monorepo): the `service` module describes its image in the build (`dockerBaseImage`, `Compile / mainClass`, `dockerExposedPorts`) — no Dockerfile, no external `docker build` string. Verified end-to-end: `service/Docker/publishLocal` built `example-service:1.4.2-ci`, and `docker run` printed the greeting through the full `models → coreLib → service` chain.

### M5 — Remote caches ✅
Selectable via `zipxCache`:
- **`LocalDir`** (default) — `actions/cache` over sbt's local action-cache dir, commit-stable epoch key.
- **`BazelRemoteSidecar(image, port)`** — emits a `services:` block running `buchgr/bazel-remote` (gRPC) plus `env ZIPX_REMOTE_CACHE=grpc://localhost:<port>`. Verified rendering the nested `services:` mapping end-to-end.
- **`ManagedRemote(uri, headerSecret)`** — no sidecar; sets `ZIPX_REMOTE_CACHE` + `ZIPX_REMOTE_CACHE_HEADER` (from a repo secret) for a managed gRPC backend (BuildBuddy/EngFlow/NativeLink).

The plugin reads those env vars at load and wires `Global / remoteCache` + `remoteCacheHeaders` + `Global / cacheVersion` (inert when unset). The transport is bundled (zipx-sbt depends on `sbt-remote-cache`), and `cacheVersion` folds JDK+OS to keep heterogeneous remote pools sound — see the follow-ups section above.

### M6 — Environments, approval gates & multi-target deploys ✅

**Why.** Real monorepos need three more capabilities beyond test/publish/docker, plus a first-class extension seam:

1. **GitHub Environments + human-in-the-loop approval.** A deploy/publish job binds `environment: <name>`; production approval is a GitHub Environment protection rule (required reviewers) configured on the prod environment — the workflow binds the environment and GitHub pauses that job for approval. Each target is a separate job (`fail-fast:false`), so a prod target can hold for approval while staging targets proceed.
2. **Environment-specific config (staging vs production).** Each deploy target carries its own data — account/project id, region, credentials/role, a tier label (prod vs staging). This is exactly the join that external-config CI does with several YAML files + a resolver script; in zipx it becomes a typed function in the build.
3. **Multi-target publishing.** Build once → publish/deploy to N targets, each with its own credentials and environment; plus optional additional publish kinds (e.g. a second registry or delivery system) as downstream jobs.

**Decisions locked:** config lives in the **sbt build as typed Scala** (the resolver-script join becomes a typed function — no external YAML); approval = **GitHub Environment name on the job** (GitHub enforces; zipx emits no manual-approval steps); image **tags/aliases/registries delegate to sbt-native-packager** (`dockerAliases`/`Docker/publish` already do multi-tag/multi-repo); **extensibility for unforeseen cases is first-class**.

**Design principle (sharpened).** zipx owns **topology** — jobs, `needs`, matrix, gating, **environment binding**, **env injection**, **target fan-out** — and stays **semantics-agnostic** (knows nothing about specific registries, tier meanings, or tag schemes). Because config is resolved at **generate time**, zipx emits **explicit per-target jobs** (not a runtime JSON matrix): simpler, byte-stable for the check round-trip, and each target gets its own `environment:` for independent approval. Cloud/credential/tier resolution is **user Scala producing `List[Target]`**, not zipx code.

**New/changed types:**
```scala
// zipx-workflow: Job gains
environment: Option[String] = None    // renders `environment: <name>`

// zipx-core:
final case class Target(                 // a deploy/publish destination, resolved at generate time
  name: String,                          // job-id suffix, e.g. "us-prd"
  environment: Option[String] = None,    // GitHub Environment → approval gate
  env: Map[String, String] = Map.empty,  // injected into the job's env: (account, region, role, tier…)
  condition: Option[String] = None,      // extra `if` clause (e.g. main-only)
)
final case class StepContext(node: ModuleNode, target: Option[Target], matrixed: Boolean)
final case class Capability(             // gains (all defaulting to current behavior):
  /* …existing… */
  targets: ModuleNode => List[Target] = _ => Nil,    // Nil = single job, no fan-out
  needsCapabilities: List[String] = Nil,             // also `needs` same-module jobs of these capabilities
  extraSteps: StepContext => List[Step] = _ => Nil,  // steps before the command (e.g. configure-aws) — the extension seam
  permissions: Map[String, String] = Map.empty,      // job permissions, e.g. "id-token" -> "write" for OIDC
  runsOn: Option[List[String]] = None,               // per-capability runner override; >1 element ⇒ list form
)
// Phase gains `case Deploy` (after Publish); add Capability.deploy(...) and Capability.custom(...).
```

**Two capabilities added for full coverage (see mapping below):** `Capability.permissions` (OIDC/cloud deploys need `id-token: write`; `Job.permissions` already exists, the planner now sets it) and `Capability.runsOn` supporting **list-valued** runners (e.g. `[self-hosted, linux]`) — `Job.runsOn` renders a scalar for one label, a sequence for many; falls back to build-level `zipxRunnerOs`.

**Planner changes:** target fan-out (job id `<cap>-<mod>-<target>`, each carrying `environment`, merged `target.env`, ANDed `target.condition`); cross-capability `needs` (deploy needs the module's docker job) with a cycle guard; `extraSteps` inserted between setup/cache and the command.

**Plugin surface:** `zipxCapabilities: SettingKey[Seq[Capability]]` (default `[test, publish, docker?]`, users **append** custom capabilities — the extension entry point); re-export `Target`/`Capability`/`Phase`/`Gate`/`Ordering`/`StepContext`. No cloud/registry types in core — the example shows the typed target resolution (a user `case class` + a validating `=> List[Target]` function, the typed replacement for a YAML+script config join). A cloud-provider convenience module (e.g. `zipx-aws`) is deferred until a second consumer needs it.

**Sub-milestones (all ✅):**
- **M6a+M6b — Environment binding + target fan-out + env injection:** `Job.environment`, `Target`, `Capability.targets`; explicit per-target jobs (`<cap>-<mod>-<target>`), `target.env`→job `env:`, `target.condition` ANDed into `if`. ✅
- **M6c — Cross-capability needs + `Phase.Deploy` + `Capability.deploy` + `Capability.permissions`:** deploy `needs` the module's docker job; capability-graph cycle guard; `id-token: write`. ✅
- **M6d — Extension seam:** `extraSteps`/`StepContext` + `Capability.custom` + `Capability.runsOn` (list runners, scalar/sequence rendering); append-able `zipxCapabilities`; staging/prod deploy demo in `examples/monorepo` (targets defined in `project/Deploy.scala` — the typed config-join). ✅
- **M6e — End-to-end capability proof:** `PipelineSpec` plans all capabilities together on the sample graph + staging/prod targets, asserting the complete pipeline (test → ordered publish → docker → gated multi-target deploy), phase ordering, cross-capability needs, approval env, OIDC, and deterministic rendering. ✅

**Setting-scope fix (sbt 2.0 common settings).** During M6d we corrected how zipx reads build-level settings. sbt 2.0 makes a bare `foo := x` in `build.sbt` a *common setting* injected into every subproject's own scope (overridable per module) — NOT `ThisBuild`, and scope delegation only flows specific→general. So zipx's build-level tasks now read config from the **root project's scope** (`extracted.getOpt(rootRef / key)`, which delegates project→ThisBuild→Global), honoring bare/common, `ThisBuild /`, and the Global default alike. Result: **no `ThisBuild /` prefix is needed anywhere** in a consumer build; a bare `zipxTestTask := "testFull"` applies to every module and any module can override it (verified by a propagate-down/override scripted test).

**Testing plan — one test per capability.** Each capability gets a dedicated pure-planner assertion in `PlannerSpec`/`CapabilitySpec`, driven by the `sampleGraph` fixture plus a representative staging/production deploy target set:
- **Environments/approval:** a target with `environment = Some("prod")` → the job renders `environment: prod`; a staging target renders none. Assert the prod job carries the environment and staging doesn't (approval is GitHub-side; we test the binding).
- **Target fan-out:** N targets → exactly N jobs `deploy-<mod>-<target>`, sorted deterministically; each carries its own `env:` (account/region/role/tier) and `condition` in `if`.
- **Env injection:** assert `target.env` keys land in the job `env:` block verbatim (including a `${{ secrets.X }}` value) and that steps can reference `${{ env.DEPLOY_ROLE }}`.
- **Cross-capability needs:** `deploy-<svc>` `needs` `docker-<svc>`; cycle guard test (a capability set with a needs-cycle throws).
- **OIDC permissions:** deploy job renders `permissions: { id-token: write, contents: read }`.
- **List runners:** `runsOn = Some(List("self-hosted", "linux"))` renders a YAML sequence; a single label renders a scalar (golden).
- **Custom command:** a deploy capability whose `command` is a user sbt task renders that exact `run:`; a `tier` value from `target.env` is referenceable.
- **2nd publish kind:** a `Capability.custom` in the Publish/Deploy phase with `needsCapabilities=["docker"]` → a downstream job depending on the docker job.
- **Determinism:** the full sample-graph pipeline → generate twice → byte-identical; `zipxWorkflowCheck` clean.
- **Scripted (`generate-check`):** extend with a 2-target deploy capability; assert the prod job's `environment:` + `needs` + `permissions`, and the round-trip.
- **M6e:** in `examples/`, wire the sample graph + staging/prod targets and confirm `zipxWorkflowGenerate` produces the full job set with correct needs edges, environments, and gates.

**Resolved design choices:** (1) **`Phase.Deploy` is added** — Verify → Publish → Deploy; deploy is never affected-gated, sorts after publish, and uses `needsCapabilities` for its docker/publish dependency. (2) **Env injection uses the job `env:` block** — each explicit per-target job merges `target.env` into its `env:`, referenced in steps as `${{ env.KEY }}` (secret-valued entries like `${{ secrets.X }}` work as env values); no runtime matrix, so no GHA uniform-object constraint. (3) `zipx-aws` convenience module deferred until a second consumer needs it.

**Capability coverage — what a full CI pipeline needs, and how zipx provides it.** M6 is "done" when a `build.sbt` can generate a complete multi-environment pipeline with no external YAML config. Capability-by-capability:

| CI capability | zipx mechanism | milestone |
|---|---|---|
| test each module (custom task, e.g. `testFull`) | `zipxTestTask` (+ Aggregate Once / Graph/Layer); optional `zipxVerifyClean` | ✅ M1 / Verify knobs |
| ordered library publish | `Capability.publish`, dependency-ordered | ✅ M2 |
| publish gated on release | release-tag gate | ✅ M2 |
| docker image build | `Capability.docker` (native-packager) | ✅ M4 |
| one image → N tags / moving `latest` alias | native-packager `dockerAliases` | ✅ delegated |
| one image → **N registries/accounts**, each with own credentials | a `docker`-named `Capability.custom` with `targets` = registries + `extraSteps` login (same-name override of the built-in); demonstrated in `examples/` (`docker-service-us`/`-eu`) | ✅ M6+ |
| deploy to staging/production targets | `Capability.deploy` + `targets` | ✅ M6 |
| production human-in-the-loop approval | GitHub Environment name on the job | ✅ M6 |
| per-target account/region/tier/credential config | typed `List[Target]` in the build (a typed config join) | ✅ M6 |
| deploy-time retag/promote using tier | user sbt task as the deploy `command`, reading `TIER` from the target `env:` (proven: a fresh JVM reads process env; sbt's persistent *server* is a local-dev caveat) | ✅ M6+ |
| a second publish kind, downstream of the image push | `Capability.custom` + `needsCapabilities` | ✅ M6d |
| cloud credential setup step (e.g. OIDC role assumption) | `extraSteps` seam, values from `target.env` | ✅ M6d |
| `permissions: id-token: write` (OIDC) | `Capability.permissions` → `Job.permissions` | ✅ M6c |
| custom / list-valued runner (`[self-hosted, linux]`) | `Capability.runsOn: Option[List[String]]` | ✅ M6d |
| run-once build-wide gate (e.g. `scalafmtCheckAll`) | `Capability.once` (`CapabilityScope.Once`) — single job; others `needsCapabilities` it | ✅ M6+ |
| independent targets (one holds for approval, others proceed) | explicit per-target jobs are already independent | ✅ inherent |

Deliberately **not** modeled (equivalent-or-better by design): a container-based sbt runner — zipx uses `actions/setup-java` + `sbt/setup-sbt` for the same toolchain pinning without a container; `Job.container` remains available if a user wants it. Ad-hoc cache-warmup hacks and time-bucketed cache keys are obviated by M5's content-addressed caching + commit-stable epoch. `examples/monorepo` demonstrates the full pipeline end-to-end (fmt gate → test → ordered publish → multi-registry docker → gated multi-target deploy) generated entirely from `build.sbt` + typed lists in `project/`, no external YAML.

**Every acceptance-mapping capability is now implemented and proven** (unit + scripted + running example). A monorepo on the external-YAML-config pattern can migrate its whole pipeline to zipx.

### M7 — Typed secrets & capability env ✅

**Why.** Secrets were stringly typed: consumers hand-wrote `"${{ secrets.X }}"` into `Target.env`. That worked for demos but was error-prone, un-completable, and insufficient for early-effect Central publishing (publish jobs ran bare `publish` with **no** PGP/Sonatype env injection).

**Goal.** Make secret *references* first-class Scala while keeping zipx semantics-agnostic (names and values stay out of the planner; only rendering is owned).

**Shipped types:**
```scala
enum EnvValue:
  case Plain(value: String)
  case FromSecret(name: String)   // → ${{ secrets.NAME }}
  case FromEnv(name: String)      // → ${{ env.NAME }}
  case Expr(expr: String)         // escape hatch

// Target.env and Capability.env are Map[String, EnvValue]
// autoImport: secret"PGP_PASSPHRASE", Secret.ref("…"), EnvValue.plain / .env / .expr
```

**Planner / plugin:**
- Capability gains `env: Map[String, EnvValue]` so publish/signing secrets attach once to all jobs of that capability.
- Merge precedence (later wins): cache contribution → `Capability.env` → `Target.env`.
- `ManagedRemote.headerSecret` validated via `EnvValue.secret` at plan time.
- Re-exported via `autoImport`; `examples/monorepo` uses `secret"…"` / `EnvValue.plain`.

**Acceptance (met):**
- Unit: `EnvValueSpec` (render + adversarial name validation); planner injects capability/target/cache layers; golden expressions.
- Scripted: publish jobs carry typed secret env; `zipxWorkflowCheck` clean.
- Example: no raw `"$${{ secrets.… }}"` strings in `examples/monorepo/build.sbt`.
- Gap coverage added for publish-edge contraction, cross-capability target fan-out needs, `ciRelevant=false`, unknown `needsCapabilities`, trailing-slash base dirs, ModuleGraph edge cases.

**Design guardrails:** generate-time resolution only (no runtime secret matrix); zipx never stores secret values; org vs repo secret *scope* is a GitHub concern, not a zipx type. Empty / spaced / expression-like secret names are rejected at construction.

### M8 — `zipx-central` + dogfood Central publish ✅

**Why.** early-effect libraries publish CI-only to the Sonatype Central Portal, signing with the shared org secrets (`PGP_KEY_HEX`, `PGP_SECRET`, `PGP_PASSPHRASE`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`). Before M8, zipx's own `publish-*` jobs could not release; consumers had to invent GPG-import steps and env wiring by hand.

**Shipped.** `zipx-central` composes M7 primitives into the paved Central path. Dogfood:

```scala
zipxCapabilities += ZipxCentral.release   // Aggregate: one job
// or Graph: ZipxCentral.publishSigned + ZipxCentral.releaseOnce
```

Generated CI owns GPG import + `publishSigned; sonaRelease` (Aggregate) or Graph staging artifacts + Specular Pages (`ZipxDocs.pages`); hand-rolled `release.yml` / `docs.yml` deleted. `ZipxCentral` / `ZipxDocs` are re-exported from the plugin's `autoImport` (nested objects so meta-build only needs the plugin jar).

**Also:** hash-pinned GitHub Actions via configurable `zipxActions` / `ActionPins` (checkout v7, setup-java v5, setup-sbt v1.5, cache v6). Reusable-workflow once-jobs via `Capability.workflowCall` / `Job.uses`.

**Acceptance:** unit coverage for `publishSigned` / `releaseOnce` / `needsCapabilities` fan-out; dogfood `ci.yml` regenerated with SHA pins + Central jobs. First Central tag publish is the live proof (same org secrets as peers).

**Out of scope for M8:** replacing every hand-written `release.yml` across the org (that's M11 rollout); local manual publishing.

### M9a — Aggregate-first + Layer + deploy-by-target ✅

**Why.** Graph (one job per module) proves topology but burns GHA minutes (~11 sbt starts on dogfood). sbt's root `.aggregate` already batches work in one JVM; zipx defaults should match that cost profile while keeping Graph as an escape hatch.

**Shipped:**
- `CapabilityScope`: `Aggregate` | `Layer` | `Graph` | `Once`
- Defaults: `Capability.test` / `.publish` / `.docker` / `.deploy` are Aggregate (deploy = one job per Target, modules joined)
- Escape hatches: `testGraph` / `publishGraph` / `dockerGraph` / `deployGraph`; Layer: `testLayers` / `publishLayers` / `dockerLayers`
- Planner emits joined `;` commands for Aggregate/Layer; Layer uses `subsetLayers` with previous-wave `needs`
- Affected setup only when a Graph Verify capability is present
- `ZipxCentral.release` (Aggregate single-job) preferred; Graph staging path retained
- Dogfood on Aggregate; `examples/monorepo` on Layer test/publish + Aggregate deploy; README execution-modes guide

**Acceptance:** unit coverage for Aggregate/Layer/Graph across test, publish, docker, deploy; dogfood workflow regenerates to Aggregate shape.

### M9 — Dynver-ci + publishSigned auto-detect ⬜

**Why.** Cache epoch already defaults to `version`; [`sbt-dynver-ci`](https://github.com/early-effect/sbt-dynver-ci) makes that PR-stable (`<last-tag>-ci`). Docker auto-detect from `DockerPlugin` is the right pattern for "the build describes itself"; pgp presence should similarly nudge the default publish command.

**Goal:**
- Document / recommend `sbt-dynver-ci` alongside zipx (epoch = dynver-ci version across a PR).
- When `sbt-pgp` is on the classpath, default `zipxPublishTask` (or the built-in publish capability command) toward `publishSigned`, with an explicit override. Consumers using `zipx-central` already get this via capability replace; auto-detect helps bare setups.
- Hygiene: refresh stale milestone comments in `Planner` / `CacheBackend`; keep ROADMAP status table in sync.

**Acceptance:** docs + scripted/unit proving pgp auto-detect and override; dynver-ci called out in README cache-epoch section.

### M10 — `zipx-aws` (on second consumer) ⬜

**Deferred by design** until a second real consumer would otherwise copy the OIDC + ECR block from `examples/monorepo`.

**When started:** extract `project/Deploy.scala`-style helpers into `zipx-aws` (role assumption step factory, registry `Target` builders) using M7 `EnvValue` / `secret"…"`. Same meta-build library pattern as `zipx-central`.

### M11 — "Extend with Scala" docs & org rollout ⬜

**Docs:** a first-class guide that makes Scala the default extension story:
- `project/*.scala` typed config (the join that replaced YAML + resolver scripts)
- `zipxTasks` / `cmd"…"` over string commands
- `EnvValue` / `secret"…"` over raw `${{ }}` strings
- composing `Capability.custom` / `.deploy` / `.once` and same-name replace
- published packs (`zipx-central`, later `zipx-aws`)

**Org rollout:**
1. Publish zipx `0.1.0` to Central (via M8 dogfood).
2. Adopt zipx in 1–2 early-effect libraries (alongside `sbt-dynver-ci`).
3. Prefer generated publish/release topology over hand-maintained `release.yml` where the build graph already knows the modules.
4. Extract `zipx-aws` only when step 2 produces a second copy of the AWS block (triggers M10).

**Design guardrails (carry forward):**
1. Topology in zipx; semantics in Scala packs.
2. Generate-time resolution; deterministic YAML for `zipxWorkflowCheck`.
3. Org secrets by **name**, never value, in the plugin or packs.
4. sbt 2.0 remains the unlock (action cache, remote cache, Scala 3 plugins, common settings); do not regress to sbt 1.x shapes.

## Verification

- **Pure units (fast, no sbt):** `zipx-core` planner + `zipx-workflow` printer tested with golden output against a fixture graph. `sbt "workflow/testFull; core/testFull"`.
- **Plugin integration:** sbt `scripted` test (`modules/sbt-plugin/src/sbt-test/zipx/generate-check`) where `zipxWorkflowGenerate` then `zipxWorkflowCheck` is a clean no-op round-trip (idempotence = determinism proof).
- **Dogfood:** zipx generates its own `.github/workflows/ci.yml` (`workflow → core → plugin` test + publish chains).

## Post-milestone follow-ups (all done)

- **Remote-cache correctness (`cacheVersion`).** For remote backends, `Global / cacheVersion` = a stable FNV-1a hash of `(jdkMajor, os)` — the axes sbt does NOT auto-hash — so a heterogeneous runner pool can't poison the shared cache. The commit epoch is excluded (cross-epoch reuse is the point of a persistent remote cache); the epoch still keys the local `actions/cache`.
- **Remote-cache transport is bundled.** `zipx-sbt` depends on `org.scala-sbt:sbt-remote-cache`, whose `RemoteCachePlugin` triggers on AllRequirements — so consumers need no extra `addSbtPlugin` line. It's a no-op until `Global / remoteCache` is set (only when the CI job exports `ZIPX_REMOTE_CACHE`), so local builds are unaffected. (Required a `libraryDependencySchemes += "org.scala-sbt" % "compiler-interface" % "always"` to silence a false eviction between the sbt-2.x and zinc-1.x versioning of `compiler-interface`.)
- **`zipxPublishOrder` task** prints the contracted publish layers (`ModuleGraph.subsetLayers(_.publishes)`), e.g. `L0: models / L1: coreLib / L2: client`.
- **Opt-in push-time affected (`zipxAffectedOnPush`, default false).** When on, pushes also restrict to affected modules by diffing the push `before` sha, guarded against force-push / branch-create (all-zero sha → build everything). Default remains: PRs are affected-scoped, pushes/tags build all.

## Deviations from the original plan

- **Own `YamlPrinter` instead of zio-blocks' `YamlWriter`.** zio-blocks' writer escapes newlines to `\n` and can't emit block scalars, which breaks multi-line values like `actions/cache` `path:`. `YamlPrinter` replicates its quoting exactly (single-line output byte-identical) and adds literal block scalars.
- **Docker opt-in is auto-detected**, not a `zipxDocker := true` flag: enabling sbt-native-packager's `DockerPlugin` on a module is the signal (`zipxDocker` defaults from `thisProject.autoPlugins`). Users can still override the setting.
