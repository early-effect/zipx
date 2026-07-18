# zipx

[![CI](https://github.com/early-effect/zipx/actions/workflows/ci.yml/badge.svg)](https://github.com/early-effect/zipx/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-earlyeffect.rocks-blue)](https://www.earlyeffect.rocks/zipx/)
[![Maven Central](https://img.shields.io/maven-central/v/rocks.earlyeffect/zipx-sbt_sbt2_3?logo=apachemaven)](https://central.sonatype.com/artifact/rocks.earlyeffect/zipx-sbt_sbt2_3)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**The build describes its own CI.** zipx is an sbt 2.x plugin (Scala 3) that generates a GitHub Actions workflow directly from your sbt build graph — no hand-maintained YAML, no module list to keep in sync, no per-module command strings to copy-paste.

Docs: [early-effect.github.io/zipx](https://early-effect.github.io/zipx/) (after the first `v*` Docs deploy).

You declare your modules and their `dependsOn` edges once, in `build.sbt`, as you already do. zipx introspects that graph and emits a workflow that:

- **defaults to Aggregate mode** (few sbt sessions: `sbt test`, one publish/release job) while remaining driven by the real module graph;
- **offers Layer and Graph modes** when you need dependency-ordered waves or full per-module fan-out (affected-only PRs, matrix isolation);
- **publishes in true dependency order** when you use Layer or Graph (upstream before downstream), gated on release tags;
- **runs only affected modules on pull requests** in Graph mode (changed modules + their transitive dependents);
- **caches sbt's build state** with a commit-stable key so mid-PR pushes stay warm (local or remote);
- **builds & publishes docker images** for services via sbt-native-packager — so that the image can be described by the build;
- **deploys to multiple environments** (staging/production etc) with per-environment config allowing **human-in-the-loop approval** for production via GitHub Environments (targets always fan out; modules can batch);
- **extends to anything** — deploys, multi-registry pushes, lint gates, and stages you invent are all pluggable capabilities;
- **checks itself in CI**: a committed workflow that drifts from the build fails the build.

## Why

The common approach to CI for a Scala monorepo is a hand-written `.github/workflows/*.yml` (often plus an external config file and a resolver script) that re-declares the module set, their dependencies, and per-module test/publish/deploy recipes. That duplicates what `build.sbt` already knows, and the two drift: a new module, a renamed project, or a changed dependency edge silently desyncs CI from the build. Publish ordering is usually not modeled at all — modules publish in parallel and rely on artifacts already existing in the registry.

zipx makes the sbt build the single source of truth. The build graph *is* the CI topology. How aggressively that topology fans out into GitHub jobs is an explicit **execution mode** (see below): Aggregate by default for cost, Graph when you need isolation.

## Why sbt 2.0 (the unlock)

zipx isn't just a nicer way to write CI — it's something **sbt 2.0 makes newly possible**. Four sbt 2.0 changes are load-bearing:

- **A machine-wide, content-addressed build cache (Bazel-style "action cache").** This is what keeps **Graph** mode viable: fanning a monorepo out into one job per module is only *fast* if those jobs don't each recompile the world. sbt 2.0's action cache is content-addressed and shared across the machine, so a per-module job restores upstream compilation instead of redoing it. zipx's Graph fan-out and its **commit-stable cache key** (reuse across a whole PR, roll on release) are built directly on this — in sbt 1.x there was nothing to key a cross-job cache on. Most builds should still start on **Aggregate** and only move to Graph when they need affected-only or per-module matrix isolation.
- **A real remote cache over the Bazel gRPC protocol** (`sbt-remote-cache`, which zipx bundles). This is what lets caching span *runners*, not just jobs on one machine — `BazelRemoteSidecar` and `ManagedRemote` exist because sbt 2.0 shipped the transport.
- **Scala 3 plugin authoring.** sbt 2.0 plugins are Scala 3, so zipx's heart — `zipx-core` and `zipx-workflow` — are ordinary, exhaustively-testable Scala 3 libraries with a clean typed model (`Capability`, `Target`, the module graph, `ModuleGraph => Workflow`). The whole planner is verified with zero sbt on the classpath. Under sbt 1.x that logic would be Scala 2.12 tangled into the plugin.
- **Common settings.** sbt 2.0's model — a bare `foo := x` in `build.sbt` applies to every module and each can override — is exactly the ergonomics zipx wants: derive everything, let a bare `zipxTestTask := "testFull"` set a build-wide default, and let any module override it. No `ThisBuild /` ceremony.

In short: sbt 2.0 turned "fast, cached, parallel monorepo CI" from an external-tooling problem into a *build* problem — and zipx is the build describing the solution.

## Quick start

Add the plugin (it bundles everything it needs, including the remote-cache transport):

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "<version>")
```

Generate and commit the workflow:

```
sbt zipxWorkflowGenerate
git add .github/workflows/ci.yml && git commit -m "ci: generate with zipx"
```

That's it. Defaults are **Aggregate**: one test job and one publish job (plus docker when any module enables `DockerPlugin`). For a typical library you write **zero** module lists, `needs` edges, or project-id strings. The rest of this README covers execution modes and knobs for when you need them.

> **A note on setting scope.** zipx reads its settings from the root project's scope, so you write plain **bare settings** in `build.sbt` — no `ThisBuild /` prefix needed. Following sbt 2.0's *common settings* model, a bare `zipxTestTask := "testFull"` applies to every module, and any module can override it in its own `.settings(...)`.

## Execution modes: Aggregate, Layer, Graph

**This is the main lever for CI cost.** sbt already batches work through root `.aggregate` in one JVM. Graph mode pays for many JVMs to get per-module `needs`, affected gating, and Scala matrix isolation. Pick the mode that matches the cost/isolation tradeoff — not "always fan out."

| Mode | Test / publish / docker jobs | Deploy jobs | Best for |
|---|---|---|---|
| **Aggregate** (default) | 1 per stage; module commands joined with `;` | **1 per Target** (modules batched) | Cost; libraries; dogfood; "just run the build" |
| **Layer** | 1 per toposort wave; waves chained by `needs` | Same as Aggregate-by-target (or use Graph deploy) | Ordered waves without N JVMs |
| **Graph** | 1 per module (± Scala matrix / targets) | 1 per **module × Target** | Affected-only PRs; matrix isolation; per-module deploy hooks |

### When to use which

- **Aggregate** — default plugin behavior. Libraries, small graphs, this repo's dogfood. One `test` job, one `publish` (or `ZipxCentral.release`) job. No per-module affected gating: the stage always runs the full joined command.
- **Layer** — you want dependency-ordered waves (L0 → L1 → L2) and fewer sbt starts than Graph. Inspect waves with `sbt zipxGraph` / `zipxPublishOrder`. No per-module affected.
- **Graph** — you need affected-only PRs, per-module Scala matrix isolation, per-module deploy hooks, or max concurrency with a warm action cache. This is what [`examples/monorepo`](examples/monorepo) demonstrates for multi-registry docker; Layer is used there for test/publish.

### Docker and deploy: modules batch, targets do not

Participants still come from the sbt graph (`_.docker`, deploy `participates`). Commands stay sbt tasks. Targets stay typed `List[Target]` from `project/*.scala`.

**Hard constraint:** GitHub `environment:` (approval) and per-destination `env:` are **per Target**. You cannot merge `staging` and `prod` into one job without losing independent approval.

| Dimension | Aggregate | Graph |
|---|---|---|
| **Modules** | Batch: `svcA/promote; svcB/promote` in one job | One job per module |
| **Targets** | Still one job **per Target** | One job per module × Target |

That cuts jobs from `modules × targets` to `targets` when many services share the same staging/prod destinations.

**Examples:**

- Aggregate deploy → `deploy-staging`, `deploy-prod` (modules joined inside each).
- Graph deploy → `deploy-service-staging`, `deploy-service-prod`.

### API cheat sheet

```scala
// Defaults (Aggregate) — what builtins emit
Capability.test
Capability.publish
Capability.docker
Capability.deploy(participates, command, targets)

// Layer — one job per toposort wave
Capability.testLayers
Capability.publishLayers
Capability.dockerLayers

// Graph — full fan-out (escape hatch)
Capability.testGraph
Capability.publishGraph
Capability.dockerGraph
Capability.deployGraph(participates, command, targets)

// Central publish
ZipxCentral.release                              // Aggregate: GPG + publishSigned; sonaRelease
ZipxCentral.publishSigned + ZipxCentral.releaseOnce  // Graph + staging artifacts across jobs
```

Same-name override still applies: a user capability whose `name` matches a built-in replaces it.

```scala
// Example: Layer test/publish, Aggregate deploy, Graph multi-registry docker
zipxCapabilities ++= Seq(
  Capability.testLayers.copy(needsCapabilities = List("fmt")),
  Capability.publishLayers,
)
zipxCapabilities += Capability.custom(name = "docker", /* Graph targets… */)
zipxCapabilities += zipxTasks.deploy(_.id == "service", promote, targets) // Aggregate-by-target
```

### Cost intuition

For a 4-module library with cross-Scala and release publish:

- **Aggregate:** roughly 2 sbt starts on PR (`test` + optional gates), plus one `publish`/`release` job on tags.
- **Graph:** one test job per module × Scala matrix legs, plus affected setup, plus one publish job per module on tags — often ~10+ sbt starts even when little changed.

This repo dogfoods on Aggregate (`ZipxCentral.release`). [`examples/monorepo`](examples/monorepo) showcases Layer test/publish, Graph multi-registry docker, and Aggregate-by-target deploy.

### Affected-only PRs

`zipxAffectedOnPR` (default `true`) only emits the `affected` setup job when a **Graph** Verify capability is present. Aggregate and Layer always run their full stage command. Use `Capability.testGraph` (or replace the builtin) when you need affected-only.

### Skip Verify after PR merge

By default (`zipxSkipMergedPrPush := true`), a push to `main` that lands a merged PR does **not** re-run Verify: those tests already ran on the PR. Direct pushes to `main` still run Verify. Detection uses the GitHub API (`commits/{sha}/pulls`), so both merge commits and squash merges are covered. Publish / Deploy / tags / `workflow_dispatch` are unchanged. Set `zipxSkipMergedPrPush := false` to always Verify on every branch push.

## What gets generated

Given a build like:

```scala
lazy val models  = project.settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))
lazy val coreLib = project.dependsOn(models).settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))
lazy val client  = project.dependsOn(coreLib).settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))
lazy val service = project.dependsOn(coreLib).enablePlugins(JavaAppPackaging, DockerPlugin)
lazy val root    = project.in(file(".")).aggregate(models, coreLib, client, service)
```

**With defaults (Aggregate)** zipx generates:

- one **`test`** job running joined `models/test; coreLib/test; …`;
- one **`publish`** job (release-tag-gated) joining publishing modules' `+…/publish` commands;
- one **`docker`** job joining `service/Docker/publish`;
- **caching** on every job with a commit-stable key.

**With Graph** (`Capability.testGraph` / `publishGraph` / `dockerGraph`):

- an **`affected`** setup job (on PRs);
- **test jobs** `test-models → test-coreLib → {test-client, test-service}` wired by `needs`, each matrixed over its own `crossScalaVersions`, each gated on affected membership;
- **publish jobs** `publish-models → publish-coreLib → publish-client` (dependency-ordered, release-tag-gated);
- a **`docker-service`** job running `service/Docker/publish`.

The aggregating `root` project gets no commands (it's a container). `service` is excluded from library publish (`publishArtifact := false`) but included in docker when it enables `DockerPlugin`.

Inspect what zipx sees before generating:

```
sbt zipxGraph          # modules, dependsOn edges, flags, topological layers
sbt zipxPublishOrder   # the contracted publish waves (L0 → L1 → L2)
```

## How it works

zipx is three layers:

- **`zipx-workflow`** — a pure GitHub Actions AST + a deterministic YAML printer. Zero sbt dependency.
- **`zipx-core`** — the pure planner: the module-graph model, topological sort, publish-edge contraction, affected-set computation, the `Capability` / `Target` / `CacheBackend` models, and `ModuleGraph => Workflow`. No sbt dependency, exhaustively unit-tested.
- **`zipx-sbt`** — the `AutoPlugin`: adapts sbt's build state into the core model and wires the tasks. The only layer that touches `sbt.*`.

The plugin owns **topology** (the graph, ordering, `needs`, matrix, gating, environment binding, caching — all derived). The build owns **what to run** (the sbt tasks), modeled as pluggable **capabilities**. zipx stays semantics-agnostic: it knows nothing about specific registries, what a "tier" means, or your tag scheme.

### Capabilities

A **capability** is a pipeline stage. Built-ins default to **Aggregate**; Graph/Layer variants are explicit constructors. Test, publish, and docker are built-in; you can add your own.

| Capability | Default mode | Runs (per participant) | Participates | Phase | Gate |
|---|---|---|---|---|---|
| **test** | Aggregate | `<module>/<testTask>` (joined) | every CI-relevant module | Verify | always |
| **publish** | Aggregate | `+?<module>/<publishTask>` (joined) | modules that publish an artifact | Publish | release tag |
| **docker** | Aggregate | `<module>/Docker/publish` (joined) | modules with `DockerPlugin` | Publish | release tag |

Use `testGraph` / `publishGraph` / `dockerGraph` for today's one-job-per-module shape (Graph test is matrixed over Scala versions). Use `*Layers` for wave scheduling. See [Execution modes](#execution-modes-aggregate-layer-graph).

Capabilities run in phase order — **Verify → Publish → Deploy** — and a capability can depend on another (`needsCapabilities`), e.g. deploy waits on docker. A `zipxCapabilities += ...` you add is merged with the built-ins; a capability with the same `name` as a built-in **replaces** it (useful for turning Aggregate `docker` into a multi-registry Graph one — see below).

### Affected-only execution (PRs)

On a pull request with a **Graph** Verify capability, a leading `affected` job computes which modules changed and outputs them as a JSON array. Each Graph test job runs only if its module is in that set (or its transitive-dependent closure). The mapping is: changed files → owning module (longest base-dir prefix) → reverse-dependency closure. A change to a `.sbt` file or anything under a `project/` directory forces a full build. On push/tag, everything builds (see `zipxAffectedOnPush` to opt into push-time scoping).

The generated `if` conditions handle GitHub's skipped-`needs` hazard: an affected module still runs even when an unaffected upstream was skipped (`!cancelled()` + `needs.<upstream>.result != 'failure'`).

Aggregate/Layer Verify capabilities do not emit or consume the affected job.

### Caching

sbt 2.x has a machine-wide, content-addressed build cache. zipx persists it in CI. The cache key uses a **commit-stable epoch** (`zipxCacheEpoch`, defaulting to `version`) rather than a per-commit hash — so every push within a PR reuses the cache instead of starting cold. Cutting a release tag rolls the epoch. This pairs naturally with [`sbt-dynver-ci`](https://github.com/early-effect/sbt-dynver-ci), whose version is `<last-tag>-ci` across an entire PR.

Three backends, selected via `zipxCache`:

```scala
zipxCache := CacheBackend.LocalDir                                  // default: epoch-keyed actions/cache
zipxCache := CacheBackend.BazelRemoteSidecar("buchgr/bazel-remote:latest", 9092)
zipxCache := CacheBackend.ManagedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")
```

### Action pins

Generated workflows use **commit-SHA pins** (not floating `@v4` tags). Defaults track current upstream releases; override when you want to bump without waiting on a zipx release:

```scala
zipxActions := ActionPins.Defaults.copy(
  checkout = "actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0", // v7.0.0
)
```

- **`LocalDir`** — persist sbt's local cache dirs and build `target/` with `actions/cache` (pin via `zipxActions`). Primary key is OS + JDK + epoch + run id + job id so each job in a run can save; restore-keys prefer the same run (accumulated upstream), then the epoch. Disables setup-sbt's hashFiles disk-cache. No infrastructure.
- **`BazelRemoteSidecar(image, port)`** — run a `buchgr/bazel-remote` gRPC server as a job service; sbt uses it as a Bazel-protocol remote cache shared across the run.
- **`ManagedRemote(uri, headerSecret)`** — point sbt at a managed gRPC cache (BuildBuddy/EngFlow/NativeLink); the auth header comes from the named repository secret.

The remote-cache transport (`sbt-remote-cache`) is bundled with zipx, so remote backends need no extra plugin. For remote backends zipx also sets `Global / cacheVersion` from a hash of `(JDK, OS)` — the axes sbt itself doesn't hash — so a heterogeneous runner pool can't poison the shared cache.

### Early-effect packs (`ZipxCentral`, `ZipxDocs`)

Org paved paths as capabilities (secret *names* only; values stay in GitHub):

```scala
// Aggregate (preferred for libraries / dogfood)
zipxCapabilities += ZipxCentral.release   // GPG import + publishSigned; sonaRelease in one job

