package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite

/** Contributing to zipx itself. */
object Developing extends DocSpecSuite:

  def doc = page("Developing")(
    md"""
The **root** build loads zipx from **source** via a meta-build mirror (`project/dogfood.sbt`), not via `publishLocal`.
""",
    section("Dogfood layout")(
      md"""
- `project/meta-{workflow,core,central,plugin}` compile the same `modules/*/src/main/scala` trees
- Shared versions live in [`project/Dependencies.scala`](https://github.com/early-effect/zipx/blob/main/project/Dependencies.scala)
- `project/project/Dependencies.scala` and `Dogfood.scala` are **symlinks** — edit only the real files under `project/`

**After changing** sources under `modules/{workflow,core,central,sbt-plugin}`: `reload`, then `zipxWorkflowGenerate` if
planner output changed.

**Action pins:** edit [`.github/zipx/action-pins.yml`](https://github.com/early-effect/zipx/blob/main/.github/zipx/action-pins.yml)
(not under `workflows/`), then regenerate. Or let Dependabot bump workflow `uses:` and run `sbt zipxActionsPull`
(dogfood enables `zipxDependabotSync := true` for the automatic sync workflow). Published jar defaults embed this
pin file via `resourceGenerators`. See the **Action pins** docs page.

**When adding a library dependency** used by those modules: update `project/Dependencies.scala` only.

**When adding a mirrored module:** add a `meta*` project in `project/dogfood.sbt`, create `project/meta-<name>/`, and
wire `dependsOn` like the existing chain.

The publishable `plugin` project remains for Central publish and scripted tests.
[`examples/monorepo`](https://github.com/early-effect/zipx/tree/main/examples/monorepo) is a **consumer** (uses
`publishLocal` or a released `zipx-sbt`). Root dogfood uses Aggregate `ZipxCentral.release` and `ZipxDocs.pages`,
both with `JobCondition.repositoryIs("early-effect/zipx")` so fork tag pushes do not publish or deploy Pages.
"""
    ),
    section("Docs site")(
      md"""
Docs are Specular DocSpecs under `docs/src/test/scala`:

```
sbt docs/test
sbt docs/specularSite
sbt docs/specularServe
```

Pages deploy on `v*` tags via `ZipxDocs.pages` in the generated workflow.
"""
    ),
    section("Status")(
      md"""
See [ROADMAP.md](https://github.com/early-effect/zipx/blob/main/ROADMAP.md). The plugin targets sbt 2.0.3 / Scala 3.8.4.
License: Apache-2.0.
"""
    ),
  )
end Developing
