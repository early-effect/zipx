package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.workflow.ActionRef
import zio.test.*

/** GitHub Action SHA pins, the pin file, Dependabot, and sync. */
object ActionPinsDoc extends DocSpecSuite:

  def doc = page("Action pins")(
    md"""
Skip this page at first. zipx already pins third-party GitHub Actions to **full commit SHAs** (not floating `@v4`
tags) in the generated workflow, so a moved tag cannot change what CI runs. Version labels appear as trailing comments:

```yaml
- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

Jar defaults ship in the plugin. Come back when you want to bump those Actions **without** waiting for a zipx release:
a small pin file plus optional Dependabot, the same path this repository uses.

`zipxWorkflowGenerate` also writes in-repo **composite actions** under `.github/actions/zipx-*` (JDK/sbt/cache
bootstrap, AWS login when you use those packs). Commit them alongside `ci.yml`; `zipxWorkflowCheck` drifts both.
Checkout is a prior workflow step so GitHub can resolve local `uses: ./…` from the workspace.
""",
    section("Resolve order")(
      md"""
When generating a workflow, zipx picks pins in this order:

```mermaid
flowchart TD
  Start[Generate workflow] --> Explicit{zipxActions != Defaults?}
  Explicit -->|yes| UseExplicit[Use zipxActions]
  Explicit -->|no| File{pin file exists?}
  File -->|yes| UseFile[Use action-pins.yml]
  File -->|no| UseJar[ActionPins.Defaults]
  class Start,Explicit,File warn
  class UseExplicit warn
  class UseFile,UseJar happy
```

1. **`zipxActions`**: only when set *away from* `ActionPins.Defaults` (one-off / escape hatch in `build.sbt`)
2. **Pin file**: if `zipxActionsPath` points at an existing file (default `.github/zipx/action-pins.yml`)
3. **`ActionPins.Defaults`**: embedded classpath resource from the zipx release you depend on

Empty `zipxActionsPath := ""` disables file loading so you always use jar defaults (or an explicit `zipxActions`).
"""
    ),
    section("The pin file")(
      md"""
**Path:** `.github/zipx/action-pins.yml` (configurable via `zipxActionsPath`).

This is **not** a workflow. It lives outside `.github/workflows/` and is named so it is not confused with
`ci.yml`. Flat keys match `ActionPins` fields; values are `owner/action@sha` with an optional `# vX.Y.Z` comment:

```yaml
# zipx GitHub Action SHA pins (not a workflow).
checkout: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
setupJava: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
setupSbt: sbt/setup-sbt@bfea3c5f48abd221b04a6df4798aa5eb8b6a2baf # v1.5.6
cache: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
uploadArtifact: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
downloadArtifact: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
```

After editing the pin file (or syncing from Dependabot):

```
sbt zipxWorkflowGenerate
git add .github/zipx/action-pins.yml .github/workflows/ci.yml .github/actions/
```

If `zipxDependabotSync := true`, also commit `.github/workflows/zipx-action-pins-sync.yml` when it changes.
""",
      exampleValue {
        val text =
          """checkout: actions/checkout@abc123 # v9.0.0
            |setupSbt: sbt/setup-sbt@def456 # v1.9.9
            |""".stripMargin
        ActionPinFile.parse(text).map(ActionPinFile.render)
      }.assert(yaml =>
        assertTrue(
          yaml.exists(_.contains("checkout: actions/checkout@abc123 # v9.0.0")),
          yaml.exists(_.contains("setupSbt: sbt/setup-sbt@def456 # v1.9.9")),
        )
      ),
    ),
    section("Pins for actions zipx does not emit (`extra:`)")(
      md"""
The flat keys above are the actions **zipx itself** writes into a workflow, one per `ActionPins` field. An action *you*
reach through `extraSteps`, a custom capability, or a published pack has no field, and waiting for a zipx release to
get one would be absurd. Those go in an indented `extra:` block, keyed by whatever name you like:

```yaml
checkout: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
extra:
  configure-aws-credentials: aws-actions/configure-aws-credentials@b47578312673ae6fa5b5096b330d9fbac3d13d67 # v6.0.0
```

In Scala, `withExtra` puts one there and `extraRef` reads it back, which is how a pack writes a step whose pin the
consumer can bump without a zipx upgrade:

```scala
zipxActions := ActionPins.Defaults.withExtra(
  "configure-aws-credentials",
  ActionRef("aws-actions/configure-aws-credentials@b47578312673ae6fa5b5096b330d9fbac3d13d67"),
  Some("v6.0.0"),
)
```

**A typed field and an extra pin are not interchangeable, and the difference is what gets checked:**

| | typed field (`checkout:`) | `extra:` pin |
|---|---|---|
| For | an action zipx emits | an action your steps or a pack emit |
| Key | one of a closed set; a typo is refused | any identifier you choose |
| Ref must be pinned | yes | yes |
| Ref must name the *right* action | yes, via the field's known prefix | **no**, there is no prefix to compare against |
| Adding one | a zipx release | a line in your pin file |

The row that matters is the fourth. `checkout: evil/malware@<sha>` is refused because `checkout:` is known to mean
`actions/checkout`; `myaction: evil/malware@<sha>` cannot be, because `myaction` means whatever you decided. That
weaker guarantee is the price of not needing a release, so prefer a typed field for anything zipx emits.

`zipxActionsPull` bumps an extra pin whose **key already exists**, matching on the `owner/action` part of the ref. It
never invents a key for an action it has not seen before: pinning a new action is a deliberate act by whoever wrote the
step, and a guessed key would be one more thing to rename later.
""",
      exampleValue {
        val text =
          s"""checkout: actions/checkout@abc123 # v9.0.0
             |extra:
             |  configure-aws-credentials: aws-actions/configure-aws-credentials@def456 # v6.0.0
             |""".stripMargin
        ActionPinFile.parse(text)
      }.assert(pins =>
        assertTrue(
          pins.map(_.extraRef("configure-aws-credentials")) ==
            Right(Some(ActionRef("aws-actions/configure-aws-credentials@def456"))),
          pins.exists(_.extraVersion("configure-aws-credentials").contains("v6.0.0")),
          // The same file re-rendered is the same file: extra pins sort by key, so a Map's iteration order cannot
          // produce a spurious diff on the next generate.
          pins.map(ActionPinFile.render).flatMap(ActionPinFile.parse) == pins,
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
    section("A line zipx cannot read fails the build")(
      md"""
`ActionPinFile.parse` returns an `Either`, and `zipxWorkflowGenerate` turns a `Left` into a build error naming the
line. A pin file that is *present* must be wholly readable; only an *absent* file falls back to jar defaults.

That matters because the fallback used to be silent. A key with a typo in it (`setup-jav:`) simply did not match, so
the field quietly reverted to the SHA baked into the zipx jar, undoing a pin the repo had deliberately held back. Four
things are refused, and the error names the file, the line number, and the line:

| The line | Why |
|---|---|
| `setup-jav: actions/setup-java@<sha>` | not a pin, a comment, or blank |
| `setupJava2: actions/setup-java@<sha>` | `setupJava2` is not an `ActionPins` field (the message lists the ones that are) |
| `checkout: actions/checkout` | no `@ref`: an unpinned action is the risk this file exists to remove |
| `checkout: evil/malware@<sha>` | a valid ref, but `checkout:` may only name `actions/checkout` |

The last is the one a shape check alone misses, and the reason a `uses:` value is an
[[zipx.workflow.ActionRef]] everywhere rather than a `String`: the pin file is where an action ref becomes typed, and
after that no step, job or reusable-workflow call can carry an unvalidated one.
""",
      exampleValue {
        ActionPinFile.parse("checkout: actions/checkout\n")
      }.assert(result =>
        assertTrue(
          result.isLeft,
          result.swap.exists(_.contains(".github/zipx/action-pins.yml:1:")),
          result.swap.exists(_.contains("@ref")),
        )
      ),
    ),
    section("Friction ladder")(
      md"""
| Goal | What to do |
|---|---|
| Stay on zipx release defaults | No pin file; upgrade `sbt-zipx` when we bump pins |
| Track actions with low friction | Commit `.github/zipx/action-pins.yml`; enable Dependabot; run `sbt zipxActionsPull` on bump PRs |
| Fully hands-off | `zipxDependabotSync := true` (generates the sync workflow) + Dependabot |
| One-off exotic pin | `zipxActions := ActionPins.Defaults.copy(...)` in `build.sbt` |
"""
    ),
    section("Dependabot")(
      md"""
Dependabot's `github-actions` ecosystem only sees `uses:` in workflow / composite-action YAML, not Scala and not the
pin file directly. That is fine: it bumps SHAs (and `# vX.Y.Z` comments) in the generated workflow. You then **pull**
those bumps back into the pin file so `zipxWorkflowCheck` stays green.

Minimal Dependabot config:

```yaml
# .github/dependabot.yml
version: 2
updates:
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
    groups:
      github-actions:
        patterns:
          - "*"
```

**Manual sync on a Dependabot PR:**

```
sbt zipxActionsPull
# updates the pin file from ci.yml and .github/actions/**/action.yml, then regenerates
git add .github/zipx/action-pins.yml .github/workflows/ .github/actions/
```

`zipxActionsPull` refuses to run if `zipxActionsPath` is empty (nowhere to write).
"""
    ),
    section("Automatic sync workflow")(
      md"""
```scala
zipxDependabotSync := true
```

Then `zipxWorkflowGenerate` / `zipxWorkflowCheck` also maintain
`.github/workflows/zipx-action-pins-sync.yml` (separate from `ci.yml`). On Dependabot PRs that workflow:

1. checks out the PR branch
2. runs `sbt zipxActionsPull`
3. commits and pushes pin-file + workflow updates when anything changed

zipx itself dogfoods this (`zipxDependabotSync := true` in the root build).
"""
    ),
    section("build.sbt escape hatch")(
      md"""
Prefer the pin file for ongoing SHA tracking. Use `zipxActions` only for temporary or exotic overrides:

```scala
zipxActions := ActionPins.Defaults.copy(
  setupSbt = ActionRef("sbt/setup-sbt@d059c39de700f4cc5cb64f9f56577315e44a984e"),
)
```

The `ActionRef(...)` wrapper is not ceremony: it is validated while your `build.sbt` compiles, so
`ActionRef("sbt/setup-sbt")` is a compile error naming the missing `@ref` rather than an unpinned action in `ci.yml`.
For a ref your build computes rather than writes out, `ActionRef.make(...)` returns an `Either` instead.

An explicit `zipxActions` that differs from `ActionPins.Defaults` **wins over** the pin file. Setting
`zipxActions := ActionPins.Defaults` (or leaving the default) lets the pin file take effect when present.
"""
    ),
    section("How jar defaults stay honest")(
      md"""
In the zipx repository, `.github/zipx/action-pins.yml` is the editable source of truth. At compile time,
`resourceGenerators` copies it onto the `zipx-core` classpath as `zipx/action-pins.yml`.
`ActionPins.Defaults` loads that resource, so a published zipx release ships the same pins this repo dogfoods.

Consumer repos without a pin file get those jar defaults until they add their own file or upgrade zipx.
"""
    ),
    section("Settings and tasks")(
      md"""
| Setting / task | Role |
|---|---|
| `zipxActionsPath` | pin file path (default `.github/zipx/action-pins.yml`; `""` disables) |
| `zipxActions` | explicit `ActionPins` override (escape hatch); `.withExtra(key, ref)` for an action zipx does not emit |
| `zipxDependabotSync` | also generate `zipx-action-pins-sync.yml` |
| `zipxActionsPull` | workflow `uses:` → pin file → regenerate |
| `zipxWorkflowGenerate` / `zipxWorkflowCheck` | write / verify `ci.yml` (and sync workflow when enabled) |

Pinned actions today: `actions/checkout`, `actions/setup-java`, `sbt/setup-sbt`, `actions/setup-node`,
`actions/cache`, `actions/upload-artifact`, `actions/download-artifact`, `scala-steward-org/scala-steward-action`.
Anything else your steps use goes in the `extra:` block.

Pins that are not GitHub Actions (CDN + sha256, tarball tags, vendor files) are **Pin feeds**, a different machine.
See **Pin feeds** and **Dependency updates**.
"""
    ),
  )
end ActionPinsDoc
