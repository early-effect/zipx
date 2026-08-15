package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite

/** Contributing to zipx itself. */
object Developing extends DocSpecSuite:

  def doc = page("Developing")(
    md"""
This page is for people hacking on **zipx itself**, not for adopting it in your repo. Start at **Quick start**.

The **root** build loads zipx from **source** via a meta-build mirror (`project/dogfood.sbt`), not via `publishLocal`.
""",
    section("Dogfood layout")(
      md"""
```mermaid
flowchart TD
  Modules([1 · modules sources]) --> Meta[2 · project meta mirrors]
  Meta --> Root([3 · root sbt load])
  Modules --> Plugin[2b · plugin project]
  Plugin --> Central([3b · Central + scripted])
  class Modules warn
  class Meta,Root happy
  class Plugin,Central warn
```

Same trees under `modules/*/src`: the green path is dogfood (`project/meta-*` mirrors → root loads from
source). The amber path is the publishable `plugin` project for Central and scripted.

- `project/meta-{workflow,core,central,plugin}` compile the same `modules/*/src/main/scala` trees
- Shared versions for the **main** build live in [`project/ZipxVersions.scala`](https://github.com/early-effect/zipx/blob/main/project/ZipxVersions.scala)
  (`Lib` / `Plugin`). The meta-build cannot import those types, so dogfood ModuleIDs stay in
  [`project/Dependencies.scala`](https://github.com/early-effect/zipx/blob/main/project/Dependencies.scala)
- `project/*.sbt` cannot see `project/*.scala` directly (sbt layering).
  [`project/project/build.sbt`](https://github.com/early-effect/zipx/blob/main/project/project/build.sbt)
  pulls `Dependencies.scala` / `Dogfood.scala` onto that classpath via `unmanagedSources` (no symlinks)

**After changing** sources under `modules/{workflow,core,central,sbt-plugin}`: `reload`, then `zipxWorkflowGenerate` if
planner output changed.

**Action pins:** edit [`.github/zipx/action-pins.yml`](https://github.com/early-effect/zipx/blob/main/.github/zipx/action-pins.yml)
(not under `workflows/`), then regenerate. Or let Dependabot bump workflow `uses:` and run `sbt zipxActionsPull`
(dogfood enables `zipxDependabotSync := true` for the automatic sync workflow). Published jar defaults embed this
pin file via `resourceGenerators`. See the **Action pins** docs page.

**When adding a library or sbt plugin:** add a `Lib` / `Plugin` row in `project/ZipxVersions.scala` and select it with
`ZipxDeps`. If the meta-build dogfood mirror also needs it, add the same version to `project/Dependencies.scala`.
`sbt zipxWorkflowGenerate` rewrites `project/plugins.sbt` and `project/build.properties`. `zipxCheckDeps`
fails generate if a `libraryDependencies` GAV is not in the catalog.

**When adding a mirrored module:** add a `meta*` project in `project/dogfood.sbt`, create `project/meta-<name>/`, and
wire `dependsOn` like the existing chain.

The publishable `plugin` project remains for Central publish and scripted tests.
[`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo) is a **consumer** (uses
`publishLocal` or a released `sbt-zipx`, with `project/ZipxVersions.scala` like a real repo). Root dogfood uses Aggregate `ZipxCentral.release` and `ZipxDocs.pages`,
both with `JobCondition.repositoryIs("early-effect/zipx")` so fork tag pushes do not publish or deploy Pages.

**Remote-cache live proof** lives in `core` tests (`zipx.it.RemoteCacheItSpec`): plain Testcontainers for bazel-remote
plus an sbt fixture image (Docker required; failure is a clear test failure). It runs under Aggregate `test` / `sbt
core/test`. Pins and Put/Get are documented under **Remote cache for teams** / `RemoteCacheProof`.
"""
    ),
    section("Docs site")(
      md"""
Docs are Specular DocSpecs under `docs/src/test/scala`:

```
sbt docs/testFull        # Specular DocSpecs (same gate as CI; plain docs/test can skip on sbt 2)
sbt docs/specularSite
sbt docsDev              # watch: ~docs/specularPreview (rebuild + restart DocsServe)
```

Open http://127.0.0.1:8765/ while `docsDev` is running. Pages deploy on `v*` tags **or** manual
`workflow_dispatch` (`zipxWorkflowDispatch := true`) via `ZipxDocs.pages` in the generated workflow. Verify is skipped
on dispatch so a docs-only refresh does not re-run the full test suite. Install / chrome versions use
`specularDisplayVersion` (last stable tag when dynver is `*-ci`) so docs-only deploys do not advertise `-ci`
coordinates.
"""
    ),
    section("Status")(
      md"""
See [ROADMAP.md](https://github.com/early-effect/zipx/blob/main/ROADMAP.md) and
[AGENTS.md](https://github.com/early-effect/zipx/blob/main/AGENTS.md) (verification blast radius for agents).
The plugin targets sbt 2.x / Scala 3.8.4. License: Apache-2.0.
"""
    ),
  )
end Developing
