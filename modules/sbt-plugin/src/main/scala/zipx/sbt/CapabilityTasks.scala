package zipx.sbt

import sbt.*
import zipx.core.{Capability, EnvValue, Gate, ModuleNode, Ordering, Phase, StepContext, Target}
import zipx.workflow.Step
import scala.quoted.*

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

  /** Render one splice for the `cmd"…"` interpolator against a module. A `Scoped` (task/input key) renders
    * module-scoped and config-aware (`<id>/[<Config>/]<label>`); a `String` passes through verbatim (so you can splice
    * a computed version, path, etc.). Called by the [[cmd]] macro with statically-checked argument types.
    */
  def renderSplice(x: Any, n: ModuleNode): String = x match
    case k: Scoped => s"${n.id}/${scopedLabel(k)}"
    case s: String => s
    case other     => other.toString // unreachable — the macro rejects other types at compile time

  /** The `cmd"…"` interpolator: write command *syntax* as literal text and splice typed keys (or strings) with `$`.
    *
    * Literal parts are emitted verbatim (so you carry `+`, `++<ver>`, compound `;`, and args). Each `${…}` splice is
    * dispatched by its **static type**:
    *   - a `TaskKey`/`InputKey` (`Scoped`) is compile-checked, config-aware, and rendered **module-scoped** as
    *     `<moduleId>/[<Config>/]<label>` — exactly like the built-ins;
    *   - a `String` is spliced verbatim (a computed version, path, secret ref, …).
    *
    * A macro enforces that every splice is one of those two types (any other is a compile error) and dispatches
    * statically, so a renamed/removed key fails to compile. The result is a `ModuleNode => String` for a capability
    * `command`:
    *
    * {{{
    * cmd"+ \${testFull}"                          // n => s"+\${n.id}/testFull"
    * cmd"++\${scalaV}; \${legacyClient / publish}" // String splice + a module-scoped typed key (mixed)
    * cmd"\${Docker / publish}"                     // config axis preserved → <id>/Docker/publish
    * }}}
    *
    * Splices are always module-scoped; for an explicitly cross-*project* command, use a plain string/lambda.
    */
  extension (inline sc: StringContext)
    inline def cmd(inline args: Any*): ModuleNode => String =
      ${ cmdMacro('sc, 'args) }

  private def cmdMacro(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[ModuleNode => String] =
    import quotes.reflect.*
    val spliceExprs: Seq[Expr[Any]] = args match
      case Varargs(es) => es
      case _           => report.errorAndAbort("cmd\"…\" requires literal splices", args)
    // Validate each splice's static type is Scoped or String; keep the checked Expr for code-gen.
    spliceExprs.foreach { e =>
      val tpe = e.asTerm.tpe.widen
      if !(tpe <:< TypeRepr.of[Scoped] || tpe <:< TypeRepr.of[String]) then
        report.errorAndAbort(
          s"cmd\"…\" splices must be a TaskKey/InputKey or a String; got ${tpe.show}",
          e,
        )
    }
    // Build: (n: ModuleNode) => sc.parts interleaved with renderSplice(arg, n) for each splice.
    '{ (n: ModuleNode) =>
      val parts   = ${ sc }.parts.iterator
      val splices = ${ Varargs(spliceExprs) }.iterator.map(a => CapabilityTasks.renderSplice(a, n))
      val sb      = new StringBuilder
      while parts.hasNext do
        sb.append(parts.next())
        if splices.hasNext then sb.append(splices.next())
      sb.toString
    }
  end cmdMacro

  // ---- Typed constructors mirroring Capability.{deploy,custom,once} but taking a key for the command ----

  /** [[Capability.deploy]] with the deploy command given as a task key (rendered `<module>/<label>`). */
  def deploy(
      participates: ModuleNode => Boolean,
      command: Scoped,
      targets: ModuleNode => List[Target],
      name: String = "deploy",
      needsCapabilities: List[String] = List("docker"),
      permissions: Map[String, String] = Map.empty,
      env: Map[String, EnvValue] = Map.empty,
  ): Capability =
    Capability.deploy(participates, moduleCommand(command), targets, name, needsCapabilities, permissions, env)

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
      env: Map[String, EnvValue] = Map.empty,
  ): Capability =
    Capability.custom(
      name,
      moduleCommand(command),
      participates,
      phase,
      ordering,
      gate,
      matrixed,
      targets,
      needsCapabilities,
      permissions,
      runsOn,
      extraSteps,
      env = env,
    )

  /** [[Capability.once]] with the single build-wide command given as a task key (rendered as its bare `<label>`). */
  def once(
      name: String,
      command: Scoped,
      phase: Phase = Phase.Verify,
      gate: Gate = Gate.Always,
      runsOn: Option[List[String]] = None,
      extraSteps: StepContext => List[Step] = _ => Nil,
      env: Map[String, EnvValue] = Map.empty,
      needsCapabilities: List[String] = Nil,
  ): Capability =
    Capability.once(
      name,
      scopedLabel(command),
      phase,
      gate,
      runsOn,
      extraSteps,
      env = env,
      needsCapabilities = needsCapabilities,
    )

end CapabilityTasks
