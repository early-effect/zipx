package zipx.workflow

import neotype.unwrap
import zio.blocks.schema.*
import scala.collection.immutable.ListMap

/** Sub-types render through zio-blocks' YAML deriver, which kebab-cases field names as GitHub wants (`runs-on`).
  * [[Render]] hand-writes the `on:` block, whose event keys (`pull_request`) kebab-casing would mangle.
  *
  * Map fields are plain `Map` because zio-blocks derives no `Schema[ListMap]`. Populate them with a `ListMap`: the
  * derived codec preserves insertion order.
  */
final case class Workflow(
    name: String,
    on: Triggers,
    jobs: ListMap[String, Job],
    concurrency: Option[Concurrency] = None,
    permissions: Map[String, String] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
)

final case class Triggers(
    push: Option[BranchFilter] = None,
    pullRequest: Option[PullRequestTrigger] = None,
    workflowDispatch: Option[WorkflowDispatch] = None,
    workflowCall: Boolean = false,
    schedule: List[Cron] = Nil,
)

/** Inputs render in insertion order, which is the order GitHub's Run workflow form shows them in. */
final case class WorkflowDispatch(inputs: ListMap[InputName, DispatchInput] = ListMap.empty)

enum DispatchInput(val description: String):

  /** A required dropdown whose first option is the default. GitHub renders one choice, so a set is [[Text]]. */
  case Choice(override val description: String, options: ::[String]) extends DispatchInput(description)

  case Text(
      override val description: String,
      default: Option[String] = None,
      required: Boolean = false,
  ) extends DispatchInput(description)
end DispatchInput

/** @param types
  *   empty keeps GitHub's default: opened, synchronize, reopened.
  */
final case class PullRequestTrigger(
    filter: BranchFilter = BranchFilter(),
    types: List[PullRequestActivity] = Nil,
)

enum PullRequestActivity(val wire: String):
  case Opened      extends PullRequestActivity("opened")
  case Synchronize extends PullRequestActivity("synchronize")
  case Reopened    extends PullRequestActivity("reopened")
  case Labeled     extends PullRequestActivity("labeled")

/** GitHub Actions numbers cron days `0` = Sunday through `6` = Saturday, which is this enum's declaration order. */
enum DayOfWeek:
  case Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday

  def cronValue: Int = ordinal

/** Ranges live in [[CronHour]] / [[CronMinute]] rather than a render-time check, so `Cron.daily(hour = 24)` is a
  * compile error and [[render]] is total. [[Cron.Raw]] covers the step-value and range forms the variants cannot say.
  */
enum Cron:
  case Weekly(day: DayOfWeek, hour: CronHour = CronHour.Midnight, minute: CronMinute = CronMinute.Zero)
  case Daily(hour: CronHour = CronHour.Midnight, minute: CronMinute = CronMinute.Zero)
  case Hourly(minute: CronMinute = CronMinute.Zero)
  case Raw(expression: CronExpr)

  def render: String = this match
    case Cron.Weekly(day, hour, minute) => s"${minute.unwrap} ${hour.unwrap} * * ${day.cronValue}"
    case Cron.Daily(hour, minute)       => s"${minute.unwrap} ${hour.unwrap} * * *"
    case Cron.Hourly(minute)            => s"${minute.unwrap} * * * *"
    case Cron.Raw(expression)           => expression.unwrap
end Cron

object Cron:

  inline def weekly(day: DayOfWeek = DayOfWeek.Sunday, inline hour: Int = 0, inline minute: Int = 0): Cron =
    Weekly(day, CronHour(hour), CronMinute(minute))

  def weeklyMake(day: DayOfWeek, hour: Int, minute: Int): Either[String, Cron] =
    for
      h <- CronHour.make(hour)
      m <- CronMinute.make(minute)
    yield Weekly(day, h, m)

  inline def daily(inline hour: Int = 0, inline minute: Int = 0): Cron =
    Daily(CronHour(hour), CronMinute(minute))

  def dailyMake(hour: Int, minute: Int): Either[String, Cron] =
    for
      h <- CronHour.make(hour)
      m <- CronMinute.make(minute)
    yield Daily(h, m)

  inline def hourly(inline minute: Int = 0): Cron =
    Hourly(CronMinute(minute))

  def hourlyMake(minute: Int): Either[String, Cron] =
    CronMinute.make(minute).map(Hourly(_))

  inline def raw(inline expression: String): Cron =
    Raw(CronExpr(expression))

  def rawMake(expression: String): Either[String, Cron] =
    CronExpr.make(expression).map(Raw(_))

