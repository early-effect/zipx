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
That is different from sbt 2 incrementality inside Aggregate (see **Execution modes**). Today only **Graph Verify**
jobs are path-gated. Publish and Deploy stay release-gated; Deploy is never affected-gated.
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
    section("Who is gated today")(
      md"""
| Capability shape | Path-affected? | Why |
|---|---|---|
| `Capability.testGraph` (and other Graph + Verify) | Yes | Per-module jobs can skip |
| Aggregate / Layer Verify | No | One (or few) jobs; sbt cache skips work inside |
| Publish / docker | No (yet) | `Gate.OnReleaseTag` only; Publish affected is an open seam |
| Deploy | Never | Environments and approvals are destination-driven |

`Gate.AffectedOnly` is a **design seam**, not a shipped gate. Affected-gating is derived from phase + scope +
`zipxAffectedOnPR`, not from `Gate`. The planner **rejects** `Gate.AffectedOnly` at generate time so it cannot
silently mean Always.
"""
    ),
    section("Proving more affected value")(
      md"""
Existing machinery already path-gates any `phase = Verify` + `scope = Graph` capability. The next proof is not a
new Gate: put expensive Verify stages on Graph (scripted, MiMa, PR-local docker builds) and measure leaf-PR skips.

```mermaid
flowchart TD
  W1[Wave 1: Graph Verify proofs · scripted, MiMa, dockerLocal] --> W2[Wave 2: composable OnReleaseTag and Affected]
  W2 --> P[Publish or docker on tag]
  W2 --> X[Deploy stays excluded]
  class W1 warn
  class W2,P happy
  class X warn
```

Partial monorepo publish on a tag is the headline Wave 2 win; until Gate can compose with release, keep Publish on
`OnReleaseTag` alone.
"""
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
