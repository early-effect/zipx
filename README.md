# zipx

[![CI](https://github.com/early-effect/zipx/actions/workflows/ci.yml/badge.svg)](https://github.com/early-effect/zipx/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-earlyeffect.rocks-blue)](https://www.earlyeffect.rocks/zipx/)
[![Maven Central](https://img.shields.io/maven-central/v/rocks.earlyeffect/sbt-zipx_sbt2_3?logo=apachemaven)](https://central.sonatype.com/artifact/rocks.earlyeffect/sbt-zipx_sbt2_3)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**The build describes its own CI.** zipx is an sbt 2.x plugin (Scala 3). You keep writing `build.sbt`. zipx generates a GitHub Actions workflow from that graph, so you do not maintain a second copy in YAML: no hand-maintained job lists, no module names to copy-paste.

You declare modules and `dependsOn` once. zipx emits a workflow that:

- **defaults to Aggregate mode** (few sbt sessions: root `testFull`, parallel fmt / workflow-check / advisories, one publish/release job);
- **offers Layer and Graph modes** for dependency-ordered waves or per-module fan-out (affected-only PRs, matrix isolation);
- **caches sbt's build state** with a commit-stable key (local or remote);
- **builds & publishes docker images** via sbt-native-packager when `DockerPlugin` is enabled;
- **deploys to multiple environments** with GitHub Environment approval (targets fan out; modules can batch);
- **extends with custom capabilities**: lint gates, multi-registry pushes, stages you invent in Scala;
- **checks itself in CI**: a committed workflow that drifts from the build fails the build;
- **pins GitHub Actions to commit SHAs**, with catalog `Action` vals so you can bump them without waiting on a zipx release.

## Quick start

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "<version>")
```

The artifact is `sbt-zipx`, published for sbt 2 / Scala 3 as [`sbt-zipx_sbt2_3`](https://central.sonatype.com/artifact/rocks.earlyeffect/sbt-zipx_sbt2_3). An earlier `rocks.earlyeffect:zipx-sbt` coordinate exists on Central, abandoned at 0.0.6; it is not this plugin and gets no updates.

```
sbt zipxWorkflowGenerate
git add .github/workflows/ci.yml && git commit -m "ci: generate with zipx"
```

Defaults are Aggregate: parallel Verify jobs (`testFull`, `fmt`, `workflow-check`, `advisories`) and one publish job (plus docker when any module enables `DockerPlugin`). Write bare settings in `build.sbt` (no `ThisBuild /`); e.g. `zipxTestTask := zipxTasks.of(testFull)` is the plugin default and any module can override it.

### Action pins

Generated `uses:` lines are SHA-pinned (with `# vX.Y.Z` comments). Jar defaults ship in the plugin. To track upstream action releases ahead of a zipx upgrade, add `Action` vals to `project/ZipxVersions.scala` and run `sbt "zipxActionUpdate yes"`, then `reload` and `zipxWorkflowGenerate`. A leftover `.github/zipx/action-pins.yml` fails generate (paste the constructors). A `github-actions` Dependabot ecosystem is not needed. Full guide: **Action pins** on the docs site.

### Keeping versions current

Extend `ZipxVersions` in `project/ZipxVersions.scala`, then drop `MyVersions.settings` in `build.sbt`. Every `Lib` /
`Plugin` / `Action` val is a catalog row; you do not list them again. Each module picks a group. Typed values, not regex
over the build. Other plugins extend the same trait. Full guide: **Versions** on the docs site.

```scala
import zipx.*
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val zio                 = Lib("dev.zio", "zio", "2.1.26")
  val slf4j               = Lib("org.slf4j", "slf4j-simple", "2.0.18").java
  def libraries           = library(zio)
  def service             = library(zio, slf4j)

MyVersions.settings
lazy val lib     = project.settings(MyVersions.libraries)
lazy val service = project.settings(MyVersions.service)
```

Bump locally, then open a PR:

```
sbt zipxDepUpdate
sbt zipxActionUpdate
```

Pins that are not Maven (CDN URL plus checksum, tarballs, vendored files) use `sbt zipxPinUpdate`. Full loop:
**Dependency updates** on the docs site.

## Docs

Full guide (Specular):

- [early-effect.github.io/zipx](https://early-effect.github.io/zipx/)
- [earlyeffect.rocks/zipx](https://www.earlyeffect.rocks/zipx/)

What's covered:

- Overview, **Why zipx**, and **From Bazel** (strategy vs second graphs / acceleration layers)
- Quick start, **Versions** (`ZipxVersions` catalog), **Extending Versions** (plugins that sit on zipx), and self-checking
- **Execution modes** (Aggregate / Layer / Graph)
- Built-in **capabilities**, **custom capabilities**, and **composing sbt commands** (`zipxTasks`, `thenOnce`, `ZipxCentral.release`)
- Verify knobs (`zipxTestTask`, `zipxVerifyClean`, affected, skip-after-merge)
- Caching and **Remote cache for teams** (CI-hydrated digests; live proof in Aggregate Verify via Testcontainers)
- **Action pins** (catalog `Action` vals, `zipxActionUpdate`, jar defaults)
- **Dependency updates** (scheduled `zipx-version-updates.yml` opens the catalog PR; local `zipxDepUpdate` / `zipxActionUpdate` / `zipxPinUpdate`) and **Pin feeds**
- Docker and multi-target deploy
- `ZipxCentral` / `ZipxDocs` packs
- Settings reference and dogfood notes

Runnable reference: [`examples/monorepo`](examples/monorepo). Roadmap: [ROADMAP.md](ROADMAP.md).

Live remote-cache proof (Docker required): part of `sbt core/testFull` / Aggregate Verify (Testcontainers + sbt fixture image).

## License

Apache-2.0

## Development

```bash
./scripts/install-git-hooks  # once per clone: pre-commit runs scalafmtCheckAll
```
