package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.docs.DocsFixtures.*
import zio.test.*

/** Built-in capabilities and how they compose. */
object Capabilities extends DocSpecSuite:

  def doc = page("Capabilities")(
    md"""
A **capability** is a pipeline stage. Built-ins default to **Aggregate**; Graph/Layer variants are explicit
constructors. Test, publish, and docker are built-in; you can add your own (see **Custom capabilities**).
""",
    section("Built-ins")(
      md"""
| Capability | Default mode | Runs | Participates | Phase | Gate |
|---|---|---|---|---|---|
| **test** | Aggregate (Once) | root `zipxTestTask` | whole build (`.aggregate`) | Verify | always |
| **publish** | Aggregate | `+?<module>/<publishTask>` (joined) | modules that publish | Publish | release tag |
| **docker** | Aggregate | `<module>/Docker/publish` (joined) | `DockerPlugin` modules | Publish | release tag |

Use `testGraph` / `publishGraph` / `dockerGraph` for one-job-per-module. Use `*Layers` for wave scheduling. Use
`testJoined` if Aggregate must join `<module>/<testTask>` instead of a root task.
"""
    ),
    section("Phases and replace-by-name")(
      md"""
Capabilities run **Verify → Publish → Deploy**. A capability can depend on another via `needsCapabilities`.

`zipxCapabilities += ...` merges with built-ins; the **same `name` replaces** a built-in (e.g. turn Aggregate docker
into a multi-registry Graph capability).
""",
      exampleValue {
        val pub = Capability.publish.copy(env =
          Map(
            "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
            "SONATYPE_USERNAME" -> EnvValue.secret("SONATYPE_USERNAME"),
          )
        )
        val job = Planner.plan(libGraph, List(pub), config).jobs("publish")
        (job.env.get("PGP_PASSPHRASE"), job.env.get("SONATYPE_USERNAME"))
      }.assert { case (pgp, user) =>
        assertTrue(
          pgp.contains("${{ secrets.PGP_PASSPHRASE }}"),
          user.contains("${{ secrets.SONATYPE_USERNAME }}"),
        )
      },
    ),
    section("Verify knobs")(
      md"""
Shared across Aggregate, Layer, and Graph (details on the **Verify** page):

```scala
zipxTestTask    := "testFull"
zipxVerifyClean := VerifyClean.CleanFull
// Aggregate → sbt 'cleanFull; testFull'
// Graph     → sbt 'cleanFull; core/testFull' (per job)
```
"""
    ),
  )
end Capabilities
