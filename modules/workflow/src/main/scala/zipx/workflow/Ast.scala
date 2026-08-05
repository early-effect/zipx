package zipx.workflow

import neotype.unwrap
import zio.blocks.schema.*
import scala.collection.immutable.ListMap

/** A GitHub Actions workflow, modeled as an algebraic data type.
  *
  * The sub-types (`Job`, `Step`, `Strategy`, `Container`, `Concurrency`) `derive Schema` and are rendered by
  * zio-blocks' YAML deriver, which kebab-cases every field name, exactly what GitHub Actions wants for job/step keys
  * (`runs-on`, `timeout-minutes`, `fail-fast`, ...). See [[Render]] for the one place derivation cannot reach: the
  * `on:` triggers block, whose keys (`pull_request`, `workflow_dispatch`, ...) use underscores that kebab-casing would
  * mangle.
  *
  * Map fields use plain `Map` (zio-blocks derives `Schema[Map]` but not `Schema[ListMap]`); populate them with
  * `ListMap` to keep insertion order deterministic; the derived codec preserves it.
  */
final case class Workflow(
    name: String,
    on: Triggers,
    jobs: ListMap[String, Job],
    concurrency: Option[Concurrency] = None,
    permissions: Map[String, String] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
)

/** The `on:` block. Rendered by a hand-written codec (see [[Render.triggersYaml]]) because GitHub's event keys use
  * underscores that the kebab-casing deriver cannot produce.
  */
final case class Triggers(
    push: Option[BranchFilter] = None,
    pullRequest: Option[BranchFilter] = None,
    workflowDispatch: Boolean = false,
    workflowCall: Boolean = false,
    /** `on.schedule` entries. Prefer [[Cron]] smart constructors over [[Cron.Raw]]. */
    schedule: List[Cron] = Nil,
)

/** Day-of-week for [[Cron.Weekly]] (GitHub Actions: `0` = Sunday … `6` = Saturday). */
enum DayOfWeek:
  case Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday

  /** Numeric field used in the five-field cron expression. */
  def cronValue: Int = ordinal

/** Five-field UTC cron for GitHub Actions `on.schedule` (`minute hour day-of-month month day-of-week`).
  *
  * Ranges are enforced by the field types ([[CronHour]], [[CronMinute]]) rather than checked when rendering, so
  * `Cron.daily(hour = 24)` is a compile error at the call site and [[render]] is total. A schedule is written as a
  * literal in a build, which is what makes the compile-time form the right one here; `Cron.dailyMake` is the sibling
  * for an hour that arrives as runtime data.
  *
  * Prefer [[Cron.weekly]], [[Cron.daily]], [[Cron.hourly]] over [[Cron.Raw]], the escape hatch for the step-value and
  * range forms the variants cannot express.
  */
enum Cron:
  case Weekly(day: DayOfWeek, hour: CronHour = CronHour.Midnight, minute: CronMinute = CronMinute.Zero)
  case Daily(hour: CronHour = CronHour.Midnight, minute: CronMinute = CronMinute.Zero)
  case Hourly(minute: CronMinute = CronMinute.Zero)
  case Raw(expression: CronExpr)

  /** Render to the string that lands in YAML `cron:`. Total: every field is a type that cannot hold a bad value. */
  def render: String = this match
    case Cron.Weekly(day, hour, minute) => s"${minute.unwrap} ${hour.unwrap} * * ${day.cronValue}"
    case Cron.Daily(hour, minute)       => s"${minute.unwrap} ${hour.unwrap} * * *"
    case Cron.Hourly(minute)            => s"${minute.unwrap} * * * *"
    case Cron.Raw(expression)           => expression.unwrap
end Cron

object Cron:

  /** `weekly(Monday, hour = 6)`, with the hour and minute checked during compilation. */
  inline def weekly(day: DayOfWeek = DayOfWeek.Sunday, inline hour: Int = 0, inline minute: Int = 0): Cron =
    Weekly(day, CronHour(hour), CronMinute(minute))

  /** [[weekly]] for an hour or minute that arrives as runtime data. */
  def weeklyMake(day: DayOfWeek, hour: Int, minute: Int): Either[String, Cron] =
    for
      h <- CronHour.make(hour)
      m <- CronMinute.make(minute)
    yield Weekly(day, h, m)

  inline def daily(inline hour: Int = 0, inline minute: Int = 0): Cron =
    Daily(CronHour(hour), CronMinute(minute))

  /** [[daily]] for an hour or minute that arrives as runtime data. */
  def dailyMake(hour: Int, minute: Int): Either[String, Cron] =
    for
      h <- CronHour.make(hour)
      m <- CronMinute.make(minute)
    yield Daily(h, m)

  inline def hourly(inline minute: Int = 0): Cron =
    Hourly(CronMinute(minute))

  /** [[hourly]] for a minute that arrives as runtime data. */
  def hourlyMake(minute: Int): Either[String, Cron] =
    CronMinute.make(minute).map(Hourly(_))

  /** Escape hatch: a raw five-field cron string, checked during compilation. */
  inline def raw(inline expression: String): Cron =
    Raw(CronExpr(expression))

  /** [[raw]] for an expression that arrives as runtime data. */
  def rawMake(expression: String): Either[String, Cron] =
    CronExpr.make(expression).map(Raw(_))

