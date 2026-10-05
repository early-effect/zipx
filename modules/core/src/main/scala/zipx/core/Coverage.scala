package zipx.core

import zipx.workflow.Step

import scala.collection.immutable.ListMap

/** scoverage as a capability, built so that the task it measures cannot be plain `test`.
  *
  * Plain `test` runs `testQuick`, which skips suites it deems unaffected, so a hand-rolled
  * `coverage; test; coverageAggregate` passes a `coverageMinimum` having measured almost nothing.
  *
  * {{{
  * zipxCoverageWorkflow := Some(Coverage.workflow(CoverageTrigger.Dispatch, CoverageTrigger.prLabel("coverage")))
  * zipxCapabilities += Coverage.once()   // in ci.yml: coverage; testFull; coverageAggregate
  * zipxCapabilities += Coverage.graph()  // in ci.yml: one job per module, each measuring its own zipxTestTask
  * }}}
  *
  * Every shape restores the build snapshot and none may save it, so instrumented classes never become the entry the
  * builtin `test` and image jobs restore.
  */
object Coverage:

  val Name: CapabilityName = CapabilityName("coverage")

  /** Generate checks this name when the scoverage alias is on the classpath. */
  private val Enable: SbtCommand = SbtCommand.unsafeCommand("coverage")

  private val Aggregate: SbtCommand = SbtCommand.unsafeTask("coverageAggregate")
  private val Report: SbtCommand    = SbtCommand.unsafeTask("coverageReport")

  /** The full suite, since `test` is `testQuick`. */
  private[core] val FullTest: SbtCommand = SbtCommand.unsafeTask("testFull")

  private[core] def instruments(capability: Capability): Boolean =
    capability.declaredNames.exists(Enable.declaredNames.contains)

  private[core] def aggregateSession(task: SbtCommand): SbtCommand =
    SbtCommand.session(Enable, task, Aggregate)

  /** Coverage in its own workflow, off `ci.yml`'s required checks. */
  def workflow(first: CoverageTrigger, rest: CoverageTrigger*): CoverageWorkflow =
    CoverageWorkflow(::(first, rest.toList))

  /** The module's own [[ModuleNode.testTask]], except that the default `test` becomes [[FullTest]]. Pass `_.testTask`
    * to [[graph]] to keep the default as is.
    */
  def measuredTask(node: ModuleNode): SbtCommand =
    if node.testTask == ModuleNode.DefaultTestTask then FullTest else node.testTask

  /** A glob because the Scala version is in the path (`target/scala-<version>/scoverage-report`). */
  val ReportPaths: String = "**/scoverage-report/**"

  val DefaultArtifact: String = "coverage-report"

  def moduleArtifactName(moduleId: String): String = s"$DefaultArtifact-$moduleId"

  /** One fixed artifact name, for a capability with one job. */
  def uploadReportSteps(artifact: String = DefaultArtifact, path: String = ReportPaths): Steps =
    Steps.one("coverage-report")(ctx => uploadStep(ctx.actions, artifact, path))

  /** Named per module, so [[CapabilityScope.Graph]]'s jobs do not collide on one artifact name. */
  def uploadModuleReportSteps(path: String = ReportPaths): Steps =
    Steps.one("coverage-report-per-module")(ctx => uploadStep(ctx.actions, moduleArtifactName(ctx.node.id), path))

  /** `if-no-files-found: error` on purpose: a run that measured nothing produces no report, and this pack exists to
    * make that loud rather than upload an empty directory.
    */
  private[core] def uploadStep(actions: ActionPins, artifact: String, path: String = ReportPaths): Step =
    Step(
      name = Some("Upload coverage report"),
      uses = Some(actions.uploadArtifact),
      `with` = ListMap(
        "name"              -> artifact,
        "path"              -> path,
        "if-no-files-found" -> "error",
      ),
    )

  /** One build-wide session, `coverage; testFull; coverageAggregate`: the shape to prefer, since `coverageAggregate`
    * reads every module's data. No trailing `coverageOff`, because the session ends with the job.
    *
    * `task` is a literal because there is no module here to read `zipxTestTask` from. Generate refuses
    * `name = Capability.TestName`, which would make coverage every PR's required check; use [[workflow]].
    */
  def once(
      task: SbtCommand = FullTest,
      name: CapabilityName = Name,
      gate: Gate = Gate.Always,
      uploadReport: Boolean = true,
      artifact: String = DefaultArtifact,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability.once(
      name = name,
      command = aggregateSession(task),
      phase = Phase.Verify,
      gate = gate,
      postSteps = if uploadReport then uploadReportSteps(artifact) else Steps.empty,
      condition = condition,
    )

  /** One job per module: `coverage; <id>/<task>; <id>/coverageReport`.
    *
    * Affected-gated like any Graph Verify capability, which is what makes it affordable on a large build. The trade is
    * scoverage's own: there is no cross-module aggregate, so a `coverageMinimum` is enforced per module.
    */
  def graph(
      task: ModuleNode => SbtCommand = measuredTask,
      name: CapabilityName = Name,
      participates: ModuleNode => Boolean = _.ciRelevant,
      gate: Gate = Gate.Always,
      uploadReport: Boolean = true,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability(
      name = name,
      phase = Phase.Verify,
      ordering = Ordering.ParallelWithUpstream,
      gate = gate,
      participates = participates,
      command = CommandSource.PerModule(n =>
        SbtCommand.session(Enable, SbtCommand.module(n, task(n)), SbtCommand.module(n, Report))
      ),
      matrixed = false,
      postSteps = if uploadReport then uploadModuleReportSteps() else Steps.empty,
      scope = CapabilityScope.Graph,
      condition = condition,
    )

end Coverage
