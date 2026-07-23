package zipx.specular

import zipx.core.*

/** Early-effect Specular docs paved path for zipx.
  *
  * Emits a once-job that calls the org reusable workflow
  * `early-effect/.github/.github/workflows/specular-docs.yml` (build Specular site → GitHub Pages). Same pattern as
  * peers' thin `docs.yml`, expressed as a capability so generated CI owns it.
  *
  * Runs on `v*` tags **or** manual `workflow_dispatch` (enable with `zipxWorkflowDispatch := true`). Publish stays
  * tag-only; Verify is skipped on dispatch so "Run workflow" is docs-cheap.
  *
  * {{{
  * zipxCapabilities += ZipxDocs.pages()
  * zipxWorkflowDispatch := true
  * // Layer a fork gate without wiping the tag|dispatch condition:
  * zipxCapabilities += ZipxDocs.pages().andCondition(JobCondition.repositoryIs("acme/libs"))
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

  /** Built-in job filter: release tags or manual workflow dispatch. */
  val deployWhen: JobCondition =
    JobCondition.onReleaseTag || JobCondition.onWorkflowDispatch

  /** Deploy Specular docs to GitHub Pages on `v*` tags or `workflow_dispatch`.
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
        gate = Gate.Always,
        permissions = pagesPermissions,
      )
      .copy(
        workflowCall = Some(WorkflowCall(uses = ReusableWorkflow, withInputs = inputs)),
        condition = Some(deployWhen),
      )
  end pages

end ZipxDocs
