package zipx.docs

import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.workflow.Render
import zipx.workflow.Workflow
import scala.collection.immutable.ListMap

/** Plan + fragment YAML for Specular docs (result panels show real `ci.yml` bytes). */
object DocsRender:

  /** The rendered YAML. Every workflow here comes from [[zipx.core.Planner]], so a `Left` is a planner bug and should
    * break the suite rather than print an error into the published page.
    */
  extension (result: Either[String, String])
    def yaml: String =
      result.fold(error => throw AssertionError(s"planner produced an invalid workflow: $error"), identity)

  /** Doc examples that do not set [[Capability.matrixCollapse]] keep expanded job ids (Off). Pages that teach Auto /
    * Strict / Coarse pass an explicit mode on the capability.
    */
  private def forDocs(caps: Seq[Capability]): List[Capability] =
    caps.map(c => if c.matrixCollapse.isEmpty then c.withMatrixCollapse(MatrixCollapse.Off) else c).toList

  def plan(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): Workflow =
    Planner.plan(graph, forDocs(caps), cfg)

  /** Single job YAML fragment (`publish:` + body). */
  def job(id: String)(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): String =
    val wf = plan(caps*)
    Render.renderJob(id, wf.jobs(id)).yaml

  /** Selected jobs as a mapping fragment, in `ids` order. */
  def jobs(ids: String*)(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): String =
    val wf       = plan(caps*)
    val selected = ListMap.from(ids.map(id => id -> wf.jobs(id)))
    Render.renderJobs(selected).yaml

  /** Full workflow body (no generated-by header). */
  def body(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): String =
    Render.renderBody(plan(caps*)).yaml

end DocsRender
