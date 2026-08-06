package zipx.workflow

import neotype.unwrap
import zio.blocks.schema.*
import scala.collection.immutable.ListMap

/** A GitHub Actions workflow as an algebraic data type.
  *
  * The sub-types `derive Schema` and render through zio-blocks' YAML deriver, which kebab-cases every field name: what
  * GitHub wants for job and step keys (`runs-on`, `fail-fast`). [[Render]] hand-writes the `on:` block, the one place
  * derivation cannot reach, since event keys like `pull_request` use underscores kebab-casing would mangle.
  *
  * Map fields are typed as plain `Map` because zio-blocks derives `Schema[Map]` but not `Schema[ListMap]`. Populate
  * them with a `ListMap` for deterministic order; the derived codec preserves insertion order.
  */
final case class Workflow(
    name: String,
    on: Triggers,
    jobs: ListMap[String, Job],
    concurrency: Option[Concurrency] = None,
    permissions: Map[String, String] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
)

/** See [[Render.triggersYaml]] for why this one block is rendered by hand. */
final case class Triggers(
    push: Option[BranchFilter] = None,
    pullRequest: Option[BranchFilter] = None,
    workflowDispatch: Boolean = false,
    workflowCall: Boolean = false,
    schedule: List[Cron] = Nil,
)

/** GitHub Actions numbers cron days `0` = Sunday through `6` = Saturday, which is this enum's declaration order. */
enum DayOfWeek:
  case Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday

  def cronValue: Int = ordinal

/** Five-field UTC cron for `on.schedule`: `minute hour day-of-month month day-of-week`.
  *
  * Ranges live in the field types ([[CronHour]], [[CronMinute]]) rather than in a render-time check, so
  * `Cron.daily(hour = 24)` is a compile error at the call site and [[render]] is total. [[Cron.Raw]] is the escape
  * hatch for the step-value and range forms the variants cannot express.
  *
  * Each `inline` constructor checks a literal while the consumer's build compiles; its `*Make` sibling takes runtime
  * data and returns an `Either`.
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
    environment: Option[String] = None,
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

final case class JobService(
    image: String,
    ports: List[String] = Nil,
    options: Option[String] = None,
) derives Schema

final case class Strategy(
    failFast: Boolean = false,
    matrix: Map[String, List[String]] = ListMap.empty,
) derives Schema

/** One flat case class with all-optional fields rather than a `uses`-vs-`run` sum type, because that is the on-disk
  * shape and a sum type would make the deriver emit variant discriminator wrappers.
  *
  * The cost is that `Step()` and `Step(uses = …, run = …)` both compile and both render YAML GitHub rejects. Prefer the
  * builders [[Step.run]] / [[Step.uses]], which cannot express either; [[Step.validate]] catches what is hand-built,
  * and [[Render]] calls it on every step it encodes.
  *
  * `uses` is an [[ActionRef]], not a `String`: the *shape* of an action ref is the field's own business, so a step
  * cannot be built around an unpinned or malformed one even by hand-construction. zio-blocks derives a `Schema` for a
  * neotype as its underlying primitive, so this renders as the same YAML scalar a `String` did.
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
) derives Schema

object Step:

  /** `Step.run(script).named("Test").build`. See [[StepBuilder.run]]. */
  def run(script: zipx.shell.Script): StepBuilder.Run = StepBuilder.run(script)

  /** **Escape hatch.** See [[StepBuilder.runRaw]].
    */
  def runRaw(text: String): StepBuilder.Run = StepBuilder.runRaw(text)

  /** See [[StepBuilder.uses]]. */
  inline def uses(inline action: String): StepBuilder.Uses = StepBuilder.uses(action)

  /** See [[StepBuilder.usesRef]]. */
  def usesRef(action: ActionRef): StepBuilder.Uses = StepBuilder.usesRef(action)

  /** See [[StepBuilder.usesMake]]. */
  def usesMake(action: String): Either[String, StepBuilder.Uses] = StepBuilder.usesMake(action)

  /** The two structural rules a flat case class cannot encode: exactly one of `uses` and `run`, and `with:` only on a
    * `uses:` step (GitHub silently ignores it on a `run:` step). [[StepBuilder]] makes both unreachable, so this exists
    * for the other two ways a `Step` comes into being: hand-construction and codec decoding. Checking at render time
    * rather than on construction is what lets a codec fill a value in field by field.
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

/** `cancelInProgress` is a String, not a Boolean, because GitHub accepts an expression there and the useful policies
  * need one: "cancel superseded runs, but never a release publish" is `${{ !startsWith(github.ref, 'refs/tags/') }}`.
  * Pass `"true"` / `"false"` for the constant cases.
  */
final case class Concurrency(
    group: String,
    cancelInProgress: String = "false",
) derives Schema
