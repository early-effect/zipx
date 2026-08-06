package zipx.workflow

import neotype.unwrap
import zipx.shell.Script

/** A [[Step]] under construction, with typed fields.
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
  * This is the good end of [[Step]]'s validity: a builder starts from either [[Step.run]] or [[Step.uses]], so the
  * mutually-exclusive pair is a *type* rather than a check, [[build]] has no failure case, and `withInput` exists only
  * on [[StepBuilder.Uses]]. [[Step.validate]] is the other end, for a step that was hand-built or decoded around the
  * builder.
  */
sealed trait StepBuilder:

  /** So a fluent call on a `uses:` builder stays a `uses:` builder: without it every shared method would widen to
    * `StepBuilder` and `Step.uses(pin).named("Login").withInput(…)` would stop compiling.
    */
  type This <: StepBuilder

  /** Not public: the point of the type is that the only way out is [[build]]. */
  protected def step: Step

  /** Escape-hatch text in this step's script, for the generate-time warning. [[Step]] has no field to carry it. */
  def rawFragments: List[String]

  protected def withStep(updated: Step): This

  def named(name: String): This = withStep(step.copy(name = Some(name)))

  inline def withId(inline id: String): This = withStepId(StepId(id))

  def withStepId(id: StepId): This = withStep(step.copy(id = Some(id.unwrap)))

  /** Rendered bare (see [[Expr.unwrapped]]), because an `if:` is already an expression context. */
  def when(condition: Expr): This = withStep(step.copy(`if` = Some(condition.unwrapped)))

  inline def withEnv(inline name: String, value: Expr): This = withEnvName(EnvName(name), value)

  def withEnvName(name: EnvName, value: Expr): This =
    withStep(step.copy(env = step.env + (name.unwrap -> value.render)))

  /** Pass a `ListMap` to fix the rendered order. */
  def withEnvs(entries: Map[String, String]): This = withStep(step.copy(env = step.env ++ entries))

  def in(workingDirectory: String): This = withStep(step.copy(workingDirectory = Some(workingDirectory)))

  def build: Step = step

end StepBuilder

object StepBuilder:

  final case class Run(protected val step: Step, rawFragments: List[String] = Nil) extends StepBuilder:
    type This = Run
    protected def withStep(updated: Step): Run = copy(step = updated)

  /** The only kind of step that takes `with:` inputs: GitHub silently ignores them on a `run:` step. */
  final case class Uses(protected val step: Step, rawFragments: List[String] = Nil) extends StepBuilder:
    type This = Uses
    protected def withStep(updated: Step): Uses = copy(step = updated)

    def withInput(name: String, value: Expr): Uses = withInput(name, value.render)

    /** For the many action inputs that are plain data: `fetch-depth`, a path. */
    def withInput(name: String, value: String): Uses = withStep(step.copy(`with` = step.`with` + (name -> value)))

    /** Pass a `ListMap` to fix the rendered order. */
    def withInputs(inputs: Map[String, String]): Uses = withStep(step.copy(`with` = step.`with` ++ inputs))
  end Uses

  def run(script: Script): Run =
    Run(Step(run = Some(script.render)), script.rawFragments)

  /** **Escape hatch.** A `run:` step from verbatim text, for shell a build cannot yet express through [[run]]. Reported
    * as a raw fragment, so `zipxWorkflowGenerate` warns and names the step.
    */
  def runRaw(text: String): Run =
    Run(Step(run = Some(text)), List(text))

  /** `Step.uses("actions/checkout")` is a compile error naming the missing `@ref`. That is the form a build writes,
    * which is why it gets the short name.
    */
  inline def uses(inline action: String): Uses = usesRef(ActionRef(action))

  /** For a ref that is genuinely untrusted text: read from a workflow file, or typed into a setting. An `ActionPins`
    * field is *not* one of those, since a pin is validated where the pin file is parsed; use [[usesRef]] there and
    * carry no `Either` a consumer would have to fake a failure for.
    */
  def usesMake(action: String): Either[String, Uses] =
    ActionRef.make(action).map(usesRef)

  /** The normal case inside zipx: the ref is already an [[ActionRef]], so there is nothing left to check. */
  def usesRef(action: ActionRef): Uses = Uses(Step(uses = Some(action)))

end StepBuilder
