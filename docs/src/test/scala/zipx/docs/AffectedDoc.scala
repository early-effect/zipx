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
**Affected** answers a GitHub Actions question Graph mode can ask: which *jobs* should run for this PR's diff?
That is different from sbt 2 incrementality inside Aggregate (see **Execution modes**). **Graph Verify** jobs are
path-gated by default. **Graph Publish** jobs can be, under the separate `zipxAffectedPublish` opt-in below. Deploy
never is.
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
ref) and maps each file to at most one module:

1. **Build files force everything.** If any path ends in `.sbt` or sits under a `project/` directory
   (root or nested), the whole module set is affected. Plugins and the graph may have changed, so nothing
   is safe to skip.
2. **Otherwise: longest `baseDir` prefix.** Each module's `baseDir` is a path prefix. A file is owned by
   the matching module whose `baseDir` is longest (most specific). Matching is directory-aware:
   `core/` owns `core/src/X.scala`, but not `core-lib/…` or `core-extra/…`. Nested bases win:
   `mods/inner/X.scala` belongs to `mods/inner`, not `mods`.
3. **Unowned paths seed nothing.** `README.md`, `.github/…`, and other files outside every module
   `baseDir` are ignored (unless step 1 applies). Aggregators with empty `baseDir` never own files.

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
| Aggregate / Layer, any phase | Never | One sbt session over every module: there is nothing in it to skip |
| Deploy | Never | A deploy is about a destination's desired state, not about what a diff touched |

`Gate.AffectedOnly` is a **design seam**, not a shipped gate. Affected-gating is derived from phase + scope +
`zipxAffectedOnPR` / `zipxAffectedPublish`, not from `Gate`. The planner **rejects** `Gate.AffectedOnly` at generate
time so it cannot silently mean Always.
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

The `affected` job itself now runs on tag pushes when a Publish capability reads it, where a Verify-only setup skips
there. That is free: on a tag it takes no diff.
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
skipped too. `Capability.deploy` needs `docker` by default, so without care an affected-skipped `docker-<module>`
would silently skip the deploy that wanted the other modules' images. That is the failure this feature exists to
avoid, not to create.

So every job that needs something narrowable gains two kinds of clause:

| Clause | Purpose |
|---|---|
| `!cancelled()` | Makes the job reachable at all once a need can skip, by displacing the implicit `success()` |
| `needs.<id>.result != 'failure'` | Restores the blocking that `!cancelled()` just removed, per need |

`!= 'failure'` rather than `== 'success'`, because `skipped` is the answer being tolerated. Two needs are excluded
from the guard because each already has a clause of its own: `affected` (read through its output) and `verify-gate`
(fail-open by design, where a skipped gate means "run").

```mermaid
flowchart TD
  Aff([affected]) --> D1[docker-serviceA · in the diff]
  Aff --> D2[docker-serviceB · skipped]
  D1 --> Dep([deploy-prod])
  D2 --> Dep
  Dep --> Ok([runs · !cancelled and no need failed])
  class Aff,D1 happy
  class D2 warn
  class Dep,Ok happy
```

A **failed** need still blocks, which is the property worth checking after any change here: a red `fmt` job must not
be let through by the same `!cancelled()` that lets a skipped image through.
""",
      exampleValue {
        given PlanConfig = config.copy(affected = AffectedMode.AffectedOnPR, affectedPublish = true)
        DocsRender.job("deploy-prod")(
          Capability.dockerGraph,
          Capability.deploy(
            participates = _.docker,
            command = n => SbtCommand.module(n, SbtCommand("deployTask")),
            targets = _ => List(Target(TargetName("prod"), environment = Some("production"))),
          ),
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("!cancelled()"),
          yaml.contains("needs.docker-service.result != 'failure'"),
          // Deploy is never itself narrowed, whatever the switch says.
          !yaml.contains("needs.affected.outputs.modules"),
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
