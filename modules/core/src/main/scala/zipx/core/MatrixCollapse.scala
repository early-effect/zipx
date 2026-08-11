package zipx.core

/** How a capability's sibling fan-out is folded into a GitHub Actions `strategy.matrix` for a quieter workflow graph.
  *
  *   - [[MatrixCollapse.Off]]: today's emission (one job per Graph module, one Aggregate/Layer job per target).
  *   - [[MatrixCollapse.Strict]]: collapse only when legs are independent and isomorphic; otherwise generate fails.
  *   - [[MatrixCollapse.Coarse]]: collapse even when that drops Graph same-capability `needs` (GHA cannot express
  *     per-leg needs); still errors on non-isomorphic templates.
  *
  * Resolution is cascading: [[Capability.matrixCollapse]] wins over [[PlanConfig.matrixCollapse]], else
  * [[MatrixCollapse.Off]].
  */
enum MatrixCollapse:
  case Off, Strict, Coarse

object MatrixCollapse:

  /** Capability override, then plan allowlist, else [[MatrixCollapse.Off]]. */
  def effective(capability: Capability, config: PlanConfig): MatrixCollapse =
    capability.matrixCollapse
      .orElse(config.matrixCollapse.get(capability.name))
      .getOrElse(Off)

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

  /** Targets are collapse-safe when environment is absent or equals the target name (so
    * `environment: $${{ matrix.target }}` works without `matrix.include`).
    */
  def targetsAllowSimpleMatrix(targets: List[Target]): Either[String, Unit] =
    val badEnv = targets.filter(t => t.environment.exists(_ != (t.name: String)))
    if badEnv.nonEmpty then
      Left(
        s"targets ${badEnv.map(_.name).mkString(", ")} have environment names that differ from the target name; " +
          "matrix collapse needs environment == target name (or no environment) without matrix.include"
      )
    else if targets.exists(_.condition.isDefined) && targets.map(_.condition).distinct.sizeIs > 1 then
      Left("targets have differing conditions; refuse matrix collapse")
    else
      val envKeys = targets.map(_.env.keySet).distinct
      if envKeys.sizeIs > 1 then Left("targets have differing env keys; refuse matrix collapse")
      else
        val keys = envKeys.headOption.getOrElse(Set.empty)
        val bad  =
          targets.filter(t => keys.exists(k => t.env.get(k).exists(v => v.render != (t.name: String))))
        if bad.nonEmpty && keys.nonEmpty then
          // Allow env values that are identical across targets, or equal to the target name.
          val identical =
            keys.forall(k => targets.map(_.env.get(k).map(_.render)).distinct.sizeIs == 1)
          if identical then Right(())
          else if bad.nonEmpty then
            Left(
              s"target env values for ${bad.map(_.name).mkString(", ")} are neither identical nor equal to the " +
                "target name; refuse matrix collapse"
            )
          else Right(())
        else Right(())
        end if
      end if
    end if
  end targetsAllowSimpleMatrix

  /** Env block for a target-collapsed job: keys whose values equal the target name become `matrix.target`. */
  def collapsedTargetEnv(targets: List[Target]): Map[String, EnvValue] =
    targets.headOption match
      case None         => Map.empty
      case Some(sample) =>
        sample.env.map { (k, v) =>
          val useMatrix =
            targets.forall(t => t.env.get(k).exists(_.render == (t.name: String)))
          k -> (if useMatrix then EnvValue.typed(zipx.workflow.Expr.matrix("target")) else v)
        }

end MatrixCollapse
