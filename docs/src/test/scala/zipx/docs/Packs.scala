package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.github.ZipxGitHubPackages
import zipx.specular.ZipxDocs
import zio.test.*

/** Early-effect paved paths as capabilities. */
object Packs extends DocSpecSuite:

  def doc = page("Packs")(
    md"""
Org paved paths are capabilities (secret *names* only; values stay in GitHub):
""",
    section("ZipxCentral")(
      md"""
```scala
// Aggregate (preferred for libraries / dogfood)
zipxCapabilities += ZipxCentral.release   // GPG import + publishSigned; sonaRelease

// Graph escape hatch
zipxCapabilities ++= Seq(ZipxCentral.publishSigned, ZipxCentral.releaseOnce)
```
""",
      exampleValue {
        DocsRender.job("publish")(ZipxCentral.release)
      }.assert(yaml =>
        assertTrue(
          yaml.contains("publishSigned; sonaRelease"),
          yaml.contains("SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}"),
          yaml.contains("Import signing key"),
        )
      ),
    ),
    section("ZipxGitHubPackages")(
      md"""
```scala
zipxCapabilities ++= Seq(
  ZipxCentral.release,
  ZipxGitHubPackages.sameRepo(repository = Some("acme/my-fork")),
)
// Shared registry PAT: ZipxGitHubPackages.sharedRegistry(tokenSecret = "GH_PACKAGES_TOKEN")
```

Thin CI wiring (`packages: write` + token + `PUBLISH_GITHUB_PACKAGES=true`). **sbt** owns `publishTo` / Credentials.
See **Job conditions** for fork gates and multi-publish recipes.
""",
      exampleValue {
        DocsRender.job("github-packages")(
          ZipxGitHubPackages.sameRepo(repository = Some("acme/fork"))
        )
      }.assert(yaml =>
        assertTrue(
          yaml.contains("packages: write"),
          yaml.contains("PUBLISH_GITHUB_PACKAGES"),
          yaml.contains("acme/fork"),
        )
      ),
    ),
    section("ZipxDocs")(
      md"""
```scala
zipxCapabilities += ZipxDocs.pages()
zipxWorkflowDispatch := true  // Actions → Run workflow (docs without a release tag)

// Layer a fork gate; andCondition keeps the built-in tag|dispatch filter:
zipxCapabilities += ZipxDocs.pages().andCondition(JobCondition.repositoryIs("acme/libs"))
```

`ZipxDocs.pages` calls the org reusable workflow on **`v*` tags or `workflow_dispatch`**. Verify is skipped on
dispatch so a manual run is docs-cheap; publish stays tag-only. No hand-rolled `docs.yml`.
""",
      exampleValue {
        DocsRender.job("docs")(ZipxDocs.pages())(using ModuleGraph(Nil))
      }.assert(yaml =>
        assertTrue(
          yaml.contains(ZipxDocs.ReusableWorkflow),
          yaml.contains("sbt-project: docs"),
          yaml.contains("pages: write"),
          yaml.contains("workflow_dispatch"),
        )
      ),
    ),
  )
end Packs
