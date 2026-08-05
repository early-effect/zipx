package zipx.docs

import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.workflow.Render
import zipx.workflow.Workflow
import scala.collection.immutable.ListMap

/** Plan + fragment YAML for Specular docs (result panels show real `ci.yml` bytes). */
object DocsRender:

  /** The rendered YAML, failing the docs build if the planner produced a step GitHub would reject.
    *
    * `Render` returns `Either` because a hand-built `Workflow` can be invalid. Every workflow here comes from
    * [[zipx.core.Planner]], so a `Left` means a planner bug, not a doc author's mistake. Failing loudly is the right
    * response: these pages are tests, and a broken plan should break the suite rather than print an error message into
    * the published documentation.
    */
  extension (result: Either[String, String])
    def yaml: String =
      result.fold(error => throw AssertionError(s"planner produced an invalid workflow: $error"), identity)

  def plan(caps: Capability*)(using graph: ModuleGraph = libGraph, cfg: PlanConfig = config): Workflow =
    Planner.plan(graph, caps.toList, cfg)

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
