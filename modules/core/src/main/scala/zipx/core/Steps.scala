package zipx.core

import zipx.workflow.{Step, StepBuilder}

import scala.annotation.targetName

/** A named, composable bundle of steps: the typed replacement for a bare `StepContext => List[Step]` lambda.
  *
  * `Steps` *is* a `StepContext => List[Step]`, which is the whole trick. Every field that took a lambda
  * ([[Capability.extraSteps]], [[Capability.postSteps]], [[PlanConfig.cacheRehydrateExtraSteps]], the
  * `zipxCacheRehydrateExtraSteps` setting) accepts one with no signature change and no consumer breakage, and
  * `Planner.stepsFor` needs no edit at all. What the type adds over the lambda is everything a lambda cannot have: a
  * name for diagnostics, `++` to compose, [[when]] to gate, and a stable identity to publish.
  *
  * Publishing is the point. `zipx-core` is on Maven Central, so an org's shared bundle is an ordinary published Scala
  * value:
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
  * That is what issue #46 was reaching for: a step bundle that lives somewhere other than inline in `build.sbt`,
  * without the string splicing that a YAML resource file would have relocated rather than removed.
  *
  * @param name
  *   what this bundle is, for the generate-time raw-fragment warning and for error messages. Composition joins names
  *   with `+`, so a failure in a composed bundle still says where it came from.
  * @param rawFragments
  *   escape-hatch text in this bundle's scripts. Populated by [[Steps.built]] from the builders' own fragments, since a
  *   `Step` has no field to carry it: this type is where the information survives long enough for
  *   `zipxWorkflowGenerate` to warn and name the bundle.
  */
final case class Steps(
    name: String,
    build: StepContext => List[Step],
    rawFragments: List[String] = Nil,
) extends (StepContext => List[Step]):

  def apply(ctx: StepContext): List[Step] = build(ctx)

  /** This bundle's steps followed by `other`'s. */
  infix def ++(other: Steps): Steps =
    Steps(s"$name+${other.name}", ctx => build(ctx) ++ other(ctx), rawFragments ++ other.rawFragments)

  /** Append a plain function, for a lambda that has not been named yet. Keeps this bundle's name.
    *
    * A plain function reports no fragments, because there is nowhere for it to carry them. That is the practical reason
    * to prefer a named [[Steps]] on both sides.
    */
  infix def ++(other: StepContext => List[Step]): Steps =
    Steps(name, ctx => build(ctx) ++ other(ctx), rawFragments)

  /** AND `condition` into every step's `if:`, so a whole bundle is gated as one unit.
    *
    * A step that already has an `if:` keeps it, ANDed with `condition`. GitHub has no bundle-level `if:`, so gating is
    * per-step by necessity; doing it here means the caller writes the condition once.
    */
  def when(condition: JobCondition): Steps =
    copy(build = ctx => build(ctx).map(Steps.gate(_, condition)))

  /** Rename, for a bundle assembled from parts whose joined name is not what you want to read in a warning. */
  def named(newName: String): Steps = copy(name = newName)

  /** Map over the produced steps: the hook for a cross-cutting tweak (a shared `env:` entry, a `working-directory`). */
  def mapSteps(f: Step => Step): Steps = copy(build = ctx => build(ctx).map(f))

  /** Declare escape-hatch text this bundle carries, for a hand-built step the builders did not produce. */
  def withRawFragments(fragments: List[String]): Steps = copy(rawFragments = rawFragments ++ fragments)

end Steps

object Steps:

  /** An empty bundle, the identity for [[Steps.++]]. */
  val empty: Steps = Steps("empty", _ => Nil)

  /** `Steps("name")(ctx => …)`, the curried form that reads best at a definition site.
    *
    * @targetName
    *   because currying does not survive erasure: this and the case class `apply` both erase to `(String, Function1)`.
    */
  @targetName("curried")
  def apply(name: String)(build: StepContext => List[Step]): Steps = Steps(name, build)

  /** A bundle of context-independent steps. */
  def of(name: String)(steps: Step*): Steps = Steps(name, _ => steps.toList)

  /** A bundle from builders, so a definition site never has to call `.build` itself.
    *
    * The form to prefer: it is the only one that can collect the builders' `rawFragments`, so escape-hatch use in this
    * bundle reaches the generate-time warning instead of going silent.
    */
  def built(name: String)(builders: StepBuilder*): Steps =
    Steps(name, _ => builders.toList.map(_.build), builders.toList.flatMap(_.rawFragments))

  /** A context-dependent bundle from builders. `rawFragments` cannot be collected here: the builders do not exist until
    * a [[StepContext]] arrives, and the warning runs before any context does. Use [[Steps.withRawFragments]] to declare
    * them explicitly when a context-dependent bundle uses an escape hatch.
    */
  def buildingWith(name: String)(build: StepContext => List[StepBuilder]): Steps =
    Steps(name, ctx => build(ctx).map(_.build))

  /** A single-step bundle whose step depends on the context (a module id, a target name, an action pin). */
  def one(name: String)(build: StepContext => Step): Steps = Steps(name, ctx => List(build(ctx)))

  /** Concatenate bundles, keeping order. */
  def all(bundles: Steps*): Steps = bundles.foldLeft(empty)(_ ++ _)

  /** Escape-hatch warnings for every bundle a plan can reach, one line per raw fragment.
    *
    * The match on `case s: Steps` is what the lambda-compatible design costs and buys: a field still accepts a bare
    * function, and a bare function simply has nothing to report, so only bundles that opted into the type are
    * inspected. A plain lambda using an escape hatch is invisible here, which is the honest incentive to use
    * [[Steps.built]].
    */
  def rawWarnings(capabilities: List[Capability], config: PlanConfig): List[String] =
    val bundles = capabilities.flatMap(c => List(c.extraSteps, c.postSteps)) :+ config.cacheRehydrateExtraSteps
    bundles.collect { case s: Steps => s }.distinct.flatMap { s =>
      s.rawFragments.map(f => s"step bundle '${s.name}' uses a raw escape hatch, which nothing validates: $f")
    }

  /** AND a condition into one step's `if:`, preserving any condition already there.
    *
    * `unwrapped` rather than `render` because an `if:` is already an expression context: `${{ a }} && ${{ b }}` is a
    * template string that evaluates to neither operand, where `a && b` is the conjunction the caller asked for.
    */
  private def gate(step: Step, condition: JobCondition): Step =
    val added  = condition.expr.unwrapped
    val merged = step.`if` match
      case Some(existing) => s"($existing) && ($added)"
      case None           => added
    step.copy(`if` = Some(merged))

end Steps

/** `StepBuilder.when` for a [[JobCondition]].
  *
  * An extension rather than a method on `StepBuilder`, because `JobCondition` lives here in `zipx-core` and
  * `StepBuilder` a layer below in `zipx-workflow`; the dependency only points one way. Top-level so it arrives with
  * `import zipx.core.*`, which is what the sbt plugin's `autoImport` re-exports.
  */
extension (builder: StepBuilder) def when(condition: JobCondition): StepBuilder = builder.when(condition.expr)
