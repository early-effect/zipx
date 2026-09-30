package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.docs.DocsFixtures.config
import zipx.docs.DocsRender.yaml
import zio.test.*

/** Snapshots for iteration, releases on purpose. */
object SnapshotsAndReleases extends DocSpecSuite:

  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("models"), publishes = true),
      ModuleNode(ModuleId("coreLib"), dependsOn = List("models"), publishes = true),
      ModuleNode(ModuleId("client"), dependsOn = List("coreLib"), publishes = true),
    )
  )

  private val libs    = ShipGroup("libs", "1.4.2")("models", "coreLib")
  private val client  = Ship("client", "0.3.0")
  private val catalog = ShipIndex.from(List(libs, client))

  private def released(rows: PublishedRow*): PublishedRow => Either[ReleaseError, RowStatus] =
    row => Right(if rows.contains(row) then RowStatus.Released else RowStatus.Unreleased)

  private def run(ref: String, status: PublishedRow => Either[ReleaseError, RowStatus]): String =
    ReleaseRequest
      .fromRef(ref)
      .flatMap(ReleasePlan.plan(_, catalog, graph, status))
      .fold(err => s"$ref -> refused: ${err.message}", p => s"$ref -> ${p.entries.map(_.tag).mkString(", ")}")

  def doc = page("Snapshots and releases")(
    md"""
A row's catalog number is the **next** release. Every build until then is `<row>-SNAPSHOT`, on a laptop and in CI
alike. A release is a deliberate run that publishes catalog numbers.

```scala
// project/ZipxVersions.scala
val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
val client = Ship("client", "0.3.0")

// build.sbt
zipxReleaseWorkflow := Some(ZipxCentral.releases)
```

| Build | `models` | `client` |
|---|---|---|
| any build, `publishLocal` included | `1.4.2-SNAPSHOT` | `0.3.0-SNAPSHOT` |
| a `zipxRelease` session | `1.4.2` | `0.3.0` |

`-SNAPSHOT` is what sbt overwrites on republish, so `publishLocal` after every edit reaches a sibling build. It is also
the same string on every commit, so cache digests hold (see **Caching**).
""",
    section("Release")(
      md"""
`zipx-release.yml` has one job, started two ways:

| Start | Releases |
|---|---|
| a GitHub Release whose tag is `client/v0.3.0` (`v0.3.0` when the catalog has one row) | that row, plus its unreleased in-repo upstream rows |
| Actions → **zipx release** → Run workflow, on the default branch | every row whose number is not on the registry; then creates the tags and GitHub Releases |

Either way it is one sbt session and one registry deployment, however many rows it carries. With a release workflow
set, `ci.yml` has no publish job. The job restores the build cache and never saves one: its jars carry release
numbers, which no PR build would hit.
""",
      exampleValue {
        ReleaseWorkflow.render(ZipxCentral.releases, config).yaml
      }.assert(yml =>
        assertTrue(
          yml.contains("- v*"),
          yml.contains("- \"*/v*\""),
          yml.contains("workflow_dispatch"),
          yml.contains("environment: zipx-release"),
          yml.contains("cache-mode: restore"),
          !yml.contains("cache-mode: save"),
          yml.contains("sbt \"zipxRelease $ZIPX_RELEASE_REF\""),
          yml.contains("gh release create"),
        )
      ),
    ),
    section("What a run releases")(
      md"""
A released POM names its in-repo dependencies at their catalog numbers, so a row always releases with every unreleased
row it depends on. A row that depends on it never rides along.
""",
      exampleValue {
        List(
          run("refs/tags/client/v0.3.0", released()),
          run("refs/tags/client/v0.3.0", released(libs)),
          run("refs/tags/libs/v1.4.2", released()),
          run("refs/heads/main", released(libs)),
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("refs/tags/client/v0.3.0 -> libs/v1.4.2, client/v0.3.0"),
          text.contains("refs/tags/client/v0.3.0 -> client/v0.3.0"),
          text.contains("refs/tags/libs/v1.4.2 -> libs/v1.4.2"),
          text.contains("refs/heads/main -> client/v0.3.0"),
        )
      ),
    ),
    section("Refusals")(
      md"""
Nothing uploads until the plan is sound. Each refusal says what to do next.
""",
      exampleValue {
        List(
          run("refs/tags/client/v0.3.1", released()),
          run("refs/tags/v0.3.0", released()),
          run("refs/tags/client/v0.3.0", released(client)),
          run("refs/heads/main", released(libs, client)),
          run("refs/heads/main", row => Left(ReleaseError.RegistryUnreachable(row, "connect timed out"))),
        ).mkString("\n")
      }.assert(text =>
        assertTrue(
          text.contains("""tag client/v0.3.1 does not match Ship("client") 0.3.0; tag client/v0.3.0"""),
          text.contains("tag v0.3.0 names no catalog row; this catalog releases libs/v1.4.2, client/v0.3.0"),
          text.contains("""Ship("client") 0.3.0 is already released; move the row to release again"""),
          text.contains("every row's catalog number is already released"),
          text.contains("""cannot tell whether ShipGroup("libs") 1.4.2 is released: connect timed out"""),
        )
      ),
    ),
    section("Setup")(
      md"""
1. `zipxReleaseWorkflow := Some(ZipxCentral.releases)`, then `sbt zipxWorkflowGenerate`.
2. Create the GitHub Environment `zipx-release` (Settings → Environments). Add required reviewers there if a release
   should wait for a human; scope the signing secrets to it if you want them nowhere else.
3. Release: draft a GitHub Release with the row's tag, or run **zipx release** from the Actions tab.

A tag pushed on a commit the default branch has not reached is refused before sbt starts. A dispatch from any other
branch does not run.
"""
    ),
  )
end SnapshotsAndReleases
