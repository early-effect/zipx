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

  /** The config-axis prefix of a key, if any, rendered for the sbt CLI: `Docker / publish` → `"Docker/"`,
    * `Compile / test` → `"Compile/"`, an unscoped key → `""`. sbt's slash syntax capitalizes the config name.
    */
  private def configPrefix(key: Scoped): String =
    key.scope.config match
      case sbt.Select(configKey) => configKey.name.capitalize + "/"
      case _                     => "" // This / Zero — no explicit config axis

  /** The CLI suffix for a key on a module: `<label>` or `<Config>/<label>`. */
  private def scopedLabel(key: Scoped): String = s"${configPrefix(key)}${label(key)}"

  /** A per-module command from a task key: `<moduleId>/[<Config>/]<label>` (e.g. `service/Docker/publish`). */
  def moduleCommand(key: Scoped): ModuleNode => String = n => s"${n.id}/${scopedLabel(key)}"

  /** A per-module command that cross-publishes when the module is cross-built (a single `+<id>/…` leg). */
  def crossModuleCommand(key: Scoped): ModuleNode => String =
    n => if n.crossScalaVersions.sizeIs > 1 then s"+${n.id}/${scopedLabel(key)}" else s"${n.id}/${scopedLabel(key)}"

  /** The `cmd"…"` interpolator: write command *syntax* as literal text and splice typed keys with `${key}`.
    *
    * Literal parts are emitted verbatim (so you carry `+`, `++<ver>`, compound `;`, and args), while each `${key}`
    * splice is a real `TaskKey`/`InputKey` — compile-checked and config-aware — rendered **module-scoped** as
    * `<moduleId>/[<Config>/]<label>`, exactly like the built-ins. The result is a `ModuleNode => String` you pass as a
    * capability `command`:
    *
    * {{{
    * cmd"+ \${testFull}"                       // n => s"+\${n.id}/testFull"
    * cmd"++2.13.16; \${legacyClient / publish}" // literal version switch + a module-scoped typed key
    * cmd"\${Docker / publish}"                  // config axis preserved → <id>/Docker/publish
    * }}}
    *
    * Splices are always module-scoped; for an explicitly cross-*project* command, use a plain string/lambda. No macro
    * is involved — the typed splices are just `Scoped` varargs, so a renamed or removed key is a compile error.
    */
  extension (sc: StringContext)
    def cmd(keys: Scoped*): ModuleNode => String =
      n =>
        val rendered = keys.map(k => s"${n.id}/${scopedLabel(k)}")
        // Interleave: part0 splice0 part1 splice1 … partN  (StringContext guarantees parts.size == keys.size + 1).
        sc.parts.iterator
          .zipAll(rendered.iterator, "", "")
          .foldLeft(new StringBuilder) { case (sb, (part, splice)) => sb.append(part).append(splice) }
          .toString

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
    Capability.once(name, scopedLabel(command), phase, gate, runsOn, extraSteps)

end CapabilityTasks
