package zipx.docs

import neotype.unwrap
import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsRender.yaml
import zio.test.*

/** Pin feeds: topology and policy in zipx, inventory and apply in the build. */
object PinFeeds extends DocSpecSuite:

  private val fakeFeed = PinFeed(
    name = PinFeedName("cdn"),
    inventory = List(PinnedDep("lib-a", "1.2.3", Some(Purl("pkg:npm/lib-a@1.2.3")))),
    classify = VersionStrategy.npm,
    lookup = _ => Right(Some("1.2.4")),
    apply = (_, _) => Right(()),
  )

  def doc = page("Pin feeds")(
    md"""
Skip until you pin something that is not a Maven library and not a GitHub Action: a CDN URL plus a checksum, a
tarball tag, a file you vendor into the repo.

GitHub's Dependabot can bump SHA-pinned Actions in generated workflows. Library and plugin versions live in the
catalog (`Lib` / `Plugin`, bumped with `zipxDepUpdate`). **Pin feeds** are the machine for the rest.

zipx owns topology and Ignore / Report / Update policy. The build owns inventory, version strategy, lookup, and apply.
""",
    section("Why a feed")(
      md"""
A repo that vendors JS bytes has no `package.json`. Dependabot never opens a PR when `lib-a` gets a CVE. A third
one-off bot would repeat the catalog and Dependabot split badly. A feed is the same split: zipx schedules and gates;
the build knows how to name and rewrite the pin.
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
  end
  subgraph f [your feed]
    direction TB
    f1[list the pins]
    f2[lookup latest]
    f3[apply the bump]
    f4[name a PURL]
  end
```

Three questions, split that way. **Outdated** (is there a newer version?) is the feed's lookup plus a `VersionStrategy`
(`npm` or `exact`). **Advisory** (does this version have a CVE?) is a PURL the feed names and OSV zipx queries.
**Apply** is always the feed: zipx never edits a CDN URL itself. Jobs and companion files are the next section.
"""
    ),
    section("What runs where")(
      md"""
```mermaid
flowchart TD
  PR[pull_request] --> Cap["ci.yml job pin-check"]
  Cap --> PrTask["sbt zipxPinCheckPr"]
  Sched[scheduled plus dispatch] --> Comp["zipx-pin-check.yml"]
  Comp --> Check["sbt zipxPinCheck"]
  Push[push to default branch] --> Snap["zipx-pin-snapshot.yml"]
  Snap --> Submit["sbt zipxPinSubmit"]
```

The PR job is a builtin **Once** capability (`Capability.pinCheck`). Cron cannot live on `ci.yml` or it would also run
test and publish on that schedule. Snapshot submit is `contents: write` and must not run on a PR (that pollutes the
Security tab).
"""
    ),
    section("Register a feed, alert-only")(
      md"""
Conservative defaults: `outdated = Ignore`, `advisory = Report`, `submitSnapshot = false`. Do not auto-bump; do fail a
PR that pins a known CVE.

```scala
zipxPinFeeds += PinFeed(
  name = PinFeedName("cdn"),
  inventory = List(PinnedDep("lib-a", "1.2.3", Some(Purl("pkg:npm/lib-a@1.2.3")))),
  classify = VersionStrategy.npm,
  lookup = pin => lookupLatest(pin),   // the feed talks to jsDelivr / npm / tags
  apply = (pin, to) => rewrite(pin, to),
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
When `zipxPinFeeds` is non-empty, some feed has `advisory != Ignore`, and `zipxPinPrGate != Off`, zipx injects
`Capability.pinCheck` the same way it injects test/publish. Same-name replace works: extraSteps, condition,
`needsCapabilities`. Default is **parallel**: pin-check does not `needs` test. To fail-closed even when only `test` is
a required check:

```scala
zipxCapabilities += Capability.test.copy(needsCapabilities = List(Capability.PinCheckName))
```
""",
      exampleValue {
        DocsRender.jobs("pin-check", "test")(Capability.pinCheck(), Capability.test)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("pin-check:"),
          yaml.contains("pull_request"),
          yaml.contains("zipxPinCheckPr"),
          yaml.contains("test:"),
          !yaml.contains("- pin-check"),
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
  Pin[Pinned dep] --> Purl{purl?}
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
`GITHUB_TOKEN`, applies through the feed, and `gh pr create`s `zipx/pin-updates` as `github-actions[bot]`. Alert-only
stays `contents: read` and never opens a PR. The `pin-check` capability never applies.

**Required repo/org setting** (same as Steward, only needed for `Update`): [Allow GitHub Actions to create and
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
    section("Local update with approval")(
      md"""
The usual path is local, then **you** open the PR. Alert-only is the default (`outdated = Ignore`), so the scheduled
job will not rewrite pins for you. See **Dependency updates** for the same loop next to catalog bumps.

```text
sbt zipxPinUpdate           # list, then prompt Apply N pin update(s)? [y/N]
sbt "zipxPinUpdate yes"     # apply every listed bump (scripts)
sbt "zipxPinUpdate dry-run" # list only
```

`zipxPinUpdate` always looks up latest, even when the feed is alert-only. Apply still goes through the feed (version
and hash together). `yes` applies every listed bump. With no terminal, a bare command lists and stops. This is not a
CI job: after apply, commit and open a pull request yourself.
""",
      exampleValue {
        PinEngine
          .outdated(List(fakeFeed))
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
    section("Maven is the versions catalog")(
      md"""
```mermaid
flowchart LR
  subgraph maven [Maven and sbt]
    Cat[ZipxVersions]
    Loc[zipxDepUpdate]
  end
  subgraph other [no Maven coordinate]
    Pf[PinFeed]
  end
```

You own library and plugin versions in `project/ZipxVersions.scala`. Bump them locally with `zipxDepUpdate`, then open
a PR; see **Versions** and **Dependency updates**. A pin feed is only for pins that have no Maven coordinate: a CDN
URL plus a checksum, a tarball tag, a file you vendor into the repo.
"""
    ),
  )
end PinFeeds
