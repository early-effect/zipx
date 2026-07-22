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
        val wf  = Planner.plan(libGraph, List(ZipxCentral.release), config)
        val job = wf.jobs("publish")
        (job.steps.last.run, job.env.get("SONATYPE_USERNAME"), job.steps.exists(_.name.contains("Import signing key")))
      }.assert { case (run, user, gpg) =>
        assertTrue(
          run.exists(_.contains("publishSigned; sonaRelease")),
          user.contains("${{ secrets.SONATYPE_USERNAME }}"),
          gpg,
        )
      },
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
        val job = Planner
          .plan(libGraph, List(ZipxGitHubPackages.sameRepo(repository = Some("acme/fork"))), config)
          .jobs("github-packages")
        (job.permissions.get("packages"), job.env.get("PUBLISH_GITHUB_PACKAGES"), job.`if`)
      }.assert { case (pkgs, flag, cond) =>
        assertTrue(
          pkgs.contains("write"),
          flag.contains("true"),
          cond.exists(_.contains("acme/fork")),
        )
      },
    ),
    section("ZipxDocs")(
      md"""
```scala
zipxCapabilities += ZipxDocs.pages()
zipxWorkflowDispatch := true  // optional: manual docs-only deploys
```

`ZipxDocs.pages` emits a reusable-workflow job on `v*` tags (`early-effect/.github/.../specular-docs.yml@main`).
No hand-rolled `docs.yml`.
""",
      exampleValue {
        val job = Planner.plan(ModuleGraph(Nil), List(ZipxDocs.pages()), config).jobs("docs")
        (job.uses, job.`with`.get("sbt-project"))
      }.assert { case (uses, project) =>
        assertTrue(
          uses.contains(ZipxDocs.ReusableWorkflow),
          project.contains("docs"),
        )
      },
    ),
  )
end Packs
