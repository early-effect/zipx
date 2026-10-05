package zipx.workflow

import neotype.unwrap
import zipx.shell.Script

/** Starting from [[Step.run]] or [[Step.uses]] makes the `uses`/`run` exclusivity a type rather than a check, so
  * [[build]] cannot fail and `withInput` exists only on [[StepBuilder.Uses]].
  */
sealed trait StepBuilder:

  /** So a fluent call on a `uses:` builder stays a `uses:` builder: without it every shared method would widen to
    * `StepBuilder` and `Step.uses(pin).named("Login").withInput(…)` would stop compiling.
    */
  type This <: StepBuilder

  /** Not public: the point of the type is that the only way out is [[build]]. */
  protected def step: Step

  /** Escape-hatch text for the generate-time warning, carried here because [[Step]] has no field for it. */
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

  /** Required on `run:` steps inside a composite action; omit in ordinary workflow jobs. */
  def withShell(shell: String): This = withStep(step.copy(shell = Some(shell)))

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

    def withInput(name: String, value: String): Uses = withStep(step.copy(`with` = step.`with` + (name -> value)))

    /** Pass a `ListMap` to fix the rendered order. */
    def withInputs(inputs: Map[String, String]): Uses = withStep(step.copy(`with` = step.`with` ++ inputs))
  end Uses

  def run(script: Script): Run =
    Run(Step(run = Some(script.render)), script.rawFragments)

  /** **Escape hatch.** Reported as a raw fragment, so `zipxWorkflowGenerate` warns and names the step.
    */
  def runRaw(text: String): Run =
    Run(Step(run = Some(text)), List(text))

  /** `Step.uses("actions/checkout")` is a compile error naming the missing `@ref`. */
  inline def uses(inline action: String): Uses = usesRef(ActionRef(action))

  /** For genuinely untrusted text (a workflow file, a setting). An `ActionPins` field is already validated where the
    * pin file is parsed, so use [[usesRef]] there.
    */
  def usesMake(action: String): Either[String, Uses] =
    ActionRef.make(action).map(usesRef)

  def usesRef(action: ActionRef): Uses = Uses(Step(uses = Some(action)))

end StepBuilder