// Graph escape hatch (per-module publishSigned + staging artifacts + sonaRelease once-job)
zipxCapabilities ++= Seq(ZipxCentral.publishSigned, ZipxCentral.releaseOnce)

zipxCapabilities += ZipxDocs.pages()      // Specular site → GitHub Pages via org reusable workflow
zipxWorkflowDispatch := true              // optional: manual "Run workflow" for docs-only deploys
```

`ZipxDocs.pages` emits a reusable-workflow job (`jobs.docs.uses: early-effect/.github/.../specular-docs.yml@main`) on `v*` tags. No hand-rolled `docs.yml`.

### Docker: the paved path

A service opts into docker images simply by enabling [sbt-native-packager](https://github.com/sbt/sbt-native-packager)'s `DockerPlugin`. The image is described in the build:

```scala
lazy val service = project
  .dependsOn(coreLib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    publishArtifact      := false,           // an app, not a library
    Compile / mainClass  := Some("example.Main"),
    dockerBaseImage      := "eclipse-temurin:21-jre",
    Docker / packageName := "example-service",
    dockerExposedPorts   := Seq(8080),
  )
```

No Dockerfile, no external `docker build` string. zipx auto-detects the enabled plugin and generates a release-gated Aggregate `docker` job joining `…/Docker/publish` (or use `dockerGraph` for one job per module). Multiple tags / `latest` aliases are handled by sbt-native-packager's `dockerAliases`; pushing one image to **multiple registries/accounts** is a capability fan-out (below).

## Environments, approval & multi-target deploys

Deploying to multiple environments — with production behind a human approval gate — is a **deploy capability** that fans out over **targets**. Each target is a fully-resolved destination (name, optional GitHub Environment, injected env vars, extra `if` condition).

**Default (`Capability.deploy` / `zipxTasks.deploy`): Aggregate-by-target.** One job per Target (`deploy-staging`, `deploy-prod`); all participating modules' commands are joined. GitHub Environments stay independent.

**Escape hatch (`Capability.deployGraph`): Graph.** One job per (module × target), e.g. `deploy-service-staging`.

A `Target` carries per-environment config as plain Scala — the typed replacement for an external config file + resolver script:

```scala
// project/Deploy.scala — a typed list, validated by the compiler. zipx knows nothing about clouds/tiers.
final case class DeployEnv(name: String, ghEnvironment: Option[String], region: String, roleSecret: String, tier: String)
object DeployEnv:
  val all = List(
    DeployEnv("staging", None,               "us-west-2", "STAGING_DEPLOY_ROLE", "staging"),
    DeployEnv("prod",    Some("production"), "us-east-1", "PROD_DEPLOY_ROLE",    "prod"),  // GH Environment ⇒ approval
  )
