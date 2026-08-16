package zipx.docs

import neotype.unwrap
import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zipx.docs.DocDiff.Kind
import zio.test.*

/** Pin feeds: topology and policy in zipx, inventory as catalog Pin vals. */
object PinFeeds extends DocSpecSuite:

  private val fakePin = Pin("cdn", "lib-a", "1.2.3", sha256 = "abc", purl = "pkg:npm/lib-a@1.2.3")

  private val fakeFeed = PinFeed(
    name = PinFeedName("cdn"),
    classify = VersionStrategy.npm,
    lookup = _ => Right(Some(PinCandidate("1.2.4", sha256 = Some("def"), purl = Some(Purl("pkg:npm/lib-a@1.2.4"))))),
  )

  def doc = page("Pin feeds")(
    md"""
Skip until you pin something that is not a Maven library and not a GitHub Action: a CDN URL plus a checksum, a
tarball tag, a file you vendor into the repo.

GitHub Actions live in the catalog as `Action` vals (`zipxActionUpdate`). Library and plugin versions live as `Lib` /
`Plugin`. **Pins live in that same catalog** as `Pin` vals. A pin feed is only lookup and policy (Ignore / Report /
Update), plus optional `materialize` for extra files.

zipx owns topology, OSV, and the catalog rewrite. The feed looks up the next version and checksum.
""",
    section("Why a feed")(
      md"""
A repo that vendors JS bytes has no `package.json`. Dependabot never opens a PR when `lib-a` gets a CVE. A third
one-off bot would repeat the catalog split badly. A feed is the same split: zipx schedules, gates, and rewrites
`Pin(...)` in `project/ZipxVersions.scala`; the feed knows how to talk to jsDelivr / npm / tags.
"""
    ),
    section("Who owns what")(
      md"""
```mermaid
flowchart LR
  subgraph z [zipx]
    direction TB
    z1[schedule and gate]
    z2[Ignore, Report, Update]
    z3[query OSV]
    z4[rewrite Pin constructors]
  end
  subgraph cat [catalog]
    direction TB
    c1["Pin vals in ZipxVersions"]
  end
  subgraph f [your feed]
    direction TB
    f1[lookup latest plus checksum]
    f2[optional materialize]
  end
```

Inventory is the catalog. **Outdated** is the feed's lookup plus a `VersionStrategy` (`npm` or `exact`). **Advisory**
is a PURL on the `Pin` and OSV zipx queries. **Apply** is zipx rewriting the `Pin(...)` constructor (version, sha256,
and purl together). `materialize` is only for extra files, such as vendored JS bytes.
"""
    ),
    section("What runs where")(
      md"""
```mermaid
flowchart TD
  PR[pull_request] --> Cap["ci.yml job advisories"]
  Cap --> PrTask["sbt zipxAdvisoryCheck"]
  Sched[scheduled plus dispatch] --> Comp["zipx-pin-check.yml"]
  Comp --> Check["sbt zipxPinCheck"]
  Push[push to default branch] --> Snap["zipx-pin-snapshot.yml"]
  Snap --> Submit["sbt zipxPinSubmit"]
```

Pin OSV on a PR folds into the builtin **advisories** job (`zipxAdvisoryCheck`), so zipx does not emit two advisory
jobs. Cron cannot live on `ci.yml` or it would also run test and publish on that schedule. Snapshot submit is
`contents: write` and must not run on a PR (that pollutes the
Security tab).
"""
    ),
    section("Catalog Pin vals")(
      md"""
Write `Pin` next to `Lib` / `Plugin`. Keep the canonical constructor so apply can rewrite it:

```scala
// project/ZipxVersions.scala
val preact = Pin("cdn", "preact", "10.26.4", sha256 = "sha256-abc", purl = "pkg:npm/preact@10.26.4")
```

`MyVersions.settings` collects every `Pin` val into `zipxPins`. A `Pin` whose feed name is not in `zipxPinFeeds` fails
generate.
"""
    ),
    section("Register a feed, alert-only")(
      md"""
Conservative defaults: `outdated = Ignore`, `advisory = Report`, `submitSnapshot = false`. Do not auto-bump; do fail a
PR that pins a known CVE. No inventory list. No apply callback.

```scala
zipxPinFeeds += PinFeed(
  name = PinFeedName("cdn"),
  classify = VersionStrategy.npm,
  lookup = pin => lookupLatest(pin),   // Right(Some(PinCandidate(version, sha256, purl)))
)
```
""",
      exampleValue {
        List(
          s"outdated=${fakeFeed.outdated}",
          s"advisory=${fakeFeed.advisory}",
          s"submitSnapshot=${fakeFeed.submitSnapshot}",
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("outdated=Ignore"),
          text.contains("advisory=Report"),
          text.contains("submitSnapshot=false"),
        )
      ),
    ),
    section("PR gate")(
      md"""
When `zipxPinFeeds` is non-empty, some feed has `advisory != Ignore`, and `zipxPinPrGate != Off`, pin OSV runs inside
`zipxAdvisoryCheck` (the **advisories** job). Same-name replace still works if you add `Capability.pinCheck` yourself.
Default is **parallel**: advisories does not `needs` test. To fail-closed even when only `test` is a required check:

```scala
zipxCapabilities += Capability.test.copy(needsCapabilities = List(Capability.AdvisoriesName))
```

`zipxVerify := ZipxVerify.Strict.copy(advisories = VerifyOpt.Skip("reason"))` skips the whole job, including pin OSV.
""",
      exampleValue {
        DocsRender.jobs("advisories", "test")(
          Capability.once(Capability.AdvisoriesName, SbtCommand.unsafeTask("zipxAdvisoryCheck")),
          Capability.test,
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("advisories:"),
          yaml.contains("zipxAdvisoryCheck"),
          yaml.contains("test:"),
          !yaml.contains("- advisories"),
        )
      ),
    ),
    section("PinPrGate")(
      md"""
```mermaid
flowchart TD
  All["All · default · any current pin above min-severity fails the PR"]
  Introduced["Introduced · hotfix · only new ids or version-changed vs the PR base"]
  Off["Off · disable the builtin · scheduled Report still nags"]
```

`Introduced` fetches `ZIPX_PIN_BASE_SHA` and diffs inventory from a worktree at that SHA. `Off` is the interim "we
know, ship anyway" without deleting feeds.
"""
    ),
    section("When OSV does not know the pin")(
      md"""
```mermaid
flowchart TD
  PinRow[Pin val] --> Purl{purl?}
  Purl -->|none| Skip[skipped · not a finding]
  Purl -->|pkg:...| Osv[OSV query]
  Osv -->|empty vulns| Pass[scanned · no known advisories]
  Osv -->|HTTP or parse error| Fail[job fails]
  Osv -->|advisory at or above min-severity| Hit[finding]
```

Empty vulns is **no known advisory**, not "safe." Private packages must stay green. An all-private feed (every pin
`purl = None`) is a successful no-op. Unreachable OSV fails the job: a check that could not run must not go green.
Public-ecosystem PURLs (`pkg:npm/...`, `pkg:maven/...`) are the ones the gate is for.
"""
    ),
    section("Scheduled outdated / Update")(
      md"""
`.github/workflows/zipx-pin-check.yml` is scheduled plus `workflow_dispatch` (default Sunday 00:00 UTC).
`sbt zipxPinCheck` runs lookup + OSV.
When some feed uses `Update`, the companion is `contents: write` and `pull-requests: write`, checks out with
`GITHUB_TOKEN`, rewrites `Pin(...)` in the catalog, runs optional `materialize`, and `gh pr create`s `zipx/pin-updates`
as `github-actions[bot]`. Alert-only stays `contents: read` and never opens a PR. The `pin-check` capability never
applies.

**Required repo/org setting** (only needed for `Update`): [Allow GitHub Actions to create and
approve pull requests](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository#preventing-github-actions-from-creating-or-approving-pull-requests).
""",
      exampleValue {
        PinCheckWorkflow.render(ActionPins.Defaults, "21", "ubuntu-latest", hasUpdate = true).yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("workflow_dispatch"),
          yaml.contains("sbt zipxPinCheck"),
          yaml.contains("zipx/pin-updates") || yaml.contains("gh pr create"),
          yaml.contains("contents: write") || yaml.contains("contents:write"),
          yaml.contains("pull-requests: write") || yaml.contains("pull-requests:write"),
          yaml.indexOf(ActionPins.Defaults.checkout.unwrap) < yaml.indexOf("sbt zipxPinCheck"),
        )
      ),
    ),
    section("What the Update PR looks like")(
      md"""
The PR always touches `project/ZipxVersions.scala`. Version, sha256, and purl move together. `Lib` / `Plugin` rows stay
put. A vendored file appears only if the feed's `materialize` wrote one.

An sbt plugin that ships the feed still sees this catalog hunk (plus that extra file). It does not also bump its own
`addSbtPlugin` line. That split is **Extending Versions**.
""",
      example {
        DocDiff.stack(catalogPinPrDiff, vendorPinPrDiff)
      }.assert(_ =>
        val src   = """val preact = Pin("cdn", "preact", "10.26.4", sha256 = "abc", purl = "pkg:npm/preact@10.26.4")
val zio    = Lib("dev.zio", "zio", "2.1.26")
"""
        val pin   = Pin("cdn", "preact", "10.26.4", sha256 = "abc", purl = "pkg:npm/preact@10.26.4")
        val bumps = List(
          PinBump(pin, BumpKind.Patch, PinCandidate("10.26.5", Some("def"), Some(Purl("pkg:npm/preact@10.26.5"))))
        )
        val text = ZipxCatalog.applyPinBumps(src, bumps).yaml
        assertTrue(
          text.contains("""Pin("cdn", "preact", "10.26.5", sha256 = "def", purl = "pkg:npm/preact@10.26.5")"""),
          text.contains("""Lib("dev.zio", "zio", "2.1.26")"""),
          !text.contains("10.26.4"),
        )
      ),
    ),
    section("Local update with approval")(
      md"""
The usual path is local, then **you** open the PR. Alert-only is the default (`outdated = Ignore`), so the scheduled
job will not rewrite pins for you. See **Dependency updates** for the same loop next to catalog bumps.

```text
sbt zipxPinUpdate           # list, then prompt Apply N pin update(s)? [y/N]
sbt "zipxPinUpdate yes"     # rewrite Pin constructors, then materialize
sbt "zipxPinUpdate dry-run" # list only
```

`zipxPinUpdate` always looks up latest, even when the feed is alert-only. `yes` applies every listed bump. With no
terminal, a bare command lists and stops. After apply, commit and open a pull request yourself (unless the feed is
`Update` and CI already opened `zipx/pin-updates`).
""",
      exampleValue {
        PinEngine
          .outdated(List(fakeFeed), List(fakePin))
          .map(PinEngine.formatBumps)
          .yaml
      }.assert(text =>
        assertTrue(
          text.contains("lib-a"),
          text.contains("1.2.3"),
          text.contains("1.2.4"),
          text.contains("Patch") || text.contains("cdn"),
        )
      ),
    ),
    section("Snapshot submit")(
      md"""
Opt in per feed (`submitSnapshot = true`). Emitted only then, as `.github/workflows/zipx-pin-snapshot.yml` on push to
`zipxPushBranches`. Dependabot security-update auto-PRs stay off for submitted deps: those PRs would bump a version and
leave a sha256 stale.

Snapshot never runs on a PR.
""",
      exampleValue {
        PinSnapshotWorkflow.render(ActionPins.Defaults, "21", "ubuntu-latest", List("main")).yaml
      }.assert(yaml =>
        assertTrue(
          yaml.contains("sbt zipxPinSubmit"),
          yaml.contains("pin-snapshot"),
          !yaml.contains("pull_request"),
          yaml.indexOf(ActionPins.Defaults.checkout.unwrap) < yaml.indexOf("sbt zipxPinSubmit"),
        )
      ),
    ),
    section("The versions catalog")(
      md"""
```mermaid
flowchart LR
  subgraph catalog [project/ZipxVersions.scala]
    Lib
    Plugin
    Pin
    Action
  end
  subgraph bump [local]
    Dep[zipxDepUpdate]
    PinU[zipxPinUpdate]
    ActU[zipxActionUpdate]
  end
  Lib --> Dep
  Plugin --> Dep
  Pin --> PinU
  Action --> ActU
```

`Lib` / `Plugin` / `Pin` / `Action` vals share one catalog file. `zipxDepUpdate` rewrites Maven constructors.
`zipxPinUpdate` rewrites `Pin` constructors. `zipxActionUpdate` rewrites `Action` constructors. A pin feed is only for
pins that have no Maven coordinate: a CDN URL plus a checksum, a tarball tag, a file you vendor into the repo.
"""
    ),
  )

  private def catalogPinPrDiff =
    DocDiff.panel("project/ZipxVersions.scala")(
      DocDiff.line(Kind.Meta, "@@ object MyVersions extends ZipxVersions"),
      DocDiff.line(Kind.Ctx, "  val preact = Pin("),
      DocDiff.line(Kind.Ctx, "    \"cdn\","),
      DocDiff.line(Kind.Ctx, "    \"preact\","),
      DocDiff.line(Kind.Del, "    \"10.26.4\","),
      DocDiff.line(Kind.Add, "    \"10.26.5\","),
      DocDiff.line(Kind.Del, "    sha256 = \"abc\","),
      DocDiff.line(Kind.Add, "    sha256 = \"def\","),
      DocDiff.line(Kind.Del, "    purl = \"pkg:npm/preact@10.26.4\","),
      DocDiff.line(Kind.Add, "    purl = \"pkg:npm/preact@10.26.5\","),
      DocDiff.line(Kind.Ctx, "  )"),
      DocDiff.line(Kind.Ctx, "  val zio    = Lib(\"dev.zio\", \"zio\", \"2.1.26\")"),
    )

  private def vendorPinPrDiff =
    DocDiff.panel("vendor/preact.min.js")(
      DocDiff.line(Kind.Meta, "@@ materialize wrote this file"),
      DocDiff.line(Kind.Del, "/*! preact 10.26.4 */"),
      DocDiff.line(Kind.Add, "/*! preact 10.26.5 */"),
      DocDiff.line(Kind.Ctx, "(function(){ /* vendored bytes */ })();"),
    )
end PinFeeds
