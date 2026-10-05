package zipx.core

import zipx.workflow.{Step, StepBuilder}

import scala.annotation.targetName

/** A named, composable bundle of steps. It extends `StepContext => List[Step]`, so every lambda field accepts it, and
  * adds what a lambda lacks: a name for diagnostics, `++`, [[when]], and an identity an org can publish:
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
  *   names this bundle in the raw-fragment warning; `++` joins names with `+`.
  * @param rawFragments
  *   escape-hatch text from [[Steps.built]]. A `Step` cannot carry it, so it survives here until `zipxWorkflowGenerate`
  *   warns.
  */
final case class Steps(
    name: String,
    build: StepContext => List[Step],
    rawFragments: List[String] = Nil,
    /** Leaf bundles in `++` order (empty: this is the leaf). `dropExtraSteps` matches these names, not `a+b`. */
    parts: List[Steps] = Nil,
) extends (StepContext => List[Step]):

  def apply(ctx: StepContext): List[Step] = build(ctx)

  def leaves: List[Steps] = if parts.isEmpty then List(this) else parts

  infix def ++(other: Steps): Steps =
    if isVacuous then other
    else if other.isVacuous then this
    else
      Steps(
        s"$name+${other.name}",
        ctx => build(ctx) ++ other(ctx),
        rawFragments ++ other.rawFragments,
        leaves ++ other.leaves,
      )

  /** Keeps this bundle's name. The lambda contributes no `rawFragments`, so prefer a named [[Steps]]. */
  infix def ++(other: StepContext => List[Step]): Steps =
    copy(build = ctx => build(ctx) ++ other(ctx))

  def without(dropName: String): Steps =
    leaves.filterNot(_.name == dropName) match
      case Nil           => Steps.empty
      case single :: Nil => single
      case many          => many.reduce(_ ++ _)

  private def isVacuous: Boolean = this eq Steps.empty

  /** GitHub has no bundle-level `if:`, so this ANDs `condition` into every step's own. */
  def when(condition: JobCondition): Steps =
    val gated = copy(build = ctx => build(ctx).map(Steps.gate(_, condition)))
    if parts.isEmpty then gated else gated.copy(parts = parts.map(_.when(condition)))

  def named(newName: String): Steps = copy(name = newName)

  /** The hook for a cross-cutting tweak: a shared `env:` entry, a `working-directory`. */
  def mapSteps(f: Step => Step): Steps =
    val mapped = copy(build = ctx => build(ctx).map(f))
    if parts.isEmpty then mapped else mapped.copy(parts = parts.map(_.mapSteps(f)))

  /** Declares escape-hatch text for a hand-built step the builders did not produce, so it still reaches the warning. */
  def withRawFragments(fragments: List[String]): Steps = copy(rawFragments = rawFragments ++ fragments)

end Steps

object Steps:

  /** The identity for [[Steps.++]]. */
  val empty: Steps = Steps("empty", _ => Nil)

  /** Needs `@targetName`: this and the case class `apply` both erase to `(String, Function1)`. */
  @targetName("curried")
  def apply(name: String)(build: StepContext => List[Step]): Steps = Steps(name, build)

  def of(name: String)(steps: Step*): Steps = Steps(name, _ => steps.toList)

  /** The form to prefer: the only one that collects the builders' `rawFragments` for the generate-time warning. */
  def built(name: String)(builders: StepBuilder*): Steps =
    Steps(name, _ => builders.toList.map(_.build), builders.toList.flatMap(_.rawFragments))

  /** Collects no `rawFragments`: the builders do not exist until a [[StepContext]] arrives, after the warning runs.
    * Declare them with [[Steps.withRawFragments]].
    */
  def buildingWith(name: String)(build: StepContext => List[StepBuilder]): Steps =
    Steps(name, ctx => build(ctx).map(_.build))

  def one(name: String)(build: StepContext => Step): Steps = Steps(name, ctx => List(build(ctx)))

  def all(bundles: Steps*): Steps = bundles.foldLeft(empty)(_ ++ _)

  /** A bare lambda has nothing to report, so escape hatches inside one are invisible here. */
  def rawWarnings(capabilities: List[Capability], config: PlanConfig): List[String] =
    val bundles        = capabilities.flatMap(c => List(c.extraSteps, c.postSteps)) :+ config.cacheRehydrateExtraSteps
    val bundleWarnings = bundles.collect { case s: Steps => s }.distinct.flatMap { s =>
      s.rawFragments.map(f => s"step bundle '${s.name}' uses a raw escape hatch, which nothing validates: $f")
    }
    bundleWarnings ++ commandWarnings(capabilities, config)

  private def commandWarnings(capabilities: List[Capability], config: PlanConfig): List[String] =
    val capabilityFragments = capabilities.flatMap { c =>
      val fragments = c.command.rawFragments ++ c.sessionTail.toList.flatMap(_.rawFragments)
      fragments.map(f => s"capability '${c.name}' uses an unchecked sbt command: $f")
    }
    val rehydrateFragments =
      config.cacheRehydrateTask.rawFragments.map(f => s"cacheRehydrateTask is an unchecked sbt command: $f")
    (capabilityFragments ++ rehydrateFragments).map(w =>
      s"$w. zipx validates it as text that cannot corrupt the generated file, but not as sbt syntax, so a typo is a " +
        "failing job rather than a compile error"
    )
  end commandWarnings

  /** `unwrapped`, not `render`: in an `if:`, `${{ a }} && ${{ b }}` is a template string, not the conjunction. */
  private def gate(step: Step, condition: JobCondition): Step =
    val added  = condition.expr.unwrapped
    val merged = step.`if` match
      case Some(existing) => s"($existing) && ($added)"
      case None           => added
    step.copy(`if` = Some(merged))

end Steps

/** An extension because `StepBuilder` lives a layer below, in `zipx-workflow`. Top-level so `import zipx.core.*` (what
  * the plugin's `autoImport` re-exports) brings it.
  */
extension (builder: StepBuilder) def when(condition: JobCondition): StepBuilder = builder.when(condition.expr)
