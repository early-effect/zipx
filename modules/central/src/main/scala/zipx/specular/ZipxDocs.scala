package zipx.specular

import zipx.core.*
import zipx.workflow.ActionRef

/** Early-effect Specular docs paved path for zipx: a once-job that delegates to the org reusable workflow rather than
  * running steps of its own, so generated CI owns what a hand-written `docs.yml` used to.
  *
  * Reaching the `workflow_dispatch` half of [[deployWhen]] needs `zipxWorkflowDispatch := true`.
  *
  * {{{
  * zipxCapabilities += ZipxDocs.pages()
  * zipxWorkflowDispatch := true
  * // Layer a fork gate without wiping the tag|dispatch condition:
  * zipxCapabilities += ZipxDocs.pages().andCondition(JobCondition.repositoryIs("acme/libs"))
  * }}}
  */
object ZipxDocs:

  /** Builds `sbt <project>/specularSite` and deploys it to GitHub Pages. */
  val ReusableWorkflow: ActionRef =
    ActionRef("early-effect/.github/.github/workflows/specular-docs.yml@main")

  /** Also what a build names in `needsCapabilities` to sequence something after the docs deploy. */
  val DocsName: CapabilityName = CapabilityName("docs")

  /** What the reusable workflow requires of its caller. */
  val pagesPermissions: Map[String, String] = Map(
    "contents" -> "read",
    "pages"    -> "write",
    "id-token" -> "write",
  )

  val deployWhen: JobCondition =
    JobCondition.onReleaseTag || JobCondition.onWorkflowDispatch

  /** @param sbtProject
    *   the sbt project defining `specularSite`.
    * @param javaVersion
    *   Temurin JDK major; omit to take the reusable workflow's own default.
    */
  def pages(sbtProject: String = "docs", javaVersion: Option[JdkVersion] = None): Capability =
    val inputs =
      Map("sbt-project" -> sbtProject) ++ javaVersion.map(v => "java-version" -> (v: String)).toMap
    Capability
      .steps(
        name = DocsName,
        steps = _ => Nil,
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
