# zipx

**The build describes its own CI.** zipx is an sbt 2.x plugin (Scala 3) that generates a fast, concurrent, dependency-ordered GitHub Actions workflow directly from your sbt build graph — no hand-maintained YAML, no module list to keep in sync, no per-module command strings to copy-paste.

You declare your modules and their `dependsOn` edges once, in `build.sbt`, as you already do. zipx introspects that graph and emits a workflow that:

- **fans out one job per module**, wired by `needs` derived from your real `dependsOn` graph;
- **publishes in true dependency order** (upstream artifacts before downstream), gated on release tags;
- **runs only affected modules on pull requests** (changed modules + their transitive dependents);
- **caches sbt's build state** with a commit-stable key so mid-PR pushes stay warm (local or remote);
- **builds & publishes docker images** for services via sbt-native-packager — so that the image can be described by the build;
- **deploys to multiple environments** (staging/production etc) with per-environment config allowing **human-in-the-loop approval** for production via GitHub Environments;
- **extends to anything** — deploys, multi-registry pushes, lint gates, and stages you invent are all pluggable capabilities;
- **checks itself in CI**: a committed workflow that drifts from the build fails the build.

## Why

The common approach to CI for a Scala monorepo is a hand-written `.github/workflows/*.yml` (often plus an external config file and a resolver script) that re-declares the module set, their dependencies, and per-module test/publish/deploy recipes. That duplicates what `build.sbt` already knows, and the two drift: a new module, a renamed project, or a changed dependency edge silently desyncs CI from the build. Publish ordering is usually not modeled at all — modules publish in parallel and rely on artifacts already existing in the registry.

zipx makes the sbt build the single source of truth. The build graph *is* the CI topology.

## Why sbt 2.0 (the unlock)

zipx isn't just a nicer way to write CI — it's something **sbt 2.0 makes newly possible**. Four sbt 2.0 changes are load-bearing:

- **A machine-wide, content-addressed build cache (Bazel-style "action cache").** This is the big one. Fanning a monorepo out into one job per module is only *fast* if those jobs don't each recompile the world. sbt 2.0's action cache is content-addressed and shared across the machine, so a per-module job restores upstream compilation instead of redoing it. zipx's parallel fan-out and its **commit-stable cache key** (reuse across a whole PR, roll on release) are built directly on this — in sbt 1.x there was nothing to key a cross-job cache on.
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

That's it. For a typical build you write **zero** module lists, `needs` edges, or project-id strings — zipx derives all of it. The rest of this README covers the knobs for when you need them.

> **A note on setting scope.** zipx reads its settings from the root project's scope, so you write plain **bare settings** in `build.sbt` — no `ThisBuild /` prefix needed. Following sbt 2.0's *common settings* model, a bare `zipxTestTask := "testFull"` applies to every module, and any module can override it in its own `.settings(...)`.

## What gets generated

Given a build like:

```scala
lazy val models  = project.settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))
lazy val coreLib = project.dependsOn(models).settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))
lazy val client  = project.dependsOn(coreLib).settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))
lazy val service = project.dependsOn(coreLib).enablePlugins(JavaAppPackaging, DockerPlugin)
lazy val root    = project.in(file(".")).aggregate(models, coreLib, client, service)
```

zipx generates a workflow with:

- an **`affected`** setup job (on PRs) that computes the changed-module set;
- **test jobs** `test-models → test-coreLib → {test-client, test-service}` wired by `needs`, each matrixed over its own `crossScalaVersions`, each gated on affected membership;
- **publish jobs** `publish-models → publish-coreLib → publish-client` (dependency-ordered, release-tag-gated, cross-published with a single `+publish` leg);
- a **`docker-service`** job (release-gated) running `service/Docker/publish`;
- **caching** wired into every job with a commit-stable key.

The aggregating `root` project gets no jobs (it's a container). `service` gets no publish job (it's an app, not a library) but does get a docker job (it enables `DockerPlugin`).

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

A **capability** is a pipeline stage that runs one sbt invocation per participating module. Test, publish, and docker are built-in capabilities; you can add your own. The built-ins:

| Capability | Runs | Participates | Phase | Gate | Matrix |
|---|---|---|---|---|---|
| **test** | `<module>/test` | every CI-relevant module | Verify | always | per-Scala-version |
| **publish** | `+<module>/publish` | modules that publish an artifact | Publish | release tag | none (`+` crosses internally) |
| **docker** | `<module>/Docker/publish` | modules with `DockerPlugin` enabled | Publish | release tag | none |

Capabilities run in phase order — **Verify → Publish → Deploy** — and a capability can depend on another (`needsCapabilities`), e.g. deploy waits on the module's docker job. A `zipxCapabilities += ...` you add is merged with the built-ins; a capability with the same `name` as a built-in **replaces** it (useful for turning the single-target `docker` into a multi-registry one — see below).

### Affected-only execution (PRs)

