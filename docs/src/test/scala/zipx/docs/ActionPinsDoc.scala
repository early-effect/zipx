package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zio.test.*

/** GitHub Action SHA pins, the pin file, Dependabot, and sync. */
object ActionPinsDoc extends DocSpecSuite:

  def doc = page("Action pins")(
    md"""
Generated workflows pin third-party GitHub Actions to **full commit SHAs** (not floating `@v4` tags). That keeps CI
reproducible and resistant to tag moves. Version labels appear as trailing comments so humans and Dependabot can still
read which release a SHA corresponds to:

```yaml
- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

zipx ships current defaults in the plugin jar. Consumers who want to track upstream action releases **ahead of a zipx
upgrade** use a small pin file plus optional Dependabot automation — the same path this repository dogfoods.
""",
    section("Resolve order")(
      md"""
When generating a workflow, zipx picks pins in this order:

1. **`zipxActions`** — only when set *away from* `ActionPins.Defaults` (one-off / escape hatch in `build.sbt`)
2. **Pin file** — if `zipxActionsPath` points at an existing file (default `.github/zipx/action-pins.yml`)
3. **`ActionPins.Defaults`** — embedded classpath resource from the zipx release you depend on

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
setupJava: actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95 # v5.6.0
setupSbt: sbt/setup-sbt@6444f4c8111de4b9059c3975def104b03cfaa5f0 # v1.5.2
cache: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
uploadArtifact: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
downloadArtifact: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
```

After editing the pin file (or syncing from Dependabot):

```
sbt zipxWorkflowGenerate
git add .github/zipx/action-pins.yml .github/workflows/ci.yml
```

If `zipxDependabotSync := true`, also commit `.github/workflows/zipx-action-pins-sync.yml` when it changes.
""",
      exampleValue {
        val text =
          """checkout: actions/checkout@abc123 # v9.0.0
            |setupSbt: sbt/setup-sbt@def456 # v1.9.9
            |""".stripMargin
        ActionPinFile.render(ActionPinFile.parse(text))
      }.assert(yaml =>
        assertTrue(
          yaml.contains("checkout: actions/checkout@abc123 # v9.0.0"),
          yaml.contains("setupSbt: sbt/setup-sbt@def456 # v1.9.9"),
        )
      ),
    ),
    section("Friction ladder")(
      md"""
| Goal | What to do |
|---|---|
| Stay on zipx release defaults | No pin file; upgrade `zipx-sbt` when we bump pins |
| Track actions with low friction | Commit `.github/zipx/action-pins.yml`; enable Dependabot; run `sbt zipxActionsPull` on bump PRs |
| Fully hands-off | `zipxDependabotSync := true` (generates the sync workflow) + Dependabot |
| One-off exotic pin | `zipxActions := ActionPins.Defaults.copy(...)` in `build.sbt` |
"""
    ),
    section("Dependabot")(
      md"""
Dependabot's `github-actions` ecosystem only sees `uses:` in workflow / composite-action YAML — not Scala and not the
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
# updates the pin file from ci.yml, then regenerates workflows
git add .github/zipx/action-pins.yml .github/workflows/
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
  setupSbt = "sbt/setup-sbt@6444f4c8111de4b9059c3975def104b03cfaa5f0",
)
```

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
| `zipxActions` | explicit `ActionPins` override (escape hatch) |
| `zipxDependabotSync` | also generate `zipx-action-pins-sync.yml` |
| `zipxActionsPull` | workflow `uses:` → pin file → regenerate |
| `zipxWorkflowGenerate` / `zipxWorkflowCheck` | write / verify `ci.yml` (and sync workflow when enabled) |

Pinned actions today: `actions/checkout`, `actions/setup-java`, `sbt/setup-sbt`, `actions/cache`,
`actions/upload-artifact`, `actions/download-artifact`.
"""
    ),
  )
end ActionPinsDoc
