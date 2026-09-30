# zipx roadmap

Intent as the code a build author writes. Live behavior: the Specular docs. What shipped: git.

## Thesis

```scala
// The build is the only source of truth; zipx derives CI from it.
MyVersions.settings                        // typed catalog: what we use (Lib, Plugin, Action, Pin), what we ship (Ship)
zipxCapabilities ++= Seq(Capability.test, ZipxCentral.release, ZipxDocs.pages())
```

```text
sbt zipxWorkflowGenerate    # .github/** is output: commit it
sbt zipxWorkflowCheck       # drift gate
```

## Ethos

```scala
// Topology in zipx, semantics in the build.
Capability.publish.runningEachCross(publishSigned)   // the build says what; zipx says when, where, in what order

// Refuse rather than drop.
// [error] zipx: tag v0.15.2 does not match ShipGroup("zipx") 0.15.1

// Unrepresentable > compile-time > typed error. Never strings; never throw below the sbt boundary.
Ship("core", "1.4.2-SNAPSHOT")                      // does not compile: a row is the next release
ReleaseError.SnapshotPinned(rows)                   // failures are enum cases the sbt boundary renders once

// Secrets by name, never value.
secret"SONATYPE_PASSWORD"

// A version is identity, order, and mutability. Iterate on -SNAPSHOT; release on purpose.
version == s"$row-SNAPSHOT"                         // every build except a release run

// The human writes the number; CI checks it and never commits the catalog.
sbt "zipxModverBump zipx minor"                     // modver-check floors it with MiMa against the last release

// Caches key on content, so versions stay commit-stable and digests hold.
// Deterministic YAML. sbt 2 only. testFull proves; test (testQuick) does not.
```

## Now: snapshots and releases

```scala
// project/ZipxVersions.scala: a row is the NEXT release
val zipx = ShipGroup("zipx", "0.15.0")("shell", "workflow", "core", "syntax", "cli", "central", "aws", "plugin")

// build.sbt: ci.yml builds and publishes snapshots; zipx-release.yml releases
zipxCapabilities ++= Seq(Capability.test, ZipxCentral.snapshots, ZipxDocs.pages())
zipxReleaseWorkflow := Some(ZipxCentral.releases)
```

```text
any build, laptop or CI        0.15.0-SNAPSHOT            publishLocal overwrites; digests stable across commits
merge to main                  0.15.0-SNAPSHOT        ->  Central snapshots
PR labeled for snapshots       0.15.0-pr42-SNAPSHOT   ->  Central snapshots
GitHub Release v0.15.0 | Run   0.15.0                 ->  Central (one bundle), tags, docs
```

```scala
// downstream, before the release exists
val zipxCore = Lib("rocks.earlyeffect", "zipx-core", "0.15.0-SNAPSHOT")
// resolvers + freshness: automatic while pinned
// zipxRelease:           refuses while pinned
// catalog update:        rewrites to 0.15.0 once it is released
```

```text
PR changes core after 0.15.0 shipped   modver-check: [error] ShipGroup("zipx") 0.15.0 is released; bump to >= 0.15.1
PR breaks binary compat                modver-check: [error] ShipGroup("zipx") 0.15.1 < 0.16.0 (MiMa vs 0.15.0)
Release tag v0.15.2, catalog 0.15.1    zipx-release: [error] tag v0.15.2 does not match ShipGroup("zipx") 0.15.1
```

| # | Branch | Intent | Done |
|---|---|---|---|
| 0 | `snapshots/roadmap` | this file | [x] |
| 1 | `snapshots/retire-ci` | `-SNAPSHOT` is the only unreleased form | [x] |
| 2 | `snapshots/typed-versions` | `Ship(id, ReleaseVersion)`: a snapshot row does not compile | [x] |
| 3 | `snapshots/release-workflow` | `zipx-release.yml`: tag == catalog, or dispatch; one bundle | [x] |
| 4 | `snapshots/version-model` | `version == s"$row-SNAPSHOT"`; merges never release | [x] |
| 5 | `snapshots/mainline-channel` | a merge publishes affected unreleased rows, upload-only | [ ] |
| 6 | `snapshots/consume` | pin a snapshot: resolvers, freshness, guard, promotion | [ ] |
| 7 | `snapshots/pr-channel` | a label publishes `<row>-pr<N>-SNAPSHOT` from the PR's cache | [ ] |
| 8 | `snapshots/dogfood` | zipx on its own rows and `zipx-release.yml` | [ ] |

Cache invariants every layer keeps, each with a check in the layer that could break it:

```text
C1 commit-stable versions keep hits      C5 PR snapshots reuse the PR's cache
C2 epoch rolls iff a row number moves    C6 release jobs restore, never save
C3 a bump starts warm                    C7 snapshot pins stay fresh; nothing else goes cold
C4 snapshot publish is upload-only       C8 remote cacheVersion stays JDK/OS
```

## Next: org migration

```diff
- addSbtPlugin("rocks.earlyeffect" % "sbt-dynver-ci" % "0.2.3")
+ val lib = ShipGroup("specular", "0.19.0")(/* every published project */)
- zipxCapabilities += ZipxCentral.release           // tag-gated, in ci.yml
+ zipxCapabilities += ZipxCentral.snapshots          // merge -> Central snapshots
+ zipxReleaseWorkflow := Some(ZipxCentral.releases)  // GitHub Release or dispatch -> zipx-release.yml
```

Order: ascent, specular, heddle, then the rest. sbt-dynver-ci archives after the last one; sbt-specular drops
`stripCi`.

## Later

```scala
// M12 remainder: core stops spelling sbt commands as strings
SbtCommand.unsafeTask("coverageReport")      // Coverage, VerifyClean, Docker/publish: built from real keys in the plugin

// One publishTo, derived from the registry (the same block is copied across the org today)
ThisBuild / publishTo := Registry.MavenCentral.publishTo(isSnapshot.value)

// Typed errors outside the snapshot stack
Either[String, Plan]  ->  Either[PlanError, Plan]

// Only if a build asks for it
zipxAffected <capability>
```

## How we prove changes

```text
sbt "scalafmtAll; cleanFull; testFull"     # every PR
sbt plugin/scripted zipxWorkflowCheck       # emission or .github/** changed; feature branch, unique version
sbt docsDev                                 # ~docs/specularPreview
```

Real-repo behavior (cache eviction, approvals, what a PR runs) is proven in
[zipx-ci-lab](https://github.com/early-effect/zipx-ci-lab), with the numbers in the PR. Blast radius per module:
[AGENTS.md](AGENTS.md).
