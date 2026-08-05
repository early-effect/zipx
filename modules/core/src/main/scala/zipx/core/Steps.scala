package zipx.core

import zipx.workflow.{Step, StepBuilder}

import scala.annotation.targetName

/** A named, composable bundle of steps: the typed replacement for a bare `StepContext => List[Step]` lambda.
  *
  * `Steps` *extends* `StepContext => List[Step]`, so every field that took a lambda accepts one unchanged. What it adds
  * is what a lambda cannot have: a name for diagnostics, `++` to compose, [[when]] to gate, and a stable identity,
  * which makes an org's shared bundle an ordinary published Scala value:
  *
  * {{{
  * // in a published pack
  * object OrgSteps:
  *   val playwright: Steps = Steps("playwright")(_ => List(Step.run(installBrowsers).named("Install browsers").build))
  *   val aptMirror: Steps  = Steps("apt-mirror")(_ => List(Step.run(pointAtMirror).named("Point apt at mirror").build))
  *
  * // in a consumer build
  * zipxCacheRehydrateExtraSteps := OrgSteps.playwright ++ OrgSteps.aptMirror
  * }}}
  *
  * @param name
  *   names this bundle in the generate-time raw-fragment warning. Composition joins names with `+`, so a warning about
  *   a composed bundle still says which part it came from.
  * @param rawFragments
  *   escape-hatch text in this bundle's scripts, populated by [[Steps.built]]. A `Step` has no field to carry it, so
  *   this type is where it survives long enough for `zipxWorkflowGenerate` to warn.
  */
final case class Steps(
    name: String,
    build: StepContext => List[Step],
    rawFragments: List[String] = Nil,
) extends (StepContext => List[Step]):

  def apply(ctx: StepContext): List[Step] = build(ctx)

  infix def ++(other: Steps): Steps =
    Steps(s"$name+${other.name}", ctx => build(ctx) ++ other(ctx), rawFragments ++ other.rawFragments)

  /** Appends an unnamed lambda, keeping this bundle's name. The lambda contributes no `rawFragments`, since it has
    * nowhere to carry them, which is the practical reason to prefer a named [[Steps]] on both sides.
    */
  infix def ++(other: StepContext => List[Step]): Steps =
    Steps(name, ctx => build(ctx) ++ other(ctx), rawFragments)

  /** ANDs `condition` into every step's `if:`, preserving any condition a step already has. GitHub has no bundle-level
    * `if:`, so gating is per-step by necessity; doing it here means the caller writes the condition once.
    */
  def when(condition: JobCondition): Steps =
    copy(build = ctx => build(ctx).map(Steps.gate(_, condition)))

  def named(newName: String): Steps = copy(name = newName)

  /** The hook for a cross-cutting tweak: a shared `env:` entry, a `working-directory`. */
  def mapSteps(f: Step => Step): Steps = copy(build = ctx => build(ctx).map(f))

  /** Declares escape-hatch text for a hand-built step the builders did not produce, so it still reaches the warning. */
  def withRawFragments(fragments: List[String]): Steps = copy(rawFragments = rawFragments ++ fragments)

end Steps

object Steps:

  /** The identity for [[Steps.++]]. */
  val empty: Steps = Steps("empty", _ => Nil)

  /** `Steps("name")(ctx => …)`.
    *
    * @targetName
    *   because currying does not survive erasure: this and the case class `apply` both erase to `(String, Function1)`.
    */
  @targetName("curried")
  def apply(name: String)(build: StepContext => List[Step]): Steps = Steps(name, build)

  def of(name: String)(steps: Step*): Steps = Steps(name, _ => steps.toList)

  /** The form to prefer: the only one that collects the builders' `rawFragments`, so escape-hatch use in this bundle
    * reaches the generate-time warning instead of going silent.
    */
  def built(name: String)(builders: StepBuilder*): Steps =
    Steps(name, _ => builders.toList.map(_.build), builders.toList.flatMap(_.rawFragments))

  /** `rawFragments` cannot be collected here: the builders do not exist until a [[StepContext]] arrives, and the
    * warning runs before any context does. Declare them with [[Steps.withRawFragments]] instead.
    */
  def buildingWith(name: String)(build: StepContext => List[StepBuilder]): Steps =
    Steps(name, ctx => build(ctx).map(_.build))

  def one(name: String)(build: StepContext => Step): Steps = Steps(name, ctx => List(build(ctx)))

  def all(bundles: Steps*): Steps = bundles.foldLeft(empty)(_ ++ _)

  /** One warning line per raw fragment across every bundle a plan can reach.
    *
    * The `case s: Steps` match is what lambda compatibility costs: a field still accepts a bare function, and a bare
    * function has nothing to report, so escape-hatch use inside a plain lambda is invisible here. That is the incentive
    * to use [[Steps.built]].
    */
  def rawWarnings(capabilities: List[Capability], config: PlanConfig): List[String] =
    val bundles = capabilities.flatMap(c => List(c.extraSteps, c.postSteps)) :+ config.cacheRehydrateExtraSteps
    bundles.collect { case s: Steps => s }.distinct.flatMap { s =>
      s.rawFragments.map(f => s"step bundle '${s.name}' uses a raw escape hatch, which nothing validates: $f")
    }

  /** `unwrapped` rather than `render` because an `if:` is already an expression context: `${{ a }} && ${{ b }}` is a
    * template string that evaluates to neither operand, where `a && b` is the conjunction the caller asked for.
    */
  private def gate(step: Step, condition: JobCondition): Step =
    val added  = condition.expr.unwrapped
    val merged = step.`if` match
      case Some(existing) => s"($existing) && ($added)"
      case None           => added
    step.copy(`if` = Some(merged))

end Steps

/** An extension rather than a method on `StepBuilder`, because `JobCondition` lives here in `zipx-core` and
  * `StepBuilder` a layer below in `zipx-workflow`. Top-level so it arrives with `import zipx.core.*`, which is what the
  * sbt plugin's `autoImport` re-exports.
  */
extension (builder: StepBuilder) def when(condition: JobCondition): StepBuilder = builder.when(condition.expr)
