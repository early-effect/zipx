package zipx.core

import zipx.workflow.*

import scala.collection.immutable.ListMap

/** A `workflow_dispatch` workflow whose job checks the repo out and installs the sbt toolchain, then runs `steps`.
  *
  * The toolchain is the same step `ci.yml` uses (`zipx-sbt-setup` from [[PlanConfig]]): JDK, sbt, and the LocalDir
  * cache mode. The build supplies the steps that run after that. There is no sbt command unless one of those steps runs
  * `sbt` itself.
  *
  * {{{
  * zipxShellWorkflows += ShellWorkflow(
  *   path = ".github/workflows/publish-proof.yml",
  *   name = "publish-proof",
  *   steps = pins => List(Step.run(Script(Exec("scala-cli", Word.lit("version")))).named("scala-cli").build),
  * )
  * }}}
  */
final case class ShellWorkflow(
    path: String,
    name: String,
    steps: ActionPins => List[Step],
    env: Map[String, EnvValue] = Map.empty,
    permissions: ListMap[String, String] = ListMap("contents" -> "read"),
)

object ShellWorkflow:

  private val PathPattern = """\.github/workflows/([A-Za-z0-9][A-Za-z0-9._-]*)\.yml""".r

  private val Reserved: Set[String] = Set(
    "ci.yml",
    "zipx-release.yml",
    "zipx-coverage.yml",
    "zipx-deploy.yml",
    "zipx-version-updates.yml",
    "zipx-pin-check.yml",
    "zipx-pin-submit.yml",
  )

  def render(spec: ShellWorkflow, config: PlanConfig): Either[String, String] =
    for
      id <- jobId(spec.path)
      cacheMode = if config.cache == CacheBackend.LocalDir then LocalCacheMode.Restore else LocalCacheMode.Off
      workflow  = Workflow(
        name = spec.name,
        on = Triggers(workflowDispatch = Some(WorkflowDispatch())),
        permissions = spec.permissions,
        jobs = ListMap(
          (id: String) -> Job(
            name = Some(spec.name),
            runsOn = List(config.runnerOs),
            env = EnvValue.renderAll(config.env ++ spec.env),
            steps = Planner.checkoutThenSbtSetup(config, id, None, cacheMode) ++ spec.steps(config.actions),
          )
        ),
      )
      body <- Render.render(workflow)
    yield ActionPinFile.annotateUses(body, config.actions)

  private def jobId(path: String): Either[String, JobId] =
    path match
      case PathPattern(stem) if !Reserved.contains(path.substring(path.lastIndexOf('/') + 1)) =>
        JobId.make(stem).left.map(err => s"zipx: $path: $err")
      case _ =>
        Left(s"zipx: $path is not a shell workflow path under .github/workflows")
end ShellWorkflow
