package zipx.specular

import zipx.core.*

/** Early-effect Specular docs paved path for zipx.
  *
  * Emits a release-gated once-job that calls the org reusable workflow
  * `early-effect/.github/.github/workflows/specular-docs.yml` (build Specular site → GitHub Pages). Same pattern as
  * peers' thin `docs.yml`, expressed as a capability so generated CI owns it.
  *
  * {{{
  * zipxCapabilities += ZipxDocs.pages()
  * zipxWorkflowDispatch := true // optional: manual "Run workflow" for docs-only deploys
  * }}}
  */
object ZipxDocs:

  /** Org reusable workflow that builds `sbt <project>/specularSite` and deploys to GitHub Pages. */
  val ReusableWorkflow: String =
    "early-effect/.github/.github/workflows/specular-docs.yml@main"

  /** Pages permissions required by the reusable workflow caller. */
  val pagesPermissions: Map[String, String] = Map(
    "contents" -> "read",
    "pages"    -> "write",
    "id-token" -> "write",
  )

  /** Deploy Specular docs to GitHub Pages on `v*` tags (and on `workflow_dispatch` when that trigger is enabled).
    *
    * @param sbtProject
    *   sbt project that defines `specularSite` (default `docs`).
    * @param javaVersion
    *   optional Temurin JDK major passed through to the reusable workflow (omit to use the workflow default).
    */
  def pages(sbtProject: String = "docs", javaVersion: Option[String] = None): Capability =
    val inputs =
      Map("sbt-project" -> sbtProject) ++ javaVersion.map(v => "java-version" -> v).toMap
    Capability
      .once(
        name = "docs",
        command = "true", // unused: workflowCall replaces local steps
        phase = Phase.Deploy,
        gate = Gate.OnReleaseTag,
        permissions = pagesPermissions,
      )
      .copy(workflowCall = Some(WorkflowCall(uses = ReusableWorkflow, withInputs = inputs)))
  end pages

end ZipxDocs
