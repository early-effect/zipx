package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.core.EnvValue.secret
import zio.test.*

/** Built-in capabilities and how they compose. */
object Capabilities extends DocSpecSuite:

  def doc = page("Capabilities")(
    md"""
A **capability** is a pipeline stage. Built-ins default to **Aggregate**; Graph/Layer variants are explicit
constructors. Test, publish, and docker are built-in; packs add paved Central / GitHub Packages / docs Pages / AWS
login; you can also invent stages (see **Custom capabilities**).

Capabilities decide *what* runs. Execution mode and **Matrix collapse** decide *how many* Actions jobs appear for that
stage. Bootstrap and AWS login always go through in-repo composites so the workflow YAML stays short (see **Overview**
and **Action pins**).
""",
    section("Built-ins")(
      md"""
| Capability | Default mode | Runs | Participates | Phase | Gate |
|---|---|---|---|---|---|
| **test** | Aggregate (Once) | root `zipxTestTask` | whole build (`.aggregate`) | Verify | always |
| **pin-check** | Once | `zipxPinCheckPr` | when `zipxPinFeeds` warrants it | Verify | `pull_request` |
| **publish** | Aggregate | `+?<module>/<publishTask>` (joined) | modules that publish | Publish | release tag |
| **docker** | Aggregate | `<module>/Docker/publish` (joined) | `DockerPlugin` modules | Publish | release tag |

Use `testGraph` / `publishGraph` / `dockerGraph` for one-job-per-module. Use `*Layers` for wave scheduling. Use
`testJoined` if Aggregate must join `<module>/<testTask>` instead of a root task. Packs (`ZipxCentral.release`,
`ZipxGitHubPackages`, `ZipxDocs.pages`, AWS helpers) replace or extend these by **name**; see **Packs** and **Docker
and deploy**.
"""
    ),
    section("Phases and replace-by-name")(
      md"""
Capabilities run **Verify → Publish → Deploy**. A capability can depend on another via `needsCapabilities`.

```mermaid
flowchart TD
  V[Verify] --> P[Publish]
  P --> D[Deploy]
  V -.-> Aff[Affected · Graph path gate]
  P -.-> Tag[Release tag gate]
  D -.-> Env[Environments · never affected]
  class V,P,D happy
  class Aff,Tag,Env warn
```

Path gating reaches **Graph** capabilities only (`zipxAffectedOnPR` / `zipxAffectedOnPush`; fail open): Verify by
default, Publish under `zipxAffectedPublish`, where the release gate and the affected clause compose. Deploy is
destination-driven and **never** path-affected.

`Gate` today is `Always` | `OnReleaseTag` | `AffectedOnly`. **`AffectedOnly` is rejected at generate time**: affected
gating is derived from phase, scope and the two settings, not from `Gate`, so this would be a silent Always. See
**Affected**.

`zipxCapabilities += ...` merges with built-ins; the **same `name` replaces** a built-in (e.g. turn Aggregate docker
into a multi-registry Graph capability).

```scala
zipxCapabilities += Capability.publish.copy(
  env = Map(
    "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
    "SONATYPE_USERNAME" -> secret"SONATYPE_USERNAME",
  )
)
```
""",
      exampleValue {
        DocsRender.job("publish")(
          Capability.publish.copy(
            env = Map(
              "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
              "SONATYPE_USERNAME" -> EnvValue.secret("SONATYPE_USERNAME"),
            )
          )
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("PGP_PASSPHRASE: ${{ secrets.PGP_PASSPHRASE }}"),
          yaml.contains("SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}"),
        )
      ),
    ),
    section("Verify knobs")(
      md"""
Shared across Aggregate, Layer, and Graph (details on the **Verify** page):

```scala
zipxTestTask    := zipxTasks.of(testFull)
zipxVerifyClean := VerifyClean.CleanFull
// Aggregate → sbt 'cleanFull; testFull'
// Graph     → sbt 'cleanFull; core/testFull' (per job)
```
"""
    ),
  )
end Capabilities
