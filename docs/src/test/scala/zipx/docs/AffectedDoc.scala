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
```
 changed files (git diff)
         │
         ▼
 owning module (longest baseDir prefix)
         │
         ▼
 reverse-dependency closure  ──►  modules JSON
         │
         ▼
 Graph Verify job `if:` contains(id) || contains('all')
```

A leaf change runs only that leaf. Changing a shared library runs the library and every transitive dependent. A
`.sbt` change or anything under `project/` forces the full module set. Unowned paths (README, `.github/…`) affect
nothing unless they are build files.

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

```
                    gitDiffNames
                         │
           ┌─────────────┴─────────────┐
           ▼                           ▼
     Some(files)                      None
   (diff ran)                   (diff failed)
           │                           │
           ▼                           ▼
   affectedModules              emit ["all"]
   (may be [])                  fail OPEN
           │                           │
           ▼                           ▼
   write modules JSON          every Verify job runs
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
```
                 ┌──────────────┐
  PlanConfig     │ AffectedOnPR │
  + Graph Verify └──────┬───────┘
                        │
                        ▼
              ┌─────────────────┐
              │  affected job   │
              └────────┬────────┘
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
   Graph Verify   Aggregate/     Publish /
   (path-gated)   Layer Verify   Deploy
                  (always run    (release tag;
                   stage cmd)    Deploy never
                                 affected)
```

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

```
Wave 1 (no Gate change)          Wave 2 (design)
────────────────────────         ────────────────────────
scripted Graph Verify            composable gates so
mimaReportBinaryIssues           OnReleaseTag ∩ Affected
dockerLocal on PR (Verify)       works for publish/docker
                                 (tag base ≠ PR base;
                                  Deploy stays excluded)
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

```
  push to PR branch ──► cancel in-flight run on that ref
  push of release tag ─► never cancel (publish is not idempotent)
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
