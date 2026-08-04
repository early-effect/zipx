package zipx.workflow

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
  * Prefer [[Cron.weekly]], [[Cron.daily]], [[Cron.hourly]] over [[Cron.Raw]]. Constructors validate ranges;
  * [[Cron.Raw]] is the escape hatch for expressions the variants cannot express.
  */
enum Cron:
  case Weekly(day: DayOfWeek, hour: Int = 0, minute: Int = 0)
  case Daily(hour: Int = 0, minute: Int = 0)
  case Hourly(minute: Int = 0)
  case Raw(expression: String)

  /** Render to the string that lands in YAML `cron:`. */
  def render: String = this match
    case Cron.Weekly(day, hour, minute) =>
      Cron.requireMinute(minute)
      Cron.requireHour(hour)
      s"$minute $hour * * ${day.cronValue}"
    case Cron.Daily(hour, minute) =>
      Cron.requireMinute(minute)
      Cron.requireHour(hour)
      s"$minute $hour * * *"
    case Cron.Hourly(minute) =>
      Cron.requireMinute(minute)
      s"$minute * * * *"
    case Cron.Raw(expression) =>
      Cron.requireRaw(expression)
end Cron

object Cron:

  def weekly(day: DayOfWeek = DayOfWeek.Sunday, hour: Int = 0, minute: Int = 0): Cron =
    Weekly(day, hour, minute)

  def daily(hour: Int = 0, minute: Int = 0): Cron =
    Daily(hour, minute)

  def hourly(minute: Int = 0): Cron =
    Hourly(minute)

  /** Escape hatch: a raw five-field cron string, rendered verbatim after light validation. */
  def raw(expression: String): Cron =
    Raw(expression)

  private def requireMinute(minute: Int): Unit =
    require(minute >= 0 && minute <= 59, s"cron minute must be 0–59, got $minute")

  private def requireHour(hour: Int): Unit =
    require(hour >= 0 && hour <= 23, s"cron hour must be 0–23, got $hour")

  private val RawPattern = raw"\S+(?:\s+\S+){4}".r

  private def requireRaw(expression: String): String =
    val trimmed = expression.trim
    if trimmed.isEmpty then throw IllegalArgumentException("cron expression must be non-empty")
    if RawPattern.matches(trimmed) then trimmed
    else
      throw IllegalArgumentException(
        s"invalid cron '$expression': expected five whitespace-separated fields (minute hour dom month dow)"
      )
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
  def run(script: zipx.shell.Script): StepBuilder = StepBuilder.run(script)

  /** **Escape hatch.** Start a `run:` step from verbatim text. See [[StepBuilder.runRaw]].
    */
  def runRaw(text: String): StepBuilder = StepBuilder.runRaw(text)

  /** Start a `uses:` step from an action ref, normally an `ActionPins` field. */
  def uses(action: String): StepBuilder = StepBuilder.uses(action)

  /** Throw unless this step is one GitHub Actions accepts.
    *
    * The two structural rules a flat case class cannot encode:
    *
    *   - exactly one of `uses` and `run`; neither is a step that does nothing, both is ambiguous and GitHub errors
    *   - `with:` belongs to `uses`; on a `run:` step GitHub ignores it, which reads as a silently dropped input
    *
    * Called from [[Render]] rather than from the constructor, because a `Step` is also a decode target: validating on
    * construction would reject a partially-built value that a codec is still filling in. Render time is the last moment
    * the information exists and the first moment the step is claimed to be complete.
    */
  def validate(step: Step): Unit =
    val where = step.name.orElse(step.id).map(n => s" '$n'").getOrElse("")
    (step.uses, step.run) match
      case (Some(_), Some(_)) =>
        throw IllegalArgumentException(
          s"step$where sets both uses and run; a GitHub Actions step is one or the other"
        )
      case (None, None) =>
        throw IllegalArgumentException(
          s"step$where sets neither uses nor run; every step must do one or the other"
        )
      case (None, Some(_)) if step.`with`.nonEmpty =>
        throw IllegalArgumentException(
          s"step$where sets with: on a run step; with: passes inputs to an action, so GitHub ignores it here " +
            s"(keys: ${step.`with`.keys.toList.sorted.mkString(", ")})"
        )
      case _ => ()
    end match
  end validate

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
