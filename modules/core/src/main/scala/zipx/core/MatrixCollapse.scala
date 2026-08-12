package zipx.core

import zipx.workflow.{Expr, MatrixAxis}

import scala.collection.immutable.ListMap

/** How a capability's sibling fan-out is folded into a GitHub Actions `strategy.matrix` for a quieter workflow graph.
  *
  *   - [[MatrixCollapse.Off]]: one job per Graph module, one Aggregate/Layer job per target.
  *   - [[MatrixCollapse.Auto]] (default): collapse when legs are structurally safe (simple matrix or `matrix.include`);
  *     otherwise emit expanded jobs without failing generate.
  *   - [[MatrixCollapse.Strict]]: collapse only when legs are independent and isomorphic; otherwise generate fails.
  *   - [[MatrixCollapse.Coarse]]: collapse even when that drops Graph same-capability `needs` (GHA cannot express
  *     per-leg needs); still errors on non-isomorphic templates.
  *
  * Resolution is cascading: [[Capability.matrixCollapse]] wins over [[PlanConfig.matrixCollapse]], else
  * [[MatrixCollapse.Auto]].
  */
enum MatrixCollapse:
  case Off, Auto, Strict, Coarse

object MatrixCollapse:

  /** Capability override, then plan allowlist, else [[PlanConfig.defaultMatrixCollapse]] ([[MatrixCollapse.Auto]]). */
  def effective(capability: Capability, config: PlanConfig): MatrixCollapse =
    capability.matrixCollapse
      .orElse(config.matrixCollapse.get(capability.name))
      .getOrElse(config.defaultMatrixCollapse)

  /** Generate-time notes for [[MatrixCollapse.Coarse]] Graph collapses that drop same-capability inter-module `needs`.
    */
  def warnings(capabilities: List[Capability], graph: ModuleGraph, config: PlanConfig): List[String] =
    capabilities.flatMap { c =>
      effective(c, config) match
        case Coarse if c.scope == CapabilityScope.Graph =>
          val nodes = graph.nodes.filter(c.participates)
          Option.when(hasSameCapInterModuleNeeds(c, nodes, graph))(
            s"capability '${c.name}' is MatrixCollapse.Coarse: same-capability inter-module needs are dropped " +
              "(GitHub Actions cannot express per-matrix-leg needs). Legs start together."
          )
        case _ => None
    }

  /** True when any participating module would `needs` another participant's same-capability job. */
  def hasSameCapInterModuleNeeds(capability: Capability, nodes: List[ModuleNode], graph: ModuleGraph): Boolean =
    nodes.exists { node =>
      val upstream = capability.ordering match
        case Ordering.ParallelWithUpstream =>
          graph.directDeps(node.id).flatMap(graph.get).filter(capability.participates)
        case Ordering.DependencyOrdered =>
          nearestParticipatingAncestors(node, graph, capability)
      upstream.nonEmpty
    }

  /** Soft feasibility for [[Auto]]: collapse Graph only when needs would not be dropped and templates are isomorphic.
    */
  def graphCollapseFeasible(capability: Capability, graph: ModuleGraph): Boolean =
    val nodes = graph.nodes.filter(capability.participates)
    if nodes.isEmpty then false
    else if hasSameCapInterModuleNeeds(capability, nodes, graph) then false
    else
      val targetSets = nodes.map(n => capability.targets(n).map(_.name).sorted)
      if targetSets.distinct.sizeIs > 1 then false
      else
        val targets = nodes.headOption.toList.flatMap(capability.targets)
        targetsCompatible(targets).isRight && isomorphicMatrixCommands(capability, nodes).isRight
  end graphCollapseFeasible

  private def nearestParticipatingAncestors(
      node: ModuleNode,
      graph: ModuleGraph,
      capability: Capability,
  ): List[ModuleNode] =
    def go(frontier: List[String], found: List[ModuleNode], seen: Set[String]): List[ModuleNode] =
      frontier match
        case Nil    => found
        case h :: t =>
          val deps                         = graph.directDeps(h).filterNot(seen)
          val (participating, passthrough) =
            deps.partition(d => graph.get(d).exists(capability.participates))
          go(
            passthrough ++ t,
            found ++ participating.flatMap(id => graph.get(id)),
            seen ++ deps,
          )
    go(List(node.id), Nil, Set.empty)
  end nearestParticipatingAncestors

  /** Rewrite module-scoped [[SbtStep.Task]]s to [[TaskScope.MatrixModule]]. */
  def underMatrixModule(node: ModuleNode, command: SbtCommand): Either[String, SbtCommand] =
    val id      = node.id
    var matched = false
    val next    = command.steps.map {
      case SbtStep.Task(label, TaskScope.Module(mid), cross) if mid == id =>
        matched = true
        SbtStep.Task(label, TaskScope.MatrixModule, cross)
      case other => other
    }
    if matched then Right(SbtCommand.fromSteps(next))
    else
      Left(
        s"command '${command.text}' is not parametric in module '$id' " +
          s"(expected a Task scoped to that module)"
      )
  end underMatrixModule

  def isomorphicMatrixCommands(capability: Capability, nodes: List[ModuleNode]): Either[String, SbtCommand] =
    nodes
      .foldLeft[Either[String, List[SbtCommand]]](Right(Nil)) { (acc, n) =>
        acc.flatMap(cmds => underMatrixModule(n, capability.command.commandFor(n)).map(cmds :+ _))
      }
      .flatMap { matrixCommands =>
        matrixCommands.distinct match
          case List(one) => Right(one)
          case other     =>
            Left(
              s"module commands are not isomorphic under matrix.module (${other.map(_.text).mkString(" vs ")})"
            )
      }

  /** How target fan-out should be encoded once collapse is chosen. */
  enum TargetMatrix:
    case Simple
    case Include

  /** Prefer a cartesian `matrix.target` list; fall back to `matrix.include` when environment / env values differ. */
  def targetsCompatible(targets: List[Target]): Either[String, TargetMatrix] =
    if targets.isEmpty then Right(TargetMatrix.Simple)
    else
      targetsAllowSimpleMatrix(targets) match
        case Right(_) => Right(TargetMatrix.Simple)
        case Left(_)  => targetsAllowIncludeMatrix(targets).map(_ => TargetMatrix.Include)

  /** Targets are simple-matrix-safe when environment is absent or equals the target name (so
    * `environment: $${{ matrix.target }}` works without `matrix.include`).
    */
  def targetsAllowSimpleMatrix(targets: List[Target]): Either[String, Unit] =
    val badEnv = targets.filter(t => t.environment.exists(_ != (t.name: String)))
    if badEnv.nonEmpty then
      Left(
        s"targets ${badEnv.map(_.name).mkString(", ")} have environment names that differ from the target name; " +
          "use matrix.include (or Auto) when environment != target name"
      )
    else sharedTargetShape(targets)

  /** Include-safe: same env keys and same condition shape; environment names and env values may differ. */
  def targetsAllowIncludeMatrix(targets: List[Target]): Either[String, Unit] =
    sharedTargetShape(targets).flatMap { _ =>
      val badAxes = targets.flatMap(_.env.keys).distinct.filter(k => MatrixAxis.make(k).isLeft)
      if badAxes.nonEmpty then
        Left(
          s"target env keys ${badAxes.mkString(", ")} are not valid matrix axis names; refuse matrix.include collapse"
        )
      else Right(())
    }

  private def sharedTargetShape(targets: List[Target]): Either[String, Unit] =
    if targets.exists(_.condition.isDefined) && targets.map(_.condition).distinct.sizeIs > 1 then
      Left("targets have differing conditions; refuse matrix collapse")
    else
      val envKeys = targets.map(_.env.keySet).distinct
      if envKeys.sizeIs > 1 then Left("targets have differing env keys; refuse matrix collapse")
      else Right(())

  /** Env block for a simple target-collapsed job: keys whose values equal the target name become `matrix.target`. */
  def collapsedTargetEnv(targets: List[Target]): Map[String, EnvValue] =
    targets.headOption match
      case None         => Map.empty
      case Some(sample) =>
        sample.env.map { (k, v) =>
          val useMatrix =
            targets.forall(t => t.env.get(k).exists(_.render == (t.name: String)))
          k -> (if useMatrix then EnvValue.typed(Expr.matrix("target")) else v)
        }

  /** Env block for an include-collapsed job: every key is `${{ matrix.<key> }}`. */
  def collapsedIncludeEnv(targets: List[Target]): Map[String, EnvValue] =
    targets.headOption.toList
      .flatMap(_.env.keys)
      .map { k =>
        val axis = MatrixAxis.make(k).fold(err => sys.error(s"zipx: include env axis '$k': $err"), identity)
        k -> EnvValue.typed(Expr.Matrix(axis))
      }
      .toMap

  /** `matrix.include` rows for module × target (omit `module` when `modules` is empty). */
  def includeRows(modules: List[String], targets: List[Target]): List[Map[String, String]] =
    val moduleLegs = if modules.isEmpty then List[Option[String]](None) else modules.map(Some(_))
    for
      mod <- moduleLegs
      t   <- targets
    yield
      val base = ListMap.newBuilder[String, String]
      mod.foreach(m => base += "module" -> m)
      base += "target" -> (t.name: String)
      t.environment.foreach(e => base += "environment" -> e)
      t.env.foreach { (k, v) => base += k -> v.render }
      base.result()
    end for
  end includeRows

end MatrixCollapse