```

```scala
// build.sbt — Aggregate-by-target (default)
zipxCapabilities += Capability.deploy(
  participates = _.id == "service",
  command      = n => s"${n.id}/promote",     // a real sbt task; reads TIER from the target env
  targets      = _ => DeployEnv.all.map(e =>
    Target(
      name        = e.name,
      environment = e.ghEnvironment,           // binds `environment:` → GitHub enforces required reviewers
      env         = Map(                        // injected into the job's `env:`, referenced as ${{ env.KEY }}
        "AWS_REGION"  -> EnvValue.plain(e.region),
        "DEPLOY_ROLE" -> secret"${e.roleSecret}",
        "TIER"        -> EnvValue.plain(e.tier),
      ),
      condition   = Some("github.ref == 'refs/heads/main'"),
    )),
  needsCapabilities = List("docker"),           // deploy waits on the image publish
  permissions       = Map("id-token" -> "write", "contents" -> "read"), // OIDC
).copy(
  // The extension seam: inject setup steps (e.g. cloud auth) before the command, using the target's env.
  extraSteps = _ => List(Step(
    name = Some("Configure AWS credentials"),
    uses = Some("aws-actions/configure-aws-credentials@v6"),
    `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}", "aws-region" -> "${{ env.AWS_REGION }}"),
  )),
)
```

This generates `deploy-staging` (runs immediately) and `deploy-prod` (holds for approval because it binds `environment: production` — configure required reviewers on that environment in your repo settings). Both `needs` the docker job(s), carry OIDC `permissions`, inject their per-target `env:`, and run the AWS-credentials step from `extraSteps`.

**Approval is enforced by GitHub, not zipx.** zipx emits the `environment:` binding; GitHub pauses the job for the environment's protection rules. zipx generates no manual-approval steps.

## Custom capabilities & run-once gates

`zipxCapabilities` is append-able — any sbt task becomes a CI stage. Two constructors beyond the built-ins:

**`Capability.custom`** — a stage zipx doesn't model directly (all topology knobs exposed; defaults to **Graph** so target fan-out matches multi-registry examples). For example, pushing one image to **multiple registries** is a `docker`-named custom capability (same name ⇒ replaces the built-in) that fans out over registry targets, each with its own credentials:

```scala
zipxCapabilities += Capability.custom(
  name         = "docker",                       // replaces the built-in Aggregate docker
  command      = n => s"${n.id}/Docker/publish",
  participates = _.docker,
  phase        = Phase.Publish,
  targets      = _ => Registry.all.map(r =>
    Target(r.name, env = Map("REGISTRY" -> EnvValue.plain(r.host), "DEPLOY_ROLE" -> secret"${r.roleSecret}"))),
  permissions  = Map("id-token" -> "write", "contents" -> "read"),
).copy(extraSteps = _ => List(Step(name = Some("Login"), uses = Some("aws-actions/configure-aws-credentials@v6"),
  `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}"))))
// ⇒ docker-service-us, docker-service-eu  (Graph × targets)
```

**`Capability.once`** — a single build-wide job (not per module), e.g. a format/lint gate that every test job waits on:

```scala
zipxCapabilities += Capability.once("fmt", "scalafmtCheckAll")
zipxCapabilities += Capability.test.copy(needsCapabilities = List("fmt"))  // Aggregate test needs the fmt gate
// or: Capability.testGraph.copy(needsCapabilities = List("fmt")) for Graph
```

Custom capabilities can also override runners (`runsOn = Some(List("self-hosted", "linux"))` renders a list runner) and set `permissions` — the same knobs the built-ins use.

### Typed task keys (`zipxTasks`)

The `command` above is a string because it's ultimately what runs at the sbt shell in CI — and it must express things a single key can't (the cross `+`, command aliases, compound `a; b`). For the common "one task" case, `zipxTasks` offers the same constructors taking a real `TaskKey`/`InputKey` instead, so you get **code completion and compile-time checking** (a renamed or removed task fails at build load, not in CI):

```scala
val promote = taskKey[Unit]("promote the image")
// …
zipxCapabilities += zipxTasks.once("fmt", scalafmtCheckAll)                 // renders `scalafmtCheckAll`
zipxCapabilities += zipxTasks.deploy(_.id == "service", promote, targets)   // Aggregate-by-target
zipxCapabilities += zipxTasks.deployGraph(_.id == "service", promote, targets) // Graph module × target
```

A key renders to `<module>/<label>`, config-scoped keys keep their config axis (`Docker / publish` → `<module>/Docker/publish`), and a `Once` gate renders the bare `<label>` — all identical to the string form. `zipxTasks` mirrors `once` / `custom` / `deploy` / `deployGraph`.

For commands that need shell *syntax* around a key — the cross `+`, `++<version>`, compound `;` — use the **`cmd"…"` interpolator**: literal text is verbatim, and each `${…}` splice is dispatched by its static type — a typed, config-aware, module-scoped key, **or** a plain `String` (a computed version, path, secret ref, …). A macro checks every splice is one of those two (anything else is a compile error):

```scala
command = cmd"+ ${testFull}"                     // → +<module>/testFull   (key splice)
command = cmd"${Docker / publish}"               // → <module>/Docker/publish   (config axis preserved)
command = cmd"++${scalaVersion.value}; ${publish}" // String splice + a module-scoped key (mixed)
```

`cmd"…"` produces the `command` function directly (pass it to `Capability.custom`/`.deploy`/`.once`). Key splices are always module-scoped; drop to a plain string only for an explicitly cross-*project* command or command aliases.

## Self-checking

`zipxWorkflowGenerate` writes `.github/workflows/ci.yml` with a "generated by zipx" header. `zipxWorkflowCheck` regenerates and diffs against the committed file, failing if they differ. Run the check in the generated workflow itself, so a build change that isn't reflected in the committed YAML fails the PR. Generation is deterministic (stable ordering, no timestamps), so the round-trip is a reliable no-op when in sync.

## Configuration reference

All settings have sensible derived defaults; override only what your build genuinely can't express. Write them as **bare settings** (no `ThisBuild /`) — see the scope note above.

### Build-level

| Setting | Type | Default | Purpose |
|---|---|---|---|
| `zipxCapabilities` | `Seq[Capability]` | `Seq.empty` | custom capabilities to add (merged with built-ins; same name replaces) |
| `zipxWorkflowName` | `String` | `"CI"` | the workflow `name:` |
| `zipxWorkflowPath` | `String` | `.github/workflows/ci.yml` | output path (relative to build root) |
| `zipxJavaVersion` | `String` | `"21"` | JDK for `setup-java` and the cache key |
| `zipxRunnerOs` | `String` | `"ubuntu-latest"` | default runner label |
| `zipxScalaMatrix` | `Boolean` | `true` | expand a per-module Scala matrix (**Graph** test only) |
| `zipxActions` | `ActionPins` | hash-pinned defaults | `uses:` pins for checkout / setup-java / setup-sbt / cache |
| `zipxWorkflowDispatch` | `Boolean` | `false` | emit `on.workflow_dispatch` (manual runs; useful with `ZipxDocs.pages`) |
| `zipxCacheEpoch` | `String` | `version` | commit-stable cache-key epoch |
| `zipxPushBranches` | `Seq[String]` | `Seq("main")` | branches whose pushes trigger CI |
| `zipxReleaseTagPattern` | `String` | `v[0-9]+.[0-9]+.[0-9]+` | tag glob that gates publishing |
| `zipxAffectedOnPR` | `Boolean` | `true` | emit affected setup when a Graph Verify capability is present |
| `zipxAffectedOnPush` | `Boolean` | `false` | also scope pushes (via `before`-sha diff) |
| `zipxSkipMergedPrPush` | `Boolean` | `true` | skip Verify on branch pushes when the commit already belongs to a merged PR (avoids re-testing after merge) |

### Per-project (bare settings apply to all modules; each module can override)

| Setting | Type | Default | Purpose |
|---|---|---|---|
| `zipxCiRelevant` | `Boolean` | `true` (false for aggregators) | include in test participation |
| `zipxPublish` | `Option[Boolean]` | `None` → derived from `publishArtifact` | force publish on/off |
| `zipxDocker` | `Boolean` | derived from `DockerPlugin` | build a docker image |
| `zipxTestTask` | `String` | `"test"` | sbt task used to test this module |
| `zipxPublishTask` | `String` | `"publish"` | sbt task used to publish this module |

### Capability model (`zipx.core`, re-exported via `autoImport`)

`Capability` fields: `name`, `phase` (`Verify`/`Publish`/`Deploy`), `ordering`, `gate`, `participates`, `command`, `matrixed`, `targets`, `needsCapabilities`, `permissions`, `runsOn`, `extraSteps`, `scope` (`Aggregate`/`Layer`/`Graph`/`Once`), `env` (`Map[String, EnvValue]`), `workflowCall` (optional reusable-workflow `uses`/`with`).

Constructors: `Capability.test` / `.publish` / `.docker` (Aggregate), `.*Layers`, `.*Graph`, `Capability.deploy` / `.deployGraph`, `Capability.custom`, `Capability.once`. Packs: `ZipxCentral.release` / `.publishSigned` / `.releaseOnce`, `ZipxDocs.pages(...)`. A `Target` is `(name, environment, env, condition)` with typed `EnvValue`s (`secret"NAME"`, `EnvValue.plain`, …); a `StepContext` (passed to `extraSteps`) is `(node, target, matrixed)`. Job env merge order: cache → capability → target (later wins).

### Tasks

| Task | Purpose |
|---|---|
| `zipxWorkflowGenerate` | write the workflow YAML |
| `zipxWorkflowCheck` | fail if the committed YAML is stale |
| `zipxGraph` | print modules, edges, flags, and topological layers |
| `zipxPublishOrder` | print the dependency-ordered publish waves |
| `zipxAffectedModules <base-ref>` | print affected modules since a git ref (used by the `affected` job) |

## Example

[`examples/monorepo`](examples/monorepo) is a runnable monorepo that generates its **whole** pipeline from the build — fmt gate → **Layer** test/publish → Graph multi-registry docker → **Aggregate-by-target** staging/production deploy — with the deploy/registry config as typed lists in [`project/Deploy.scala`](examples/monorepo/project/Deploy.scala) and **no external YAML**. It's the reference for wiring modes deliberately.

## Developing zipx (dogfood)

The **root** build loads zipx from **source** via a meta-build mirror (`project/dogfood.sbt`), not via `publishLocal`:

- `project/meta-{workflow,core,central,plugin}` compile the same `modules/*/src/main/scala` trees (wired in [`project/dogfood.sbt`](project/dogfood.sbt)).
- Shared versions and library deps live in [`project/Dependencies.scala`](project/Dependencies.scala) (used by both the meta mirror and `build.sbt`).
- Because `project/*.sbt` is the meta-meta layer, [`project/project/Dependencies.scala`](project/project/Dependencies.scala) and [`project/project/Dogfood.scala`](project/project/Dogfood.scala) are **symlinks** to those files so dogfood can see them. Edit only the real files under `project/`; do not replace the symlinks with copies.

**After changing** sources under `modules/{workflow,core,central,sbt-plugin}` that the plugin uses: run `reload` (then `zipxWorkflowGenerate` if you changed planner output).

**When adding a library dependency** used by those modules: update `project/Dependencies.scala` only (main + meta stay in sync).

**When adding a new mirrored module:** add a `meta*` project in `project/dogfood.sbt`, create `project/meta-<name>/`, and wire `dependsOn` like the existing chain.

The publishable `plugin` project in `build.sbt` remains for Central publish and scripted tests. [`examples/monorepo`](examples/monorepo) is a **consumer** and still uses `publishLocal` (or a released `zipx-sbt` version); that is separate from root dogfood. Root dogfood uses Aggregate `ZipxCentral.release`.

## Status

Milestones M0–M8 are complete; **M9a** adds Aggregate-first defaults, Layer waves, and deploy-by-target batching (see [ROADMAP.md](ROADMAP.md)). The plugin targets sbt 2.0.1 / Scala 3.8.4.

## License

Apache-2.0
