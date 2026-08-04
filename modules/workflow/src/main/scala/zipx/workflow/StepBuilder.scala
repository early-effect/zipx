package zipx.workflow

import neotype.unwrap
import zipx.shell.Script

import scala.collection.immutable.ListMap

/** A [[Step]] under construction, with typed fields.
  *
  * [[Step]] itself stays a flat all-optional case class: its shape is fixed by `derives Schema` and the on-disk
  * mapping, and changing it would move bytes. Validity is closed from both ends instead. This is the good end: a
  * builder starts from either [[Step.run]] or [[Step.uses]], so the mutually-exclusive pair is decided before any other
  * field is set, and it cannot be made to produce a step with both or neither. [[Step.validate]] is the other end,
  * catching a step that was hand-built around the builder.
  *
  * The fields are typed the way the layer above wants them: a `run:` body is a [[zipx.shell.Script]], an `if:` is an
  * [[Expr]], an id is a [[StepId]], `with:` values are `Expr | String`. Nothing here accepts a bare `${{ … }}` string.
  *
  * {{{
  * Step
  *   .run(Script.strict(Exec("sbt", Word.squote("test"))))
  *   .named("Test")
  *   .withId("test")
  *   .when(Expr.github("event_name"))
  *   .withEnv("TIER", Expr.env("TIER"))
  *   .build
  * }}}
  *
  * `rawFragments` is carried forward rather than dropped, so `zipxWorkflowGenerate` can warn about escape-hatch content
  * and name the step it came from. That information exists only here: [[Step]] gains no field for it.
  */
final case class StepBuilder(
    private val step: Step,
    /** Escape-hatch text in this step's script, for the generate-time warning. */
    rawFragments: List[String] = Nil,
):

  /** The step's `name:`, which is what shows up in the Actions UI. */
  def named(name: String): StepBuilder = copy(step = step.copy(name = Some(name)))

  /** The step's `id:`, so a later step can read `steps.<id>.outputs.<name>`. Validated at compile time for a literal.
    */
  inline def withId(inline id: String): StepBuilder = withStepId(StepId(id))

  /** [[withId]] for an already-validated id. */
  def withStepId(id: StepId): StepBuilder = copy(step = step.copy(id = Some(id.unwrap)))

  /** The step's `if:`. */
  def when(condition: Expr): StepBuilder = copy(step = step.copy(`if` = Some(condition.render)))

  /** One `with:` input. Values accumulate in call order, which is what keeps the rendered YAML deterministic. */
  def withInput(name: String, value: Expr): StepBuilder = withInput(name, value.render)

  /** One `with:` input with a literal value, for the many action inputs that are plain data (`fetch-depth`, a path). */
  def withInput(name: String, value: String): StepBuilder =
    copy(step = step.copy(`with` = step.`with` + (name -> value)))

  /** Several `with:` inputs at once. Pass a `ListMap` to fix the order. */
  def withInputs(inputs: Map[String, String]): StepBuilder =
    copy(step = step.copy(`with` = step.`with` ++ inputs))

  /** One step-scoped `env:` entry. Validated at compile time for a literal name. */
  inline def withEnv(inline name: String, value: Expr): StepBuilder = withEnvName(EnvName(name), value)

  /** [[withEnv]] for an already-validated name. */
  def withEnvName(name: EnvName, value: Expr): StepBuilder =
    copy(step = step.copy(env = step.env + (name.unwrap -> value.render)))

  /** Several `env:` entries at once, already rendered. Pass a `ListMap` to fix the order. */
  def withEnvs(entries: Map[String, String]): StepBuilder =
    copy(step = step.copy(env = step.env ++ entries))

  /** The step's `working-directory:`. */
  def in(workingDirectory: String): StepBuilder =
    copy(step = step.copy(workingDirectory = Some(workingDirectory)))

  /** The finished step. Validated here as well as at render time, so the failure lands at the construction site. */
  def build: Step =
    Step.validate(step)
    step
end StepBuilder

object StepBuilder:

  /** A `run:` step from a typed script. */
  def run(script: Script): StepBuilder =
    StepBuilder(Step(run = Some(script.render)), script.rawFragments)

  /** **Escape hatch.** A `run:` step from verbatim text.
    *
    * Present because `CacheEpoch.Script` and consumer builds still pass hand-written shell, and because a build that
    * cannot express something should not be blocked. The text is reported as a raw fragment, so `zipxWorkflowGenerate`
    * warns and names the step; prefer [[run]], whose script cannot produce a line YAML will mangle.
    */
  def runRaw(text: String): StepBuilder =
    StepBuilder(Step(run = Some(text)), List(text))

  /** A `uses:` step.
    *
    * Takes a `String` rather than an [[ActionRef]] because the value normally comes from `ActionPins.field`, which
    * reads the pin file at build time and so is not a literal. The ref is validated on the way in, which is the same
    * check either way; [[usesRef]] is the pre-validated form.
    */
  def uses(action: String): StepBuilder =
    ActionRef.make(action) match
      case Right(ref)  => usesRef(ref)
      case Left(error) => throw IllegalArgumentException(error)

  /** [[uses]] for an already-validated ref. */
  def usesRef(action: ActionRef): StepBuilder = StepBuilder(Step(uses = Some(action.unwrap)))

end StepBuilder
