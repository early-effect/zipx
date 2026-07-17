package zipx.sbt

import sbt.*
import zipx.core.{Capability, Gate, ModuleNode, Ordering, Phase, StepContext, Target}
import zipx.workflow.Step

/** Typed, IDE-friendly ways to specify a capability's sbt command from a real `TaskKey`/`InputKey` instead of a string.
  *
  * A capability command is ultimately a string typed at the sbt shell in CI (`sbt '<command>'`), and the pure
  * `zipx-core` model keeps it as `ModuleNode => String` — that's what lets the planner stay sbt-free and expresses
  * things a single key can't (cross `+`, aliases, compound `a; b`). These helpers live in the plugin (which has sbt on
  * the classpath) and render a key to that string form, giving code-completion and compile-time checking for the common
  * "one task" case. They compose with every `Capability` constructor via the `command`/`buildCommand` arguments.
  *
  * A key renders to `<moduleId>/<label>` (the same shape the built-ins produce), or just `<label>` for a build-wide
  * (`Once`) command. Scoping beyond the project axis (config/task axes, args, `+`) still needs a string — by design.
  */
object CapabilityTasks:

  /** The sbt CLI label of a key (its attribute-key name), e.g. `scalafmtCheckAll`, `publish`, `test`. */
  private def label(key: Scoped): String = key.key.label

  /** A per-module command from a task key: `<moduleId>/<label>`. Use as a `command` for per-module capabilities. */
  def moduleCommand(key: Scoped): ModuleNode => String = n => s"${n.id}/${label(key)}"

  /** A per-module command that cross-publishes when the module is cross-built (a single `+<id>/<label>` leg). */
  def crossModuleCommand(key: Scoped): ModuleNode => String =
    n => if n.crossScalaVersions.sizeIs > 1 then s"+${n.id}/${label(key)}" else s"${n.id}/${label(key)}"

  // ---- Typed constructors mirroring Capability.{deploy,custom,once} but taking a key for the command ----

  /** [[Capability.deploy]] with the deploy command given as a task key (rendered `<module>/<label>`). */
  def deploy(
    participates: ModuleNode => Boolean,
    command: Scoped,
    targets: ModuleNode => List[Target],
    name: String = "deploy",
    needsCapabilities: List[String] = List("docker"),
    permissions: Map[String, String] = Map.empty,
  ): Capability =
    Capability.deploy(participates, moduleCommand(command), targets, name, needsCapabilities, permissions)

  /** [[Capability.custom]] with the command given as a task key (rendered `<module>/<label>`). */
  def custom(
    name: String,
    command: Scoped,
    participates: ModuleNode => Boolean = _ => true,
    phase: Phase = Phase.Publish,
    ordering: Ordering = Ordering.DependencyOrdered,
    gate: Gate = Gate.OnReleaseTag,
    matrixed: Boolean = false,
    targets: ModuleNode => List[Target] = _ => Nil,
    needsCapabilities: List[String] = Nil,
    permissions: Map[String, String] = Map.empty,
    runsOn: Option[List[String]] = None,
    extraSteps: StepContext => List[Step] = _ => Nil,
  ): Capability =
    Capability.custom(name, moduleCommand(command), participates, phase, ordering, gate, matrixed, targets,
                      needsCapabilities, permissions, runsOn, extraSteps)

  /** [[Capability.once]] with the single build-wide command given as a task key (rendered as its bare `<label>`). */
  def once(
    name: String,
    command: Scoped,
    phase: Phase = Phase.Verify,
    gate: Gate = Gate.Always,
    runsOn: Option[List[String]] = None,
    extraSteps: StepContext => List[Step] = _ => Nil,
  ): Capability =
    Capability.once(name, label(command), phase, gate, runsOn, extraSteps)

end CapabilityTasks
