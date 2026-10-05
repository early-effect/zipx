package zipx.plugin

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

/** Capability commands from real `TaskKey`/`InputKey`s instead of strings, for completion and compile checking.
  *
  * [[zipx.core.SbtCommand]] stays text (validated not to corrupt the generated file, never parsed as sbt syntax) so
  * core stays sbt-free and still expresses cross `+`, aliases, and compound `a; b`. A key renders to
  * `<moduleId>/<label>`, or `<label>` for a build-wide (`Once`) command; anything beyond the project axis goes through
  * `cmd"…"`.
  */
object CapabilityTasks:

  private def label(key: Scoped): String = key.key.label

  /** sbt's slash syntax capitalizes the config name: `Docker / publish` renders `Docker/`. */
  private def configPrefix(key: Scoped): String =
    key.scope.config match
      case sbt.Select(configKey) => configKey.name.capitalize + "/"
      case _                     => ""

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

  /** Renders `<moduleId>/[<Config>/]<label>`, e.g. `service/Docker/publish`. */
  def moduleCommand(key: Scoped): ModuleNode => SbtCommand = n => SbtCommand.module(n, scopedLabel(key))

  /** A per-module command that cross-publishes when the module is cross-built (a single `+<id>/…` leg). */
  def crossModuleCommand(key: Scoped): ModuleNode => SbtCommand = n => SbtCommand.crossModule(n, scopedLabel(key))

  /** A key the sbt CLI can run. SettingKeys are rejected: `sbt 'someSetting'` only prints. */
  type RunnableKey = TaskKey[?] | InputKey[?]
  type SessionPart = RunnableKey | sbt.Command | SbtCommand | Seq[SbtCommand]

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

  extension (cap: Capability)
    def running(key: RunnableKey): Capability          = cap.running(of(key))
    def runningEach(key: RunnableKey): Capability      = cap.runningEach(of(key))
    def runningEachCross(key: RunnableKey): Capability = cap.runningEachCross(of(key))
    def thenOnce(key: RunnableKey): Capability         = cap.thenOnce(of(key))
    def thenOnce(command: sbt.Command): Capability     = cap.thenOnce(of(command))

  def renderSplice(x: Any, n: ModuleNode): String = x match
    case k: Scoped => s"${n.id}/${scopedLabelText(k)}"
    case s: String => s
    case other     => other.toString // unreachable: the macro rejects other types at compile time

  /** The `cmd"…"` interpolator's runtime half. No splice depends on the module, so all text but the module id is
    * validated once here. Each piece must be `ShText` (the `SbtCommand` rule minus non-emptiness, which is checked on
    * the whole). This runs while a `build.sbt` setting evaluates, so `sys.error` fails the build naming the bad text.
    */
  def commandFrom(parts: List[String], splices: List[Any]): ModuleNode => SbtCommand =
    val literalPieces = parts ++ splices.collect { case s: String => s }
    literalPieces.foreach { piece =>
      ShText.make(piece).left.foreach(error => sys.error(s"""zipx: invalid cmd"…" text "$piece": $error"""))
    }
    if literalPieces.forall(_.isEmpty) && !splices.exists(_.isInstanceOf[Scoped]) then
      sys.error("""zipx: cmd"…" produced an empty sbt command""")
    // Safe: every literal piece is ShText, ids and key labels add only safe characters, and the result is non-empty.
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

  /** Command syntax as literal text, with typed keys or strings spliced via `$`.
    *
    * Literal parts are emitted verbatim (`+`, `++<ver>`, `;`, args). A key splice is compile-checked and renders
    * module-scoped as `<moduleId>/[<Config>/]<label>`; a `String` splice is verbatim. Any other splice type is a
    * compile error, so a renamed key fails to compile.
    *
    * {{{
    * cmd"+ \${testFull}"                          // n => s"+\${n.id}/testFull"
    * cmd"++\${scalaV}; \${legacyClient / publish}" // String splice + a module-scoped typed key (mixed)
    * cmd"\${Docker / publish}"                     // config axis preserved → <id>/Docker/publish
    * }}}
    *
    * Splices are always module-scoped; for an explicitly cross-project command, use a plain string or lambda.
    */
  extension (inline sc: StringContext)
    inline def cmd(inline args: Any*): ModuleNode => SbtCommand =
      ${ cmdMacro('sc, 'args) }

  private def cmdMacro(sc: Expr[StringContext], args: Expr[Seq[Any]])(using Quotes): Expr[ModuleNode => SbtCommand] =
    import quotes.reflect.*
    val spliceExprs: Seq[Expr[Any]] = args match
      case Varargs(es) => es
      case _           => report.errorAndAbort("cmd\"…\" requires literal splices", args)
    spliceExprs.foreach { e =>
      val tpe = e.asTerm.tpe.widen
      if !(tpe <:< TypeRepr.of[Scoped] || tpe <:< TypeRepr.of[String]) then
        report.errorAndAbort(
          s"cmd\"…\" splices must be a TaskKey/InputKey or a String; got ${tpe.show}",
          e,
        )
    }
    // Checking lives in `commandFrom`, plain Scala that can be tested; the macro only hands off.
    '{ CapabilityTasks.commandFrom(${ sc }.parts.toList, ${ Varargs(spliceExprs) }.toList) }
  end cmdMacro

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

  /** [[zipx.core.Capability.once]] with its build-wide command given as a task key, rendered as the bare `<label>`. */
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
