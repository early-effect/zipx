package zipx.core

import zipx.workflow.Step

import scala.collection.immutable.ListMap

/** scoverage as a capability, built so that the task it measures cannot be sbt 2's `test`.
  *
  * Why this is API rather than a documented alias: on sbt 2.0 plain `test` runs `testQuick`, which skips tests it deems
  * unaffected and prints "No tests to run". A hand-rolled `coverage; test; coverageAggregate` therefore satisfies a
  * `coverageMinimum` having measured almost nothing, and the job is green. Building the command from the module's own
  * [[ModuleNode.testTask]] is what closes that off.
  *
  * {{{
  * zipxCapabilities += Coverage.once()   // one root session: coverage; testFull; coverageAggregate
  * zipxCapabilities += Coverage.graph()  // one job per module, each measuring that module's own zipxTestTask
  * }}}
  */
object Coverage:

  val Name: CapabilityName = CapabilityName("coverage")

  /** Wire form: scoverage's `coverage` alias. Declared name; generate checks it when the alias is on the classpath. */
  private val Enable: SbtCommand = SbtCommand.unsafeCommand("coverage")

  /** Wire form: scoverage `coverageAggregate` task label. */
  private val Aggregate: SbtCommand = SbtCommand.unsafeTask("coverageAggregate")
  private val Report: SbtCommand    = SbtCommand.unsafeTask("coverageReport")

  /** Wire form: sbt 2's full suite (not `testQuick`). */
  private val FullTest: SbtCommand = SbtCommand.unsafeTask("testFull")

  /** The task to measure for `node`: its own [[ModuleNode.testTask]], substituting [[FullTest]] when that is still the
    * default `test`.
    *
    * The substitution is the point of the pack. A module that set `zipxTestTask` itself is left alone: an explicit
    * choice outranks this one.
    *
    * Pass `_.testTask` to [[graph]] for literal inheritance instead, default included.
    */
  def measuredTask(node: ModuleNode): SbtCommand =
    if node.testTask == ModuleNode.DefaultTestTask then FullTest else node.testTask

  /** Where sbt-scoverage writes HTML and XML, per module and for `coverageAggregate`. A glob because the Scala version
    * is in the path (`target/scala-3.8.4/scoverage-report`).
    */
  val ReportPaths: String = "**/scoverage-report/**"

  val DefaultArtifact: String = "coverage-report"

  def moduleArtifactName(moduleId: String): String = s"$DefaultArtifact-$moduleId"

  /** Uploads the reports under one fixed artifact name, for a capability with one job. */
  def uploadReportSteps(artifact: String = DefaultArtifact, path: String = ReportPaths): Steps =
    Steps.one("coverage-report")(ctx => uploadStep(ctx, artifact, path))

  /** The same, named per module, so [[CapabilityScope.Graph]]'s jobs do not collide on one artifact name. */
  def uploadModuleReportSteps(path: String = ReportPaths): Steps =
    Steps.one("coverage-report-per-module")(ctx => uploadStep(ctx, moduleArtifactName(ctx.node.id), path))

  /** `if-no-files-found: error` on purpose: a run that measured nothing produces no report, and this pack exists to
    * make that loud rather than upload an empty directory.
    */
  private def uploadStep(ctx: StepContext, artifact: String, path: String): Step =
    Step(
      name = Some("Upload coverage report"),
      uses = Some(ctx.actions.uploadArtifact),
      `with` = ListMap(
        "name"              -> artifact,
        "path"              -> path,
        "if-no-files-found" -> "error",
      ),
    )

  /** One build-wide session: `coverage; testFull; coverageAggregate`.
    *
    * The shape to prefer. `coverageAggregate` is a root task over every module's measurement data, so splitting it
    * across jobs means merging artifacts back together to get the number.
    *
    * No trailing `coverageOff`: the session ends with the job. It is in every hand-rolled alias because a developer's
    * shell session outlives the command, which CI's does not.
    *
    * `task` is a literal rather than the build's `zipxTestTask` because there is no module here to read one from, and a
    * root `test` is `testQuick` too. Pass it explicitly if the root task is not [[FullTest]].
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
      command = SbtCommand.session(Enable, task, Aggregate),
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