end Cron

final case class BranchFilter(
    branches: List[String] = Nil,
    tags: List[String] = Nil,
    paths: List[String] = Nil,
)

final case class Job(
    name: Option[String] = None,
    runsOn: List[String] = List("ubuntu-latest"),
    needs: List[String] = Nil,
    `if`: Option[String] = None,
    environment: Option[JobEnvironment] = None,
    /** GitHub's default for a job-level group is never to cancel in progress. */
    concurrency: Option[String] = None,
    permissions: Map[String, String] = ListMap.empty,
    strategy: Option[Strategy] = None,
    container: Option[String] = None,
    services: Map[String, JobService] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
    outputs: Map[String, String] = ListMap.empty,
    steps: List[Step] = Nil,
    /** A reusable-workflow call. When set, [[steps]] and [[runsOn]] must be empty. */
    uses: Option[ActionRef] = None,
    `with`: Map[String, String] = ListMap.empty,
) derives Schema

/** GitHub records a deployment for every job that binds one. Renders as the bare name when there is no `url`. */
final case class JobEnvironment(name: String, url: Option[String] = None) derives Schema

final case class JobService(
    image: String,
    ports: List[String] = Nil,
    options: Option[String] = None,
) derives Schema

final case class Strategy(
    failFast: Boolean = false,
    matrix: Map[String, List[String]] = ListMap.empty,
    /** Derived next to `matrix:`; [[Render]] nests it under `matrix.include` before printing. */
    include: List[Map[String, String]] = Nil,
) derives Schema

/** Flat rather than a `uses`/`run` sum type, because that is the on-disk shape and a sum would make the deriver emit
  * discriminator wrappers. So `Step()` compiles and renders YAML GitHub rejects: prefer [[Step.run]] / [[Step.uses]];
  * [[Render]] checks every step with [[Step.problem]].
  *
  * `shell` is required on `run:` steps inside a composite action; workflow jobs inherit the runner default.
  */
final case class Step(
    name: Option[String] = None,
    id: Option[String] = None,
    `if`: Option[String] = None,
    uses: Option[ActionRef] = None,
    run: Option[String] = None,
    `with`: Map[String, String] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
    workingDirectory: Option[String] = None,
    shell: Option[String] = None,
) derives Schema

object Step:

  def run(script: zipx.shell.Script): StepBuilder.Run = StepBuilder.run(script)

  /** **Escape hatch.** See [[StepBuilder.runRaw]].
    */
  def runRaw(text: String): StepBuilder.Run = StepBuilder.runRaw(text)

  inline def uses(inline action: String): StepBuilder.Uses = StepBuilder.uses(action)

  def usesRef(action: ActionRef): StepBuilder.Uses = StepBuilder.usesRef(action)

  def usesMake(action: String): Either[String, StepBuilder.Uses] = StepBuilder.usesMake(action)

  /** For hand-built and decoded steps; [[StepBuilder]] cannot produce these problems. Checked at render time rather
    * than on construction so a codec can fill a value in field by field.
    */
  def problem(step: Step): Option[String] =
    val where = step.name.orElse(step.id).map(n => s" '$n'").getOrElse("")
    (step.uses, step.run) match
      case (Some(_), Some(_)) =>
        Some(s"step$where sets both uses and run; a GitHub Actions step is one or the other")
      case (None, None) =>
        Some(s"step$where sets neither uses nor run; every step must do one or the other")
      case (None, Some(_)) if step.`with`.nonEmpty =>
        Some(
          s"step$where sets with: on a run step; with: passes inputs to an action, so GitHub ignores it here " +
            s"(keys: ${step.`with`.keys.toList.sorted.mkString(", ")})"
        )
      case _ => None
    end match
  end problem

  def validate(step: Step): Either[String, Step] =
    problem(step).toLeft(step)

end Step

final case class Concurrency(
    group: String,
    cancelInProgress: CancelInProgress = CancelInProgress.Never,
)

/** GitHub rejects the constants as strings, so they render as YAML booleans. */
enum CancelInProgress:
  case Never
  case Always
  case When(condition: Expr)
