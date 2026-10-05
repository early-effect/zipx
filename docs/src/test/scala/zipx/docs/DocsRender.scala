package zipx.docs

import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.workflow.Render
import zipx.workflow.Workflow
import scala.collection.immutable.ListMap

/** Result panels show real planner output, never hand-written YAML. */
object DocsRender:

  /** A `Left` is a planner bug, so it breaks the suite rather than printing an error into the published page. */
  extension (result: Either[String, String])
    def yaml: String =
      result.fold(error => throw AssertionError(s"planner produced an invalid workflow: $error"), identity)

  /** Expanded job ids unless a capability sets a mode: most pages teach job ids, not collapse. */
  private def forDocs(caps: Seq[Capability]): List[Capability] =
    caps.map(c => if c.matrixCollapse.isEmpty then c.withMatrixCollapse(MatrixCollapse.Off) else c).toList

  def plan(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): Workflow =
    Planner.plan(graph, forDocs(caps), cfg)

  def job(id: String)(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): String =
    val wf = plan(caps*)
    Render.renderJob(id, wf.jobs(id)).yaml

  def jobs(ids: String*)(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): String =
    val wf       = plan(caps*)
    val selected = ListMap.from(ids.map(id => id -> wf.jobs(id)))
    Render.renderJobs(selected).yaml

  def body(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): String =
    Render.renderBody(plan(caps*)).yaml

end DocsRender