On a pull request, a leading `affected` job computes which modules changed and outputs them as a JSON array. Each test job runs only if its module is in that set (or its transitive-dependent closure). The mapping is: changed files → owning module (longest base-dir prefix) → reverse-dependency closure. A change to a `.sbt` file or anything under a `project/` directory forces a full build. On push/tag, everything builds (see `zipxAffectedOnPush` to opt into push-time scoping).

The generated `if` conditions handle GitHub's skipped-`needs` hazard: an affected module still runs even when an unaffected upstream was skipped (`!cancelled()` + `needs.<upstream>.result != 'failure'`).

### Caching

sbt 2.x has a machine-wide, content-addressed build cache. zipx persists it in CI. The cache key uses a **commit-stable epoch** (`zipxCacheEpoch`, defaulting to `version`) rather than a per-commit hash — so every push within a PR reuses the cache instead of starting cold. Cutting a release tag rolls the epoch. This pairs naturally with [`sbt-dynver-ci`](https://github.com/early-effect/sbt-dynver-ci), whose version is `<last-tag>-ci` across an entire PR.

Three backends, selected via `zipxCache`:

```scala
zipxCache := CacheBackend.LocalDir                                  // default: actions/cache over ~/.cache/sbt
zipxCache := CacheBackend.BazelRemoteSidecar("buchgr/bazel-remote:latest", 9092)
zipxCache := CacheBackend.ManagedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")
```

- **`LocalDir`** — persist sbt's local cache dir with `actions/cache@v4`, keyed by OS + JDK + epoch. No infrastructure.
- **`BazelRemoteSidecar(image, port)`** — run a `buchgr/bazel-remote` gRPC server as a job service; sbt uses it as a Bazel-protocol remote cache shared across the run.
- **`ManagedRemote(uri, headerSecret)`** — point sbt at a managed gRPC cache (BuildBuddy/EngFlow/NativeLink); the auth header comes from the named repository secret.

The remote-cache transport (`sbt-remote-cache`) is bundled with zipx, so remote backends need no extra plugin. For remote backends zipx also sets `Global / cacheVersion` from a hash of `(JDK, OS)` — the axes sbt itself doesn't hash — so a heterogeneous runner pool can't poison the shared cache.

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

No Dockerfile, no external `docker build` string. zipx auto-detects the enabled plugin and generates a release-gated `docker-service` job running `service/Docker/publish`. Multiple tags / `latest` aliases are handled by sbt-native-packager's `dockerAliases`; pushing one image to **multiple registries/accounts** is a capability fan-out (below).

## Environments, approval & multi-target deploys

Deploying to multiple environments — with production behind a human approval gate — is a **deploy capability** that fans out over **targets**. Each target is a fully-resolved destination (name, optional GitHub Environment, injected env vars, extra `if` condition). Because zipx resolves everything at generate time, it emits **explicit per-target jobs** (`deploy-<module>-<target>`), each independently gated and approvable.

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
// build.sbt
zipxCapabilities += Capability.deploy(
  participates = _.id == "service",
  command      = n => s"${n.id}/promote",     // a real sbt task; reads TIER from the target env
  targets      = _ => DeployEnv.all.map(e =>
    Target(
      name        = e.name,
      environment = e.ghEnvironment,           // binds `environment:` → GitHub enforces required reviewers
      env         = Map(                        // injected into the job's `env:`, referenced as ${{ env.KEY }}
        "AWS_REGION"  -> e.region,
        "DEPLOY_ROLE" -> s"$${{ secrets.${e.roleSecret} }}",
        "TIER"        -> e.tier,
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

This generates `deploy-service-staging` (runs immediately) and `deploy-service-prod` (holds for approval because it binds `environment: production` — configure required reviewers on that environment in your repo settings). Both `needs` the docker job, carry OIDC `permissions`, inject their per-target `env:`, and run the AWS-credentials step from `extraSteps`.

**Approval is enforced by GitHub, not zipx.** zipx emits the `environment:` binding; GitHub pauses the job for the environment's protection rules. zipx generates no manual-approval steps.

## Custom capabilities & run-once gates

`zipxCapabilities` is append-able — any sbt task becomes a CI stage. Two constructors beyond the built-ins:

**`Capability.custom`** — a stage zipx doesn't model directly (all topology knobs exposed). For example, pushing one image to **multiple registries** is a `docker`-named custom capability (same name ⇒ replaces the built-in) that fans out over registry targets, each with its own credentials:

```scala
zipxCapabilities += Capability.custom(
  name         = "docker",                       // replaces the built-in single-target docker
  command      = n => s"${n.id}/Docker/publish",
  participates = _.docker,
  phase        = Phase.Publish,
  targets      = _ => Registry.all.map(r => Target(r.name, env = Map("REGISTRY" -> r.host, "DEPLOY_ROLE" -> r.roleSecret))),
  permissions  = Map("id-token" -> "write", "contents" -> "read"),
).copy(extraSteps = _ => List(Step(name = Some("Login"), uses = Some("aws-actions/configure-aws-credentials@v6"),
  `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}"))))
// ⇒ docker-service-us, docker-service-eu
```

**`Capability.once`** — a single build-wide job (not per module), e.g. a format/lint gate that every test job waits on:

```scala
zipxCapabilities += Capability.once("fmt", "scalafmtCheckAll")
zipxCapabilities += Capability.test.copy(needsCapabilities = List("fmt"))  // every test job now needs the fmt gate
```

Custom capabilities can also override runners (`runsOn = Some(List("self-hosted", "linux"))` renders a list runner) and set `permissions` — the same knobs the built-ins use.

### Typed task keys (`zipxTasks`)

The `command` above is a string because it's ultimately what runs at the sbt shell in CI — and it must express things a single key can't (the cross `+`, command aliases, compound `a; b`). For the common "one task" case, `zipxTasks` offers the same constructors taking a real `TaskKey`/`InputKey` instead, so you get **code completion and compile-time checking** (a renamed or removed task fails at build load, not in CI):

```scala
val promote = taskKey[Unit]("promote the image")
// …
zipxCapabilities += zipxTasks.once("fmt", scalafmtCheckAll)                 // renders `scalafmtCheckAll`
zipxCapabilities += zipxTasks.deploy(_.id == "service", promote, targets)   // renders `service/promote`
```

A key renders to `<module>/<label>`, config-scoped keys keep their config axis (`Docker / publish` → `<module>/Docker/publish`), and a `Once` gate renders the bare `<label>` — all identical to the string form. `zipxTasks` mirrors `once` / `custom` / `deploy`; reach for the plain string only when you need the cross `+`, command aliases, compound `a; b`, or task/args scoping the type can't carry.

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
| `zipxScalaMatrix` | `Boolean` | `true` | expand a per-module Scala matrix |
| `zipxCache` | `CacheBackend` | `LocalDir` | cache backend (see above) |
| `zipxCacheEpoch` | `String` | `version` | commit-stable cache-key epoch |
| `zipxPushBranches` | `Seq[String]` | `Seq("main")` | branches whose pushes trigger CI |
| `zipxReleaseTagPattern` | `String` | `v[0-9]+.[0-9]+.[0-9]+` | tag glob that gates publishing |
| `zipxAffectedOnPR` | `Boolean` | `true` | scope PR builds to affected modules |
| `zipxAffectedOnPush` | `Boolean` | `false` | also scope pushes (via `before`-sha diff) |

### Per-project (bare settings apply to all modules; each module can override)

| Setting | Type | Default | Purpose |
|---|---|---|---|
| `zipxCiRelevant` | `Boolean` | `true` (false for aggregators) | include in the test fan-out |
| `zipxPublish` | `Option[Boolean]` | `None` → derived from `publishArtifact` | force publish on/off |
| `zipxDocker` | `Boolean` | derived from `DockerPlugin` | build a docker image |
| `zipxTestTask` | `String` | `"test"` | sbt task used to test this module |
| `zipxPublishTask` | `String` | `"publish"` | sbt task used to publish this module |

### Capability model (`zipx.core`, re-exported via `autoImport`)

`Capability` fields: `name`, `phase` (`Verify`/`Publish`/`Deploy`), `ordering`, `gate`, `participates`, `command`, `matrixed`, `targets`, `needsCapabilities`, `permissions`, `runsOn`, `extraSteps`, `scope` (`PerModule`/`Once`). Constructors: `Capability.test` / `.publish` / `.docker` (built-ins), `Capability.deploy(...)`, `Capability.custom(...)`, `Capability.once(...)`. A `Target` is `(name, environment, env, condition)`; a `StepContext` (passed to `extraSteps`) is `(node, target, matrixed)`.

### Tasks

| Task | Purpose |
|---|---|
| `zipxWorkflowGenerate` | write the workflow YAML |
| `zipxWorkflowCheck` | fail if the committed YAML is stale |
| `zipxGraph` | print modules, edges, flags, and topological layers |
| `zipxPublishOrder` | print the dependency-ordered publish waves |
| `zipxAffectedModules <base-ref>` | print affected modules since a git ref (used by the `affected` job) |

## Example

[`examples/monorepo`](examples/monorepo) is a runnable monorepo that generates its **whole** pipeline from the build — fmt gate → test → dependency-ordered publish → multi-registry docker → gated staging/production deploy — with the deploy/registry config as typed lists in [`project/Deploy.scala`](examples/monorepo/project/Deploy.scala) and **no external YAML**. It's the reference for wiring the plugin.

## Status

Milestones M0–M6 are complete: test, ordered publish, affected-only, caching (local + remote), docker, and environments/approval/multi-target deploys, plus the extensibility surface. See [ROADMAP.md](ROADMAP.md) for details and design notes. The plugin targets sbt 2.0.1 / Scala 3.8.4.
