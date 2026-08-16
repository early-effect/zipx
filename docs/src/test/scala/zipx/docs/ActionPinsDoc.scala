package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zio.test.*

/** GitHub Action SHA pins as catalog rows. YAML is generate/jar output, never an input. */
object ActionPinsDoc extends DocSpecSuite:

  def doc = page("Action pins")(
    md"""
Skip this page at first. zipx already pins third-party GitHub Actions to **full commit SHAs** (not floating `@v4`
tags) in the generated workflow, so a moved tag cannot change what CI runs. Version labels appear as trailing comments:

```yaml
- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

Jar defaults ship in the plugin. Come back when you want to bump those Actions **without** waiting for a zipx release:
add `Action` vals to `project/ZipxVersions.scala` and run `zipxActionUpdate`. There is no pin YAML to edit, and a
`github-actions` Dependabot ecosystem is not needed.
""",
    section("Resolve order")(
      md"""
When generating a workflow, zipx picks pins in this order:

```mermaid
flowchart TD
  Start[Generate workflow] --> Leftover{committed action-pins.yml?}
  Leftover -->|yes| Fail[Fail: paste Action vals]
  Leftover -->|no| Explicit{zipxActions != Defaults?}
  Explicit -->|yes| UseExplicit[Use zipxActions]
  Explicit -->|no| Overlay[Defaults plus catalog Action rows]
  class Start,Leftover,Explicit warn
  class Fail warn
  class UseExplicit,Overlay happy
```

1. **Leftover pin YAML:** if `.github/zipx/action-pins.yml` is still on disk, generate **fails** and prints the
   `Action(...)` rows to paste. Dual source is not allowed.
2. **`zipxActions`:** only when set *away from* `ActionPins.Defaults` (one-off / escape hatch in `build.sbt`)
3. **Catalog overlay:** `ActionPins.Defaults` (jar) plus every `Action` val in ZipxVersions. A row whose name is a
   field prefix (`actions/checkout`) updates that field. Any other name is `extra`, keyed by `owner/repo`.

No `Action` vals still means jar defaults. Upgrade zipx to take new default SHAs. This repository lists every
zipx-emitted Action so `zipxActionUpdate` can bump them before a release.
"""
    ),
    section("Catalog rows")(
      md"""
`Action` is a catalog type next to `Lib` / `Plugin` / `Pin`. A git commit SHA is not a content checksum: do not reuse
`Pin` for Actions.

```scala
val checkout = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
```

- `name` is `owner/repo` or `owner/repo/path` (the `uses:` prefix).
- `version` is the label stamped as `# vX.Y.Z`.
- `sha` is a **full 40-hex commit SHA** at construction. Combined `name@sha` must be a valid `ActionRef`.

Apply rewrites the canonical constructor so version and sha move together. `zipxActionUpdate` looks up GitHub
releases (tags if there are none), peels the tag to a commit SHA, and queries OSV (`pkg:github/owner/repo@version`).
Never write a floating `@v4` into the catalog or into `uses:`.

```
sbt zipxActionUpdate             # list, then prompt
sbt "zipxActionUpdate yes"       # rewrite constructors
sbt "zipxActionUpdate dry-run"
```

After apply: `reload`, then `sbt zipxCatalogGenerate` (composites, `plugins.sbt`, `zipx-ci.env`). Use `sbt zipxWorkflowGenerate` when `ci.yml` itself must move (checkout SHA, job graph).

If there are no Action rows, the command prints constructors to paste (jar Defaults compared to GitHub). `yes` with no
rows refuses.
""",
      exampleValue {
        val src =
          """val checkout = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
            |""".stripMargin
        val action = Action("actions/checkout", "v7.0.1", sha = "3d3c42e5aac5ba805825da76410c181273ba90b1")
        val bump   = ActionBump(action, BumpKind.Minor, "v8.0.0", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        ZipxCatalog.applyActionBumps(src, List(bump))
      }.assert(out =>
        assertTrue(
          out.exists(
            _.contains("""Action("actions/checkout", "v8.0.0", sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")""")
          ),
          !out.exists(_.contains("v7.0.1")),
        )
      ),
    ),
    section("Pins for actions zipx does not emit")(
      md"""
Typed fields are the actions **zipx itself** writes. An action *you* reach through `extraSteps`, a custom capability,
or a published pack has no field. Those are `Action` vals whose name is not a field prefix; overlay puts them in
`extra`, keyed by `owner/repo`. Packs look up by prefix (`aws-actions/configure-aws-credentials`), not a YAML key.

```scala
val awsCredentials = Action(
  "aws-actions/configure-aws-credentials",
  "v6.0.0",
  sha = "b47578312673ae6fa5b5096b330d9fbac3d13d67",
)
```

In Scala, `zipxActions := ActionPins.Defaults.copy(...)` / `.withExtra` is the one-off hatch. Prefer a catalog row.

**A typed field and an extra pin are not interchangeable:**

| | typed field | extra (non-field `Action` name) |
|---|---|---|
| For | an action zipx emits | an action your steps or a pack emit |
| Key | closed set of prefixes | `owner/repo` |
| Ref must be pinned | yes | yes |
| Ref must name the *right* action | yes, via the field's known prefix | **no** prefix check beyond the name you wrote |

`checkout` as `evil/malware@<sha>` is refused because that field may only name `actions/checkout`.
""",
      exampleValue {
        val aws = Action(
          "aws-actions/configure-aws-credentials",
          "v6.0.0",
          sha = "b47578312673ae6fa5b5096b330d9fbac3d13d67",
        )
        ActionPins.overlay(ActionPins.Defaults, List(aws))
      }.assert(pins =>
        assertTrue(
          pins.toOption.flatMap(_.extraByPrefix("aws-actions/configure-aws-credentials")).nonEmpty,
          pins.exists(_.extraVersion("aws-actions/configure-aws-credentials").contains("v6.0.0")),
        )
      ),
    ),
    section("`setupNode`: a typed field zipx emits only on request")(
      md"""
`setupNode` is a typed field, like `checkout`, because zipx writes the step itself. Unlike the others it appears in a
workflow only when a capability asks for a Node version:

```scala
zipxCapabilities += Capability.testGraph.withNodeVersion(NodeVersion("22"))
```

Off by default, and for a Scala.js build that is usually right: sbt-scalajs downloads its own Node for `jsEnv`, so a
plain `.jsPlatform` test suite needs nothing here. Ask for it when the version matters, which is narrower than "the
build has JS in it": a `jsEnv` requiring a specific Node, or a step running `npm ci` for a bundler.

The version is a `NodeVersion` newtype, so every form `setup-node` accepts (`22`, `22.11.0`, `latest`, `lts/jod`,
`lts/*`) is checked while your `build.sbt` compiles, and a value that would break the YAML is a compile error rather
than a workflow GitHub rejects.

Per-capability rather than build-wide, which is the difference from `zipxJavaVersion`: a Node toolchain belongs to one
test suite, so asking for it must not put a Node tool on every publish job in the build. The version is an input on
`zipx-sbt-setup` (after checkout), so a `jsEnv` and an `npm ci` both see it before any cache restore or `extraSteps`.
""",
      exampleValue {
        DocsRender.job("test-schema")(Capability.testGraph.withNodeVersion(NodeVersion("22")))
      }.assert(yaml =>
        assertTrue(
          yaml.contains("uses: ./.github/actions/zipx-sbt-setup"),
          yaml.contains("node-version: \"22\""),
          !yaml.contains("actions/setup-node@"),
        )
      ),
      md"""
Without `withNodeVersion` the same job passes an empty `node-version` input (the composite skips setup-node), so
adopting this changes only the capability that asked:
""",
      exampleValue {
        DocsRender.job("test-schema")(Capability.testGraph)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("uses: ./.github/actions/zipx-sbt-setup"),
          yaml.contains("node-version: \"\""),
          !yaml.contains("actions/setup-node@"),
        )
      ),
    ),
    section("Leftover pin YAML")(
      md"""
Nothing under `.github/zipx/` is an editable source. If a committed `.github/zipx/action-pins.yml` is still present
(old consumer or this repo before the catalog), generate fails with the `Action(...)` vals to paste. No silent dual
source.

```
zipx: .github/zipx/action-pins.yml is leftover input. Action pins live in project/ZipxVersions.scala. Delete …
  val checkout = Action("actions/checkout", "v7.0.1", sha = "…")
Then sbt reload and sbt zipxWorkflowGenerate.
```
""",
      exampleValue {
        ZipxCatalog.leftoverPinFileError(ActionPinFile.DefaultPath, ActionPins.Defaults)
      }.assert(err =>
        assertTrue(
          err.contains("Action(\"actions/checkout\""),
          err.contains("zipxWorkflowGenerate"),
          err.contains(ActionPinFile.DefaultPath),
        )
      ),
    ),
    section("Friction ladder")(
      md"""
| Goal | What to do |
|---|---|
| Stay on zipx release defaults | No Action vals; upgrade `sbt-zipx` when we bump pins |
| Track actions | `Action` vals in ZipxVersions; scheduled `zipx-version-updates.yml` (or `sbt "zipxActionUpdate yes"`; reload; generate) |
| One-off exotic pin | `zipxActions := ActionPins.Defaults.copy(...)` in `build.sbt` |
"""
    ),
    section("build.sbt escape hatch")(
      md"""
Prefer catalog rows for ongoing SHA tracking. Use `zipxActions` only for temporary or exotic overrides:

```scala
zipxActions := ActionPins.Defaults.copy(
  setupSbt = ActionRef("sbt/setup-sbt@d059c39de700f4cc5cb64f9f56577315e44a984e"),
)
```

The `ActionRef(...)` wrapper is not ceremony: it is validated while your `build.sbt` compiles, so
`ActionRef("sbt/setup-sbt")` is a compile error naming the missing `@ref` rather than an unpinned action in `ci.yml`.
For a ref your build computes rather than writes out, `ActionRef.make(...)` returns an `Either` instead.

An explicit `zipxActions` that differs from `ActionPins.Defaults` **wins over** catalog rows.
"""
    ),
    section("How jar defaults stay honest")(
      md"""
In the zipx repository, `Action` vals in `project/ZipxVersions.scala` are the editable source. At compile time,
`resourceGenerators` **renders** `zipx/action-pins.yml` into the `zipx-core` jar from those rows (overlay onto
bootstrap so a missing field still has a pin). That YAML lives in the jar / `target/`, not as something you commit
and edit. `ActionPins.Defaults` loads the classpath resource at runtime.

Release dogfood: the scheduled companion applies `zipxActionUpdate yes` and `zipxCatalogGenerate`. Locally: `sbt zipxActionUpdate yes` →
`reload` → `zipxCatalogGenerate` (and `zipxWorkflowGenerate` if `ci.yml` must move) → compile/publish. A zipx release is how consumers on jar defaults move.

A `github-actions` Dependabot ecosystem is leftover, not the ladder.
"""
    ),
    section("Settings and tasks")(
      md"""
| Setting / task | Role |
|---|---|
| `zipxActionRows` | collected `Action` vals (from `MyVersions.settings`) |
| `zipxActionsPath` | legacy path we **refuse** when the file is still on disk |
| `zipxActions` | explicit `ActionPins` override (escape hatch) |
| `zipxActionUpdate` | GitHub releases + SHA peel + OSV; rewrite constructors after `yes` |
| `zipxCatalogGenerate` | write composites, `plugins.sbt`, `zipx-ci.env` (not workflow YAML) |
| `zipxWorkflowGenerate` / `zipxWorkflowCheck` | write / verify `ci.yml` |

Pinned actions today: `actions/checkout`, `actions/setup-java`, `sbt/setup-sbt`, `actions/setup-node`,
`actions/cache`, `actions/upload-artifact`, `actions/download-artifact`, plus ZipxAws extras
`aws-actions/configure-aws-credentials` and `aws-actions/amazon-ecr-login`. Anything else your steps use is an extra
`Action` val.

Pins that are not GitHub Actions (CDN + sha256, tarball tags, vendor files) are **Pin feeds**, a different machine.
See **Pin feeds** and **Dependency updates**.
"""
    ),
  )
end ActionPinsDoc
