# zipx

[![CI](https://github.com/early-effect/zipx/actions/workflows/ci.yml/badge.svg)](https://github.com/early-effect/zipx/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-earlyeffect.rocks-blue)](https://www.earlyeffect.rocks/zipx/)
[![Maven Central](https://img.shields.io/maven-central/v/rocks.earlyeffect/zipx-sbt_sbt2_3?logo=apachemaven)](https://central.sonatype.com/artifact/rocks.earlyeffect/zipx-sbt_sbt2_3)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**The build describes its own CI.** zipx is an sbt 2.x plugin (Scala 3) that generates a GitHub Actions workflow directly from your sbt build graph — no hand-maintained YAML, no module list to keep in sync, no per-module command strings to copy-paste.

You declare modules and `dependsOn` once in `build.sbt`. zipx introspects that graph and emits a workflow that:

- **defaults to Aggregate mode** (few sbt sessions: `sbt test`, one publish/release job);
- **offers Layer and Graph modes** for dependency-ordered waves or per-module fan-out (affected-only PRs, matrix isolation);
- **caches sbt's build state** with a commit-stable key (local or remote);
- **builds & publishes docker images** via sbt-native-packager when `DockerPlugin` is enabled;
- **deploys to multiple environments** with GitHub Environment approval (targets fan out; modules can batch);
- **extends with custom capabilities** — lint gates, multi-registry pushes, stages you invent in Scala;
- **checks itself in CI**: a committed workflow that drifts from the build fails the build.

## Quick start

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "<version>")
```

```
sbt zipxWorkflowGenerate
git add .github/workflows/ci.yml && git commit -m "ci: generate with zipx"
```

Defaults are Aggregate: one root `test` job and one publish job (plus docker when any module enables `DockerPlugin`). Write bare settings in `build.sbt` (no `ThisBuild /`); e.g. `zipxTestTask := "testFull"` applies to every module and any module can override it.

## Docs

Full guide (Specular):

- [early-effect.github.io/zipx](https://early-effect.github.io/zipx/)
- [earlyeffect.rocks/zipx](https://www.earlyeffect.rocks/zipx/)

What's covered:

- Overview and architecture
- Quick start and self-checking
- **Execution modes** (Aggregate / Layer / Graph)
- Built-in **capabilities** and **custom capabilities** (`once`, `custom`, `zipxTasks`, `cmd`)
- Verify knobs (`zipxTestTask`, `zipxVerifyClean`, affected, skip-after-merge)
- Caching and action pins
- Docker and multi-target deploy
- `ZipxCentral` / `ZipxDocs` packs
- Settings reference and dogfood notes

Runnable reference: [`examples/monorepo`](examples/monorepo). Roadmap: [ROADMAP.md](ROADMAP.md).

## License

Apache-2.0
