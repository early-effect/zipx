package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.EnvValue
import zio.test.*

/** Why the build should own CI topology. */
object Overview extends DocSpecSuite:

  def doc = page("Overview")(
    md"""
**zipx** is an [sbt 2](https://www.scala-sbt.org/) plugin (Scala 3) that generates a fast,
concurrent, dependency-ordered GitHub Actions workflow from your real `dependsOn` graph.

The common failure mode is a hand-written `.github/workflows/*.yml` that re-declares the
module set, edges, and per-module recipes — then drifts from `build.sbt`. zipx makes the
build the single source of truth: the graph *is* the CI topology.
""",
    section("What it derives")(
      md"""
From the loaded sbt build, zipx emits:

- one job per module, wired by `needs` from real `dependsOn` edges
- dependency-ordered library publish (upstream before downstream), gated on release tags
- affected-only Verify jobs on pull requests
- commit-stable caching keyed by a version epoch (pairs with `sbt-dynver-ci`)
- pluggable **capabilities** (test, publish, docker, deploy, or anything you invent in Scala)
"""
    ),
    section("Typed secrets")(
      md"""
Secret *references* are first-class Scala. zipx never stores secret values — only names
that render to GitHub Actions expressions:
""",
      exampleValue {
        (
          EnvValue.secret("PGP_PASSPHRASE").render,
          EnvValue.plain("us-west-2").render,
          EnvValue.env("DEPLOY_ROLE").render,
        )
      }.assert { case (secret, plain, fromEnv) =>
        assertTrue(
          secret == "${{ secrets.PGP_PASSPHRASE }}",
          plain == "us-west-2",
          fromEnv == "${{ env.DEPLOY_ROLE }}",
        )
      },
    ),
  )
end Overview