end Cron

/** Branch/tag/path filters for a `push` or `pull_request` trigger. Empty lists are pruned at render time. */
final case class BranchFilter(
    branches: List[String] = Nil,
    tags: List[String] = Nil,
    paths: List[String] = Nil,
)

final case class Job(
    name: Option[String] = None,
    // One or more runner labels. A single label renders as a scalar (`runs-on: ubuntu-latest`); multiple render as a
    // YAML sequence (`runs-on: [self-hosted, linux]` in block form). See Render.jobsYaml.
    // Empty when this job is a reusable-workflow call ([[uses]]); prune drops the empty sequence.
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
    /** Reusable workflow call (`jobs.<id>.uses`). When set, [[steps]] / [[runsOn]] should be empty. */
    uses: Option[String] = None,
    /** Inputs for a reusable workflow call (`jobs.<id>.with`). */
    `with`: Map[String, String] = ListMap.empty,
) derives Schema

/** A GitHub Actions service container (a sidecar running for the duration of a job), e.g. a `bazel-remote` gRPC cache.
  */
final case class JobService(
    image: String,
    ports: List[String] = Nil,
    options: Option[String] = None,
) derives Schema

final case class Strategy(
    failFast: Boolean = false,
    matrix: Map[String, List[String]] = ListMap.empty,
) derives Schema

/** A single step. A GitHub Actions step is a flat mapping with optional keys; modeling it as one case class with
  * all-optional fields (rather than a `uses`-vs-`run` sum type) avoids variant discriminator wrappers and matches the
  * on-disk shape exactly. `None`/empty fields are omitted at render time.
  *
  * The cost of that shape is that `Step()` and `Step(uses = …, run = …)` both compile and both render YAML GitHub
  * rejects. Prefer the builders [[Step.run]] / [[Step.uses]], which cannot express either; [[Step.validate]] catches
  * what is hand-built, and [[Render]] calls it on every step it encodes.
  */
final case class Step(
    name: Option[String] = None,
    id: Option[String] = None,
    `if`: Option[String] = None,
    uses: Option[String] = None,
    run: Option[String] = None,
    `with`: Map[String, String] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
    workingDirectory: Option[String] = None,
) derives Schema

object Step:

  /** Start a `run:` step from a typed script: `Step.run(script).named("Test").build`. */
  def run(script: zipx.shell.Script): StepBuilder.Run = StepBuilder.run(script)

  /** **Escape hatch.** Start a `run:` step from verbatim text. See [[StepBuilder.runRaw]].
    */
  def runRaw(text: String): StepBuilder.Run = StepBuilder.runRaw(text)

  /** Start a `uses:` step from a literal action ref, checked during compilation. See [[StepBuilder.uses]]. */
  inline def uses(inline action: String): StepBuilder.Uses = StepBuilder.uses(action)

  /** [[uses]] for a ref that arrives as runtime data, normally an `ActionPins` field. See [[StepBuilder.usesMake]]. */
  def usesMake(action: String): Either[String, StepBuilder.Uses] = StepBuilder.usesMake(action)

  /** The reason this step is not one GitHub Actions accepts, if there is one.
    *
    * A pure query, not a check that throws: the caller decides what a bad step means. [[Render]] reports it as a
    * rendering failure, and that is the only place in zipx that needs to.
    *
    * The two structural rules a flat case class cannot encode:
    *
    *   - exactly one of `uses` and `run`; neither is a step that does nothing, both is ambiguous and GitHub errors
    *   - `with:` belongs to `uses`; on a `run:` step GitHub ignores it, which reads as a silently dropped input
    *
    * Both are unreachable through [[StepBuilder]], which fixes `run:`/`uses:` in its type and gives `withInput` only to
    * the `uses:` case. This exists for the other two ways a `Step` comes into being: hand-construction, which stays
    * legal because the case class shape is fixed by the on-disk contract, and codec decoding. Checking at render time
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

  /** `Right(step)` if this is a step GitHub Actions accepts, `Left` with [[problem]]'s message otherwise. */
  def validate(step: Step): Either[String, Step] =
    problem(step).toLeft(step)

end Step

/** Workflow- or job-level `concurrency`.
  *
  * `cancelInProgress` is a String, not a Boolean, because GitHub accepts an expression there and the useful policies
  * need one: "cancel superseded runs, but never a release publish" is `${{ !startsWith(github.ref, 'refs/tags/') }}`.
  * Pass `"true"` / `"false"` for the constant cases.
  */
final case class Concurrency(
    group: String,
    cancelInProgress: String = "false",
) derives Schema
