package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Path-based affected gating: fail-open handoff, who is gated today, and what comes next. */
object AffectedDoc extends DocSpecSuite:

  def doc = page("Affected")(
    md"""
Skip this page unless you opted into **Graph** mode. **Affected** means: only run GitHub jobs for modules this PR
touched. Aggregate (the default) does not skip jobs this way; sbt's cache skips work *inside* the one test job
instead (see **Execution modes**).

**Graph Verify** jobs are path-gated by default. **Graph Publish** and **Graph Deploy** can be, under the
`zipxAffectedPublish` and `zipxAffectedDeploy` opt-ins below. Aggregate and Layer jobs never are.
""",
    section("Closure flow")(
      md"""
```mermaid
flowchart TD
  Diff([1 · git diff]) --> Own[2 · owning module]
  Own --> Closure[3 · reverse-dep closure]
  Closure --> Json[4 · modules JSON]
  Json --> Gate([5 · Graph Verify gate])
  class Diff,Own,Closure,Json warn
  class Gate happy
```

### From git diff to owning module

The `affected` job takes the PR's changed paths (repo-root-relative, from `git diff` against the base
ref) and maps each file to the module or modules that own it:

1. **Build files force everything.** If any path ends in `.sbt` or sits under a `project/` directory
   (root or nested), the whole module set is affected. Plugins and the graph may have changed, so nothing
   is safe to skip.
2. **Otherwise: longest owned-path prefix.** A module owns its `baseDir` *and* its source directories
   (sbt's `unmanagedSourceDirectories`, Compile and Test). A file is owned by every module whose longest
   matching prefix is the longest match overall. Matching is directory-aware: `core/` owns
   `core/src/X.scala`, but not `core-lib/…` or `core-extra/…`. Nested bases win:
   `mods/inner/X.scala` belongs to `mods/inner`, not `mods`.
3. **Unowned paths seed nothing.** `README.md`, `.github/…`, and other files outside every module's
   owned paths are ignored (unless step 1 applies). Aggregators with empty `baseDir` never own files.

Step 2 returns a **set**, not a single module. One file can genuinely belong to more than one module; the
next section is the case where it always does.

Those owning modules are the **seeds**. Step 3 of the chart expands them to the reverse-dependency
closure; step 5 gates each Graph Verify job on whether its id (or `all`) appears in the published JSON.

| Changed path | Owning module | After closure (example) |
|---|---|---|
| `client/src/…` | `client` (leaf) | just `client` |
| `models/src/…` | `models` | `models` + every transitive dependent |
| `mods/inner/X.scala` | `inner` (longer than `mods`) | `inner` + dependents |
| `README.md` | none | empty (no Graph Verify) |
| `build.sbt` / `project/plugins.sbt` | (build file) | **all** modules |

```scala
zipxAffectedOnPR   := true   // default; emits `affected` only when Graph Verify is present
zipxAffectedOnPush := false  // opt-in: also scope branch pushes via before-sha
```
""",
      exampleValue {
        given PlanConfig = config.copy(affected = AffectedMode.AffectedOnPR)
        DocsRender.body(Capability.testGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("affected:"),
          yaml.contains("needs.affected.outputs.modules"),
          yaml.contains("contains(fromJson(needs.affected.outputs.modules), 'all')"),
        )
      ),
    ),
    section("Cross-built modules (projectMatrix)")(
      md"""
sbt 2 has `projectMatrix` built in, and a cross-built module is where `baseDir` stops being able to answer at all.
Each platform row is a real project with its own id (`core`, `coreJS`), but sbt bases every row at a **synthetic**
directory:

```
core / baseDirectory     = .sbt/matrix/core
coreJS / baseDirectory   = .sbt/matrix/coreJS
```

No source file is ever under those. A `baseDir`-only rule maps `core/src/main/scala/Foo.scala` to *no* module, so
both rows skip and the PR is green having compiled nothing. That is why a module owns its source directories too:

```
core   / Compile / unmanagedSourceDirectories = core/src/main/{scala, scala-3, scalajvm, scalajvm-3, java, javajvm}
coreJS / Compile / unmanagedSourceDirectories = core/src/main/{scala, scala-3, scalajs,  scalajs-3,  java, javajs}
```

Those directories also carry the platform distinction, which is what makes the answer precise rather than merely
non-empty:

| Changed path | Owning modules | Why |
|---|---|---|
| `core/src/main/scala/Foo.scala` | `core` **and** `coreJS` | shared: on both rows' source dirs |
| `core/src/main/scalajs/Foo.scala` | `coreJS` | on the JS row alone |
| `core/src/main/scalajvm/Foo.scala` | `core` | on the JVM row alone |
| `core/README.md` | none | under no row's `baseDir` or source dirs |

A shared change reaching **both** rows is the property worth stating plainly: picking one would leave half of a
cross-built module untested behind a green check, and which half you got would depend on iteration order. Ownership
is a set for exactly this reason.

`target/` and a row's `.sbt/matrix/<id>` are excluded from the owned paths: nobody edits them, and a
generated-source directory under `target/` would make every module affected on every commit.

Nothing changes for an ordinary project. Its source dirs are all under its `baseDir`, so recording them only ever
*adds* ownership; `baseDir` still answers for a module's non-source files (a README, a `Dockerfile`, a test fixture).
"""
    ),
    section("Fail open, not closed")(
      md"""
The affected handoff must never turn a broken git diff into a green, untested PR. Two outcomes used to look the same
(`[]`): “diff succeeded and found nothing” versus “diff could not run.” Empty JSON makes every
`contains(..., '<id>')` false, so every Graph Verify job skips.

```mermaid
flowchart TD
  Diff[gitDiffNames] --> SomeFiles{Some files?}
  Diff --> None[None · diff failed]
  SomeFiles -->|yes| Aff[affectedModules · may be empty]
  SomeFiles -->|empty Nil| Empty[emit empty list]
  Aff --> Write[write modules JSON]
  Empty --> Skip([skip Graph Verify · deliberate])
  None --> All[emit all]
  All --> Run([every Verify job runs · fail OPEN])
  Write --> Gate([gate per module])
  class Diff,SomeFiles warn
  class None,All,Run happy
  class Empty,Skip warn
  class Aff,Write,Gate happy
```

| Diff outcome | Value | Emitted | CI result |
|---|---|---|---|
| Succeeded, no changes | `Some(Nil)` | `[]` | Skip Graph Verify (deliberate) |
| Could not run (bad ref, no git, …) | `None` | `["all"]` | Run everything |
| Succeeded with files | `Some(files)` | affected closure | Gate per module |

A broken base ref costs runner minutes, not coverage. The `affected` job logs a warning when it disables gating for
that run.
"""
    ),
    section("Who is gated")(
      md"""
| Capability shape | Path-affected? | Why |
|---|---|---|
| `Capability.testGraph` (and other Graph + Verify) | Yes, by default | Per-module jobs can skip |
| Graph Publish (`publishGraph`, `dockerGraph`) | Only under `zipxAffectedPublish` | See the next section: the two risks are not symmetric |
| Graph Deploy (`deployGraph`) | Only under `zipxAffectedDeploy` | So a deploy skips exactly when the publish it consumes did |
| Aggregate / Layer, any phase | Never | One sbt session over every module: there is nothing in it to skip |

`Gate.AffectedOnly` is a **design seam**, not a shipped gate. Affected-gating is derived from phase + scope +
`zipxAffectedOnPR` / `zipxAffectedPublish` / `zipxAffectedDeploy`, not from `Gate`. The planner **rejects**
`Gate.AffectedOnly` at generate time so it cannot silently mean Always.
"""
    ),
    section("Narrowing Publish (zipxAffectedPublish)")(
      md"""
Rebuilding and pushing eight images because one module changed is the cost this setting removes. It is a **separate
switch** from `zipxAffectedOnPR` rather than a widening of it, and that is a deliberate asymmetry:

> **Under-verifying is silently unsafe. Under-publishing is loudly broken.**

A Verify job that wrongly skips gives you a green PR whose code was never tested, and nothing tells you. A Publish
job that wrongly skips gives you a deploy that fails immediately for a missing artifact. One switch for both would
price Publish's narrowing at Verify's risk, so Verify's gating is on by default and Publish's has to be asked for:

```scala
zipxAffectedOnPR    := true   // default: Graph Verify is narrowed on PRs
zipxAffectedPublish := true   // opt-in: Graph Publish is narrowed too
```

Three properties carry over unchanged, and each is what makes the opt-in safe rather than merely cheap:

1. **A release tag publishes everything.** There is no base ref to diff a tag against, so `affected` emits `["all"]`
   for a tag push without taking a diff at all (see the table above). The `|| contains(…, 'all')` clause in every
   gated job is the other half. The question "affected relative to what, after a series of merges?" therefore does
   not arise.
2. **Fail open.** A diff that could not run emits `["all"]`, so a bad base ref publishes too much rather than too
   little.
3. **A skipped image never silently skips its deploy.** This is the trap the feature opens, so the planner closes it
   (see the next section).

The `affected` job itself now runs on tag pushes and on merged-PR pushes when a Publish or Deploy capability reads
it, where a Verify-only setup skips there. That is free: on a non-PR event it takes no diff, and it emits `["all"]`.
""",
      exampleValue {
        given PlanConfig = config.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        DocsRender.jobs("publish-schema", "publish-api")(Capability.publishGraph)
      }.assert(yaml =>
        assertTrue(
          // The release gate survives the narrowing; losing it would publish off every PR.
          yaml.contains("startsWith(github.ref, 'refs/tags/v')"),
          yaml.contains("contains(fromJson(needs.affected.outputs.modules), 'schema')"),
          yaml.contains("contains(fromJson(needs.affected.outputs.modules), 'all')"),
          yaml.contains("needs.publish-schema.result != 'failure'"),
        )
      ),
      md"""
With the switch off (the default), the same jobs carry the release gate alone and never mention `affected`, so
turning it on is the only thing that changes a committed `ci.yml`:
""",
      exampleValue {
        given PlanConfig = config.copy(affected = AffectedMode.AffectedOnPR)
        DocsRender.job("publish-api")(Capability.publishGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("startsWith(github.ref, 'refs/tags/v')"),
          !yaml.contains("needs.affected"),
        )
      ),
    ),
    section("Tolerating a skipped need")(
      md"""
Narrowing Publish means a job can now **skip** in a phase where nothing skipped before, and that changes what its
dependents see. GitHub's implicit `success()` treats a *skipped* need exactly like a failed one: the dependent is
skipped too. So every job that needs something narrowable gains two kinds of clause:

| Clause | Purpose |
|---|---|
| `!cancelled()` | Makes the job reachable at all once a need can skip, by displacing the implicit `success()` |
| `needs.<id>.result != 'failure'` | Restores the blocking that `!cancelled()` just removed, per need |

`!= 'failure'` rather than `== 'success'`, because `skipped` is the answer being tolerated. Two needs are excluded
from the guard because each already has a clause of its own: `affected` (read through its output) and `verify-gate`
(fail-open by design, where a skipped gate means "run").

```mermaid
flowchart TD
  Aff([affected]) --> P1[publish-schema · in the diff]
  Aff --> P2[publish-api · skipped]
  P1 --> Ann([announce · a Once job])
  P2 --> Ann
  Ann --> Ok([runs · !cancelled and no need failed])
  class Aff,P1 happy
  class P2 warn
  class Ann,Ok happy
```

A **failed** need still blocks, which is the property worth checking after any change here: a red `fmt` job must not
be let through by the same `!cancelled()` that lets a skipped publish through.
""",
      exampleValue {
        given PlanConfig = config.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        DocsRender.job("announce")(
          Capability.publishGraph,
          Capability.once(
            CapabilityName("announce"),
            SbtCommand.unsafeTask("announce"),
            phase = Phase.Deploy,
            gate = Gate.OnReleaseTag,
            needsCapabilities = List(Capability.PublishName),
          ),
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("!cancelled()"),
          yaml.contains("needs.publish-schema.result != 'failure'"),
          yaml.contains("needs.publish-api.result != 'failure'"),
        )
      ),
      md"""
### The one shape that is refused instead of tolerated

Tolerance is right for a job whose command does not name the skipped module: a build-wide `announce` loses nothing
when one module did not publish. It is **wrong** for a job that names it.

`Capability.deploy` is exactly that, and it is `Aggregate` by default while needing `docker`. Under
`zipxAffectedPublish` alone, an affected-skipped `docker-<module>` would leave the deploy **running** and pulling an
image tag that run never pushed: a 404 on `main`, from a `ci.yml` in which nothing looks wrong. There is no way to
drop one module's command from an already-joined sbt session at generate time, so the planner refuses the
combination rather than generating it:

```text
zipx: capability 'deploy' is Aggregate and needs 'docker', which zipxAffectedPublish lets skip per module. One
'docker' job skipping would leave 'deploy' running against an artifact nobody built, so this is refused rather than
generated. Fixes, in order of preference: give 'deploy' CapabilityScope.Graph so it skips with its own 'docker' job;
make its command resolve a moving tag that a skipped 'docker' cannot invalidate; or turn zipxAffectedPublish off.
```
""",
    ),
    section("Narrowing Deploy (zipxAffectedDeploy)")(
      md"""
The first fix the error above recommends is the one to reach for, and `zipxAffectedDeploy` is what makes it hold: a
`Graph` deploy carries the **same** per-module affected expression as its own `docker-<module>` job, so the two skip
together. `deploy-<module>-prod` runs exactly when `docker-<module>` did.

Its own switch rather than a widening of `zipxAffectedPublish`, because narrowing image pushes while still
reconciling every destination on every run is a legitimate combination, and one switch would take it away:

```scala
zipxAffectedPublish := true   // one changed module, one image pushed
zipxAffectedDeploy  := true   // and one destination reconciled, not all of them
```

```mermaid
flowchart TD
  Aff([affected · api only]) --> D1[docker-api]
  Aff --> D2[docker-web · skipped]
  D1 --> Dep1([deploy-api-prod])
  D2 --> Dep2([deploy-web-prod · skipped])
  class Aff,D1,Dep1 happy
  class D2,Dep2 warn
```

Off by default: a deploy that does not run leaves a destination on its previous version, which is correct only when
that module's artifacts really are unchanged. The cost of `Graph` over `Aggregate` is job count, one per (module ×
target), and with it one approval per module per environment.

Both safety properties carry over. A **release tag deploys everything**: the `affected` job is forced onto tag
pushes and merged-PR pushes when a Deploy capability reads it, and on a non-PR event it emits `["all"]` without
taking a diff. And an unusable diff **fails open** to `["all"]` the same way.
""",
      exampleValue {
        given PlanConfig =
          config.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true, affectedDeploy = true)
        DocsRender.job("deploy-service-prod")(
          Capability.dockerGraph,
          Capability.deployGraph(
            participates = _.docker,
            command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deployTask")),
            targets = _ => List(Target(TargetName("prod"), environment = Some("production"))),
            gate = Gate.Always,
            condition = Some(JobCondition.refIs("refs/heads/main")),
          ),
        )
      }.assert(yaml =>
        assertTrue(
          // Its own module's clause, which is what puts it in lockstep with docker-service.
          yaml.contains("contains(fromJson(needs.affected.outputs.modules), 'service')"),
          yaml.contains("contains(fromJson(needs.affected.outputs.modules), 'all')"),
          // And neither the branch condition nor the Environment approval was displaced by the narrowing.
          yaml.contains("github.ref == 'refs/heads/main'"),
          yaml.contains("environment: production"),
          yaml.contains("needs.docker-service.result != 'failure'"),
        )
      ),
    ),
    section("Concurrency (cancel superseded runs)")(
      md"""
Superseded PR pushes should not burn runners. zipx emits workflow-level concurrency by default:

```yaml
concurrency:
  group: CI-$${{ github.ref }}
  cancel-in-progress: $${{ !startsWith(github.ref, 'refs/tags/') }}
```

```mermaid
flowchart LR
  PR[push to PR branch] --> Cancel([cancel in-flight run · on that ref])
  Tag[push of release tag] --> Keep([never cancel · publish is not idempotent])
  class PR,Cancel warn
  class Tag,Keep happy
```

A half-cancelled Central publish can leave a staged-but-unreleased bundle; that is worse than a wasted runner. Opt
out with `zipxCancelSupersededRuns := false`.
""",
      exampleValue {
        DocsRender.body(Capability.test)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("concurrency:"),
          yaml.contains("cancel-in-progress:"),
          yaml.contains("!startsWith(github.ref, 'refs/tags/')"),
        )
      ),
      exampleValue {
        given PlanConfig = config.copy(cancelSupersededRuns = false)
        DocsRender.body(Capability.test)
      }.assert(yaml => assertTrue(!yaml.contains("concurrency:"))),
    ),
  )
end AffectedDoc
