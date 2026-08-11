package zipx.sbt

import sbt.*
import zipx.core.{
  Capability,
  CapabilityName,
  EnvValue,
  Gate,
  JobCondition,
  ModuleId,
  ModuleNode,
  Ordering,
  Phase,
  SbtCommand,
  StepContext,
  Target,
  TargetFanOut,
}
import zipx.shell.ShText
import zipx.workflow.JobService
import zipx.workflow.Step
import scala.quoted.*

/** Typed, IDE-friendly ways to specify a capability's sbt command from a real `TaskKey`/`InputKey` instead of a string.
  *
  * A capability command is ultimately text typed at the sbt shell in CI (`sbt '<command>'`), which the pure `zipx-core`
  * model keeps as an [[zipx.core.SbtCommand]]: validated as *text that cannot corrupt the generated file*, not parsed
  * as sbt syntax, which is what lets the planner stay sbt-free while still expressing what a single key cannot (cross
  * `+`, aliases, compound `a; b`). These helpers live in the plugin (which has sbt on the classpath) and render a key
  * into that form, giving code-completion and compile-time checking for the common "one task" case. They compose with
  * every `Capability` constructor via the `command` argument.
  *
  * A key renders to `<moduleId>/<label>` (the same shape the built-ins produce), or just `<label>` for a build-wide
  * (`Once`) command. Scoping beyond the project axis (args, `+`, compound commands) goes through the `cmd"…"`
  * interpolator or [[zipx.core.SbtCommand]]'s own combinators.
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
      case _                     => "" // This / Zero, no explicit config axis

  /** The CLI suffix for a key on a module, as text: `<label>` or `<Config>/<label>` (no project axis). */
  private def scopedLabelText(key: Scoped): String = s"${configPrefix(key)}${label(key)}"

  /** Honour the project axis: an explicit `core / publish` stays scoped to `core`, not re-prefixed by zipx. */
  private def projectTaskScope(key: Scoped): zipx.core.TaskScope =
    key.scope.project match
      case This | Zero => zipx.core.TaskScope.Unscoped
      case Select(ref) =>
        ref match
          case LocalProject(id) =>
            zipx.core.TaskScope.Module(ModuleId.make(id).fold(err => sys.error(s"zipx: $err"), identity))
          case ProjectRef(_, id) =>
            zipx.core.TaskScope.Module(ModuleId.make(id).fold(err => sys.error(s"zipx: $err"), identity))
          case ThisProject | LocalRootProject | _: RootProject =>
            sys.error(
              s"zipx: key '${scopedLabelText(key)}' uses a project axis (${ref.getClass.getSimpleName}) with no " +
                "stable id until the build resolves; name the project value (e.g. core / publish) or leave the axis off"
            )
          case ThisBuild | _: BuildRef =>
            sys.error(
              s"zipx: key '${scopedLabelText(key)}' uses a build-level project axis; that is not a project a command " +
                "runs in. Use a project-scoped key or leave the axis off."
            )
          case other =>
            sys.error(s"zipx: unsupported project axis ${other.getClass.getSimpleName} on '${scopedLabelText(key)}'")
      case axis =>
        sys.error(s"zipx: unsupported project ScopeAxis ${axis.getClass.getSimpleName} on '${scopedLabelText(key)}'")

  /** Key as an unscoped-or-explicitly-scoped Task step (config axis in the label). */
  private def scopedLabel(key: Scoped): SbtCommand =
    SbtCommand.fromSteps(
      List(
        zipx.core.SbtStep.Task(
          zipx.core.SbtCommandText.unsafeMake(scopedLabelText(key)),
          projectTaskScope(key),
          cross = false,
        )
      )
    )

  /** A per-module command from a task key: `<moduleId>/[<Config>/]<label>` (e.g. `service/Docker/publish`). */
  def moduleCommand(key: Scoped): ModuleNode => SbtCommand = n => SbtCommand.module(n, scopedLabel(key))

  /** A per-module command that cross-publishes when the module is cross-built (a single `+<id>/…` leg). */
  def crossModuleCommand(key: Scoped): ModuleNode => SbtCommand = n => SbtCommand.crossModule(n, scopedLabel(key))

  /** A key the sbt CLI can run. SettingKeys are rejected: `sbt 'someSetting'` only prints. */
  type RunnableKey = TaskKey[?] | InputKey[?]
  type SessionPart = RunnableKey | sbt.Command | SbtCommand | Seq[SbtCommand]

  /** Render a runnable key to an [[SbtCommand]] (honours project + config axes). */
  def of(key: RunnableKey): SbtCommand = key match
    case k: Scoped => scopedLabel(k)

  /** A declared sbt [[sbt.Command]] by its registered name. */
  def of(command: sbt.Command): SbtCommand =
    command.nameOption match
      case Some(name) => SbtCommand.unsafeCommand(name)
      case None       =>
        sys.error("zipx: sbt.Command has no nameOption; use zipxTasks.of(taskKey) or SbtCommand.raw")

  /** Join keys and commands into one session, in order. */
  def session(first: SessionPart, rest: SessionPart*): SbtCommand =
    def parts(p: SessionPart): List[SbtCommand] = p match
      case c: SbtCommand  => List(c)
      case c: sbt.Command => List(of(c))
      case s: Seq[?]      => s.toList.collect { case c: SbtCommand => c }
      case k: Scoped      => List(of(k.asInstanceOf[RunnableKey]))
    val all = parts(first) ++ rest.toList.flatMap(parts)
    all match
      case h :: t => SbtCommand.session(h, t*)
      case Nil    => sys.error("zipx: session requires at least one command")
  end session

  /** One command per project ref running `task` (e.g. `matrix.projectRefs` from sbt-projectmatrix). */
  def rows(refs: Seq[ProjectRef], task: RunnableKey): List[SbtCommand] =
    refs.toList.map { ref =>
      val id = ModuleId.make(ref.project).fold(e => sys.error(s"zipx: $e"), identity)
      SbtCommand.module(ModuleNode(id = id), of(task))
    }

  /** Same task for each project. */
  def each(projects: Seq[Project], task: RunnableKey): List[SbtCommand] =
    projects.toList.map { p =>
      val id = ModuleId.make(p.id).fold(e => sys.error(s"zipx: $e"), identity)
      SbtCommand.module(ModuleNode(id = id), of(task))
    }

  def only(projects: ProjectReference*): ModuleNode => Boolean =
    val ids = projects.flatMap(refIds).toSet
    n => ids.contains(n.id: String)

  def except(projects: ProjectReference*): ModuleNode => Boolean =
    val ids = projects.flatMap(refIds).toSet
    n => !ids.contains(n.id: String)

  /** Participate filter for every row of a project matrix (`matrix.projectRefs`). */
  def onlyRows(refs: Seq[ProjectRef]): ModuleNode => Boolean =
    val ids = refs.map(_.project).toSet
    n => ids.contains(n.id: String)

  private def refIds(ref: ProjectReference): List[String] = ref match
    case LocalProject(id)  => List(id)
    case ProjectRef(_, id) => List(id)
    case _                 =>
      sys.error(s"zipx: only/except need a named project reference; got ${ref.getClass.getSimpleName}")

  /** Capability helpers that take real keys. */
  extension (cap: Capability)
    def running(key: RunnableKey): Capability          = cap.running(of(key))
    def runningEach(key: RunnableKey): Capability      = cap.runningEach(of(key))
    def runningEachCross(key: RunnableKey): Capability = cap.runningEachCross(of(key))
    def thenOnce(key: RunnableKey): Capability         = cap.thenOnce(of(key))
    def thenOnce(command: sbt.Command): Capability     = cap.thenOnce(of(command))

  /** Render one splice for the `cmd"…"` interpolator against a module. A `Scoped` (task/input key) renders
    * module-scoped and config-aware (`<id>/[<Config>/]<label>`); a `String` passes through verbatim (so you can splice
    * a computed version, path, etc.). Called by the [[cmd]] macro with statically-checked argument types.
    */
  def renderSplice(x: Any, n: ModuleNode): String = x match
    case k: Scoped => s"${n.id}/${scopedLabelText(k)}"
    case s: String => s
    case other     => other.toString // unreachable: the macro rejects other types at compile time

  /** The `cmd"…"` interpolator's runtime half: validate everything the caller wrote, then return a *total* function.
    *
    * Validation happens here rather than per module because it can: a splice is a key or a plain `String`, neither of
    * which depends on the `ModuleNode`, so every character of the result except the module id is known now. The id is a
    * [[zipx.core.ModuleId]] and a key label is an sbt `AttributeKey` label, so both are already safe.
    *
    * `ShText` is the per-piece rule: no newline, no carriage return, no control characters, which is exactly what an
    * [[zipx.core.SbtCommand]] forbids, minus the non-emptiness that applies to the whole and not to a part. Non-empty
    * is therefore checked separately, and a key splice satisfies it by rendering `<id>/<label>`.
    *
    * `sys.error` is the sbt boundary's way of reporting, and this runs while a `build.sbt` setting is being evaluated,
    * so the build fails naming the offending text rather than generating a workflow around it.
    */
  def commandFrom(parts: List[String], splices: List[Any]): ModuleNode => SbtCommand =
    val literalPieces = parts ++ splices.collect { case s: String => s }
    literalPieces.foreach { piece =>
      ShText.make(piece).left.foreach(error => sys.error(s"""zipx: invalid cmd"…" text "$piece": $error"""))
    }
    if literalPieces.forall(_.isEmpty) && !splices.exists(_.isInstanceOf[Scoped]) then
      sys.error("""zipx: cmd"…" produced an empty sbt command""")
    // Total: every piece above is ShText, a module id and a key label add only safe characters, and the check above
    // established that the result is non-empty.
    n => SbtCommand.unsafeBuilt(interleave(parts, splices.map(renderSplice(_, n))))
  end commandFrom

  private def interleave(parts: List[String], splices: List[String]): String =
    val sb = new StringBuilder
    val it = splices.iterator
    parts.foreach { part =>
      sb.append(part)
      if it.hasNext then sb.append(it.next())
    }
    sb.toString

  /** The `cmd"…"` interpolator: write command *syntax* as literal text and splice typed keys (or strings) with `$`.
    *
    * Literal parts are emitted verbatim (so you carry `+`, `++<ver>`, compound `;`, and args). Each `${…}` splice is
    * dispatched by its **static type**:
    *   - a `TaskKey`/`InputKey` (`Scoped`) is compile-checked, config-aware, and rendered **module-scoped** as
    *     `<moduleId>/[<Config>/]<label>`, exactly like the built-ins;
    *   - a `String` is spliced verbatim (a computed version, path, secret ref, …).
    *
    * A macro enforces that every splice is one of those two types (any other is a compile error) and dispatches
    * statically, so a renamed/removed key fails to compile. The result is a `ModuleNode => SbtCommand` for a capability
    * `command`, validated once when the setting is evaluated rather than per module; see [[commandFrom]]:
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
    inline def cmd(inline args: Any*): ModuleNode => SbtCommand =
      ${ cmdMacro('sc, 'args) }

  private def cmdMacro(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[ModuleNode => SbtCommand] =
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
    // Validation and interleaving both live in `commandFrom`, so this generates only the hand-off. That keeps the
    // checking in ordinary Scala where it can be read and tested, rather than in generated code.
    '{ CapabilityTasks.commandFrom(${ sc }.parts.toList, ${ Varargs(spliceExprs) }.toList) }
  end cmdMacro

  // ---- Typed constructors mirroring Capability.{deploy,custom,once} but taking a key for the command ----

  /** [[zipx.core.Capability.deploy]] (Aggregate-by-target) with the deploy command given as a task key. */
  def deploy(
      participates: ModuleNode => Boolean,
      command: Scoped,
      targets: ModuleNode => List[Target],
      name: CapabilityName = Capability.DeployName,
      needsCapabilities: List[CapabilityName] = List(Capability.DockerName),
      permissions: Map[String, String] = Map.empty,
      env: Map[String, EnvValue] = Map.empty,
      gate: Gate = Gate.OnReleaseTag,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability.deploy(
      participates,
      moduleCommand(command),
      targets,
      name,
      needsCapabilities,
      permissions,
      env,
      gate,
      condition,
    )

  /** [[zipx.core.Capability.deployGraph]] with the deploy command given as a task key. */
  def deployGraph(
      participates: ModuleNode => Boolean,
      command: Scoped,
      targets: ModuleNode => List[Target],
      name: CapabilityName = Capability.DeployName,
      needsCapabilities: List[CapabilityName] = List(Capability.DockerName),
      permissions: Map[String, String] = Map.empty,
      env: Map[String, EnvValue] = Map.empty,
      gate: Gate = Gate.OnReleaseTag,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability.deployGraph(
      participates,
      moduleCommand(command),
      targets,
      name,
      needsCapabilities,
      permissions,
      env,
      gate,
      condition,
    )

  /** [[zipx.core.Capability.custom]] with the command given as a task key (rendered `<module>/<label>`). */
  def custom(
      name: CapabilityName,
      command: Scoped,
      participates: ModuleNode => Boolean = _ => true,
      phase: Phase = Phase.Publish,
      ordering: Ordering = Ordering.DependencyOrdered,
      gate: Gate = Gate.OnReleaseTag,
      matrixed: Boolean = false,
      targets: ModuleNode => List[Target] = _ => Nil,
      targetFanOut: TargetFanOut = TargetFanOut.JobPerTarget,
      needsCapabilities: List[CapabilityName] = Nil,
      permissions: Map[String, String] = Map.empty,
      runsOn: Option[List[String]] = None,
      extraSteps: StepContext => List[Step] = _ => Nil,
      env: Map[String, EnvValue] = Map.empty,
      container: Option[String] = None,
      services: Map[String, JobService] = Map.empty,
      condition: Option[JobCondition] = None,
  ): Capability =
    // Named arguments throughout, so a new `Capability.custom` parameter cannot silently shift the ones after it.
    Capability.custom(
      name = name,
      command = moduleCommand(command),
      participates = participates,
      phase = phase,
      ordering = ordering,
      gate = gate,
      matrixed = matrixed,
      targets = targets,
      targetFanOut = targetFanOut,
      needsCapabilities = needsCapabilities,
      permissions = permissions,
      runsOn = runsOn,
      extraSteps = extraSteps,
      env = env,
      container = container,
      services = services,
      condition = condition,
    )

  /** [[zipx.core.Capability.once]] with the single build-wide command given as a task key (rendered as its bare
    * `<label>`).
    */
  def once(
      name: CapabilityName,
      command: Scoped,
      phase: Phase = Phase.Verify,
      gate: Gate = Gate.Always,
      runsOn: Option[List[String]] = None,
      extraSteps: StepContext => List[Step] = _ => Nil,
      env: Map[String, EnvValue] = Map.empty,
      needsCapabilities: List[CapabilityName] = Nil,
      container: Option[String] = None,
      services: Map[String, JobService] = Map.empty,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability.once(
      name = name,
      command = scopedLabel(command),
      phase = phase,
      gate = gate,
      runsOn = runsOn,
      extraSteps = extraSteps,
      env = env,
      needsCapabilities = needsCapabilities,
      container = container,
      services = services,
      condition = condition,
    )

end CapabilityTasks
