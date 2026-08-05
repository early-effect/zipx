package zipx.workflow

import neotype.unwrap
import zipx.shell.Script

/** A [[Step]] under construction, with typed fields.
  *
  * [[Step]] itself stays a flat all-optional case class: its shape is fixed by `derives Schema` and the on-disk
  * mapping, and changing it would move bytes. Validity is closed from both ends instead. This is the good end: a
  * builder starts from either [[Step.run]] or [[Step.uses]], so the mutually-exclusive pair is decided before any other
  * field is set, and it cannot be made to produce a step with both or neither. [[Step.validate]] is the other end, a
  * pure check for a step that was hand-built or decoded around the builder.
  *
  * The `run:`/`uses:` split is a *type*, not a check, which is what makes [[build]] total: it returns a `Step` and has
  * no failure case to report. The one rule that a single builder type could only have caught at runtime is that `with:`
  * belongs to `uses:` (on a `run:` step GitHub silently ignores it), and [[withInput]] exists only on
  * [[StepBuilder.Uses]], so writing it on a `run:` step does not compile.
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
sealed trait StepBuilder:

  /** This builder's own type, so a fluent call on a `uses:` builder stays a `uses:` builder and keeps
    * [[StepBuilder.Uses.withInput]]. Without it every shared method would widen to `StepBuilder` and
    * `Step.uses(pin).named("Login").withInput(…)` would stop compiling.
    */
  type This <: StepBuilder

  /** The step as built so far. Not public: the point of the type is that the only way out is [[build]]. */
  protected def step: Step

  /** Escape-hatch text in this step's script, for the generate-time warning. */
  def rawFragments: List[String]

  protected def withStep(updated: Step): This

  /** The step's `name:`, which is what shows up in the Actions UI. */
  def named(name: String): This = withStep(step.copy(name = Some(name)))

  /** The step's `id:`, so a later step can read `steps.<id>.outputs.<name>`. Validated at compile time for a literal.
    */
  inline def withId(inline id: String): This = withStepId(StepId(id))

  /** [[withId]] for an already-validated id. */
  def withStepId(id: StepId): This = withStep(step.copy(id = Some(id.unwrap)))

  /** The step's `if:`, rendered bare (see [[Expr.unwrapped]]): an `if:` is already an expression context. */
  def when(condition: Expr): This = withStep(step.copy(`if` = Some(condition.unwrapped)))

  /** One step-scoped `env:` entry. Validated at compile time for a literal name. */
  inline def withEnv(inline name: String, value: Expr): This = withEnvName(EnvName(name), value)

  /** [[withEnv]] for an already-validated name. */
  def withEnvName(name: EnvName, value: Expr): This =
    withStep(step.copy(env = step.env + (name.unwrap -> value.render)))

  /** Several `env:` entries at once, already rendered. Pass a `ListMap` to fix the order. */
  def withEnvs(entries: Map[String, String]): This = withStep(step.copy(env = step.env ++ entries))

  /** The step's `working-directory:`. */
  def in(workingDirectory: String): This = withStep(step.copy(workingDirectory = Some(workingDirectory)))

  /** The finished step.
    *
    * Total: every way of reaching a builder fixes exactly one of `run:`/`uses:`, and the only field rule a builder
    * could violate is enforced by [[StepBuilder.Uses]] owning `withInput`. There is nothing left to report.
    */
  def build: Step = step

end StepBuilder

object StepBuilder:

  /** A `run:` step. Has no `withInput`, because GitHub ignores `with:` on a `run:` step. */
  final case class Run(protected val step: Step, rawFragments: List[String] = Nil) extends StepBuilder:
    type This = Run
    protected def withStep(updated: Step): Run = copy(step = updated)

  /** A `uses:` step, the only kind that takes `with:` inputs. */
  final case class Uses(protected val step: Step, rawFragments: List[String] = Nil) extends StepBuilder:
    type This = Uses
    protected def withStep(updated: Step): Uses = copy(step = updated)

    /** One `with:` input. Values accumulate in call order, which is what keeps the rendered YAML deterministic. */
    def withInput(name: String, value: Expr): Uses = withInput(name, value.render)

    /** One `with:` input with a literal value, for the many action inputs that are plain data (`fetch-depth`, a path).
      */
    def withInput(name: String, value: String): Uses = withStep(step.copy(`with` = step.`with` + (name -> value)))

    /** Several `with:` inputs at once. Pass a `ListMap` to fix the order. */
    def withInputs(inputs: Map[String, String]): Uses = withStep(step.copy(`with` = step.`with` ++ inputs))
  end Uses

  /** A `run:` step from a typed script. */
  def run(script: Script): Run =
    Run(Step(run = Some(script.render)), script.rawFragments)

  /** **Escape hatch.** A `run:` step from verbatim text.
    *
    * Present because `CacheEpoch.Script` and consumer builds still pass hand-written shell, and because a build that
    * cannot express something should not be blocked. The text is reported as a raw fragment, so `zipxWorkflowGenerate`
    * warns and names the step; prefer [[run]], whose script cannot produce a line YAML will mangle.
    */
  def runRaw(text: String): Run =
    Run(Step(run = Some(text)), List(text))

  /** A `uses:` step from a literal action ref, checked during compilation.
    *
    * `Step.uses("actions/checkout")` is a compile error naming the missing `@ref`, so there is no failure value to
    * handle and no build to run before the mistake surfaces. That is the form a build writes, which is why it is the
    * one that gets the short name.
    *
    * [[usesMake]] is the sibling for a ref that arrives as runtime data, which is the normal case *inside* zipx: an
    * `ActionPins` field is read from the pin file at build time and cannot reach an `inline` check.
    */
  inline def uses(inline action: String): Uses = usesRef(ActionRef(action))

  /** [[uses]] for a ref that arrives as runtime data (an `ActionPins` field, a setting). `Left` carries [[ActionRef]]'s
    * own message.
    */
  def usesMake(action: String): Either[String, Uses] =
    ActionRef.make(action).map(usesRef)

  /** [[uses]] for an already-validated ref. */
  def usesRef(action: ActionRef): Uses = Uses(Step(uses = Some(action.unwrap)))

end StepBuilder
