package zipx.core

import zipx.shell.Script
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** What starts a [[CoverageWorkflow]] run. */
enum CoverageTrigger:
  /** GitHub runs schedules on the default branch only. */
  case Scheduled(cron: Cron)

  /** Actions → Run workflow, on any branch. */
  case Dispatch

  /** A PR carrying `label`: when that label is added, and on each push or reopen while it stays. */
  case PrLabel(label: ExprLiteral)

object CoverageTrigger:

  inline def prLabel(inline label: String): CoverageTrigger = PrLabel(ExprLiteral(label.trim))

  def prLabelMake(label: String): Either[String, CoverageTrigger] = ExprLiteral.make(label.trim).map(PrLabel(_))

/** scoverage in `zipx-coverage.yml`, so `ci.yml`'s required checks never compile instrumented classes.
  *
  * One job: `coverage; <task>; coverageAggregate`, then the report upload. It restores the LocalDir build snapshot and
  * never saves one. Nothing else joins this workflow, so no Publish or Deploy job can pick up an instrumented class.
  *
  * @param task
  *   the root task to measure. sbt 2's root `test` is `testQuick`, hence `testFull`.
  */
final case class CoverageWorkflow(
    triggers: ::[CoverageTrigger],
    task: SbtCommand = Coverage.FullTest,
):
  def command: SbtCommand = Coverage.aggregateSession(task)

object CoverageWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-coverage.yml"

  private val jobId: JobId = Coverage.Name.asJobId

  def plan(coverage: CoverageWorkflow, config: PlanConfig): Workflow =
    val triggers  = coverage.triggers.distinct
    val labels    = triggers.collect { case CoverageTrigger.PrLabel(label) => label }
    val cacheMode =
      if config.cache == CacheBackend.LocalDir then LocalCacheMode.Restore else LocalCacheMode.Off
    Workflow(
      name = "zipx coverage",
      on = Triggers(
        pullRequest = Option.when(labels.nonEmpty)(PullRequestTrigger(types = labelActivities)),
        workflowDispatch = triggers.contains(CoverageTrigger.Dispatch),
        schedule = triggers.collect { case CoverageTrigger.Scheduled(cron) => cron },
      ),
      permissions = ListMap("contents" -> "read"),
      concurrency = Some(
        Concurrency(
          group = (Expr.lit("zipx-coverage-") ++ Expr.github("ref")).render,
          cancelInProgress = CancelInProgress.Always,
        )
      ),
      jobs = ListMap(
        jobId -> Job(
          name = Some(Coverage.Name),
          runsOn = List(config.runnerOs),
          `if` = labelGate(labels).map(_.unwrapped),
          env = EnvValue.renderAll(config.env),
          steps = Planner.checkoutThenSbtSetup(config, jobId, nodeVersion = None, cacheMode) ++ List(
            Step.run(Script(coverage.command.render)).named(Coverage.Name).build,
            Coverage.uploadStep(config.actions, Coverage.DefaultArtifact),
          ),
        )
      ),
    )
  end plan

  def render(coverage: CoverageWorkflow, config: PlanConfig): Either[String, String] =
    Render.render(plan(coverage, config)).map(ActionPinFile.annotateUses(_, config.actions))

  /** `labeled` starts a run when the label goes on; the rest keep it current while the label stays. */
  private val labelActivities: List[PullRequestActivity] =
    List(
      PullRequestActivity.Opened,
      PullRequestActivity.Synchronize,
      PullRequestActivity.Reopened,
      PullRequestActivity.Labeled,
    )

  /** A PR run needs one of `labels` on the PR. A `labeled` event also needs the added label to be one of them, so
    * tagging a coverage PR with an unrelated label does not measure it again.
    */
  private def labelGate(labels: List[ExprLiteral]): Option[Expr] =
    Option.when(labels.nonEmpty) {
      val carries = labels
        .map(l => Expr.contains(Expr.github("event.pull_request.labels.*.name"), Expr.Quoted(l)))
        .reduceLeft(_ || _)
      val added = labels.map(l => Expr.github("event.label.name") === Expr.Quoted(l)).reduceLeft(_ || _)
      val onPr  =
        (if labels.sizeIs > 1 then Expr.group(carries) else carries) &&
          Expr.group((Expr.github("event.action") !== Expr.quoted("labeled")) || added)
      (Expr.github("event_name") !== Expr.quoted("pull_request")) || Expr.group(onPr)
    }

end CoverageWorkflow
