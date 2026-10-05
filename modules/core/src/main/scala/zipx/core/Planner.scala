package zipx.core

import neotype.unwrap
import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Maps a [[ModuleGraph]] + capabilities + [[PlanConfig]] to a GitHub Actions [[zipx.workflow.Workflow]], with no sbt
  * dependency. Env maps are merged plan → cache → capability → target, so a target wins every clash.
  */
object Planner:

  // Job ids are built from `Names.ActionsId` segments (capability, target, module) joined by `-`, which is again an
  // `ActionsId`, so no job id here can fail validation.

  def jobId(capability: Capability, moduleId: ModuleId): JobId = capability.name.jobId(moduleId)

  def jobId(capability: Capability, moduleId: ModuleId, target: Target): JobId =
    capability.name.jobId(moduleId, target.name)

  def aggregateTargetJobId(capability: Capability, target: Target): JobId =
    capability.name.jobId(target.name)

  /** `L<index>` is an ASCII letter followed by digits, so it is an `ActionsId` segment like the others. */
  def layerJobId(capability: Capability, layerIndex: Int): JobId =
    capability.name.jobId(s"L$layerIndex")

  def layerTargetJobId(capability: Capability, layerIndex: Int, target: Target): JobId =
    capability.name.jobId(s"L$layerIndex", target.name)

  /** Every job id a capability produces, so `needs` can name them. Must match [[plan]] under the same
    * [[MatrixCollapse.effective]] mode: an Auto collapse that is not feasible yields the expanded ids.
    */
  def allJobIds(capability: Capability, graph: ModuleGraph, config: PlanConfig = PlanConfig()): List[JobId] =
    val mode = MatrixCollapse.effective(capability, config)
    capability.scope match
      case CapabilityScope.Once      => List(capability.name.asJobId)
      case CapabilityScope.Aggregate =>
        distinctFannedTargets(capability, graph) match
          case Nil                                             => List(capability.name.asJobId)
          case targets if collapsesTargetFanOut(mode, targets) => List(capability.name.asJobId)
          case targets                                         => targets.map(t => aggregateTargetJobId(capability, t))
      case CapabilityScope.Layer =>
        val layers = graph.subsetLayers(capability.participates)
        distinctFannedTargets(capability, graph) match
          case Nil => layers.indices.map(i => layerJobId(capability, i)).toList
          case targets if collapsesTargetFanOut(mode, targets) =>
            layers.indices.map(i => layerJobId(capability, i)).toList
          case targets =>
            (for
              i <- layers.indices
              t <- targets
            yield layerTargetJobId(capability, i, t)).toList
      case CapabilityScope.Graph =>
        val expanded = graph.nodes
          .filter(capability.participates)
          .flatMap(node => jobIdsForGraph(capability, node))
          .distinct
          .sorted
        if collapsesGraph(mode, capability, graph) then List(capability.name.asJobId)
        else expanded
    end match
  end allJobIds

  /** Same predicate the Graph emitter uses before calling [[graphMatrixJobs]]. */
  private def collapsesGraph(mode: MatrixCollapse, capability: Capability, graph: ModuleGraph): Boolean =
    mode match
      case MatrixCollapse.Off                            => false
      case MatrixCollapse.Auto                           => MatrixCollapse.graphCollapseFeasible(capability, graph)
      case MatrixCollapse.Strict | MatrixCollapse.Coarse => true

  /** Same soft-fail predicate Aggregate / Layer use for target collapse under Auto. */
  private def collapsesTargetFanOut(mode: MatrixCollapse, targets: List[Target]): Boolean =
    mode match
      case MatrixCollapse.Off                            => false
      case MatrixCollapse.Auto                           => MatrixCollapse.targetsCompatible(targets).isRight
      case MatrixCollapse.Strict | MatrixCollapse.Coarse => true

  private def jobIdsForGraph(capability: Capability, node: ModuleNode): List[JobId] =
    fannedTargets(capability, node) match
      case Nil     => List(jobId(capability, node.id))
      case targets => targets.sortBy(_.name).map(t => jobId(capability, node.id, t))

  /** `Nil` for [[TargetFanOut.SharedJob]]: its destinations share the untargeted job and its ids, which keeps a
    * `needsCapabilities` edge onto it correct without callers knowing about fan-out.
    */
  private def fannedTargets(capability: Capability, node: ModuleNode): List[Target] =
    capability.targetFanOut match
      case TargetFanOut.JobPerTarget => capability.targets(node)
      case TargetFanOut.SharedJob    => Nil

  /** The complement of [[fannedTargets]]: for a capability with targets, exactly one of the two is non-empty. */
  private def sharedTargets(capability: Capability, node: ModuleNode): List[Target] =
    capability.targetFanOut match
      case TargetFanOut.JobPerTarget => Nil
      case TargetFanOut.SharedJob    => capability.targets(node).sortBy(_.name)

  private def distinctFannedTargets(capability: Capability, graph: ModuleGraph): List[Target] =
    capability.targetFanOut match
      case TargetFanOut.JobPerTarget => distinctTargets(capability, graph)
      case TargetFanOut.SharedJob    => Nil

  /** First-seen wins per name, so two modules defining `prod` differently still produce one `prod` job. */
  private def distinctTargets(capability: Capability, graph: ModuleGraph): List[Target] =
    val seen = scala.collection.mutable.LinkedHashMap.empty[TargetName, Target]
    for
      moduleId <- graph.topologicalSort
      node     <- graph.get(moduleId).toList
      if capability.participates(node)
      t <- capability.targets(node)
    do if !seen.contains(t.name) then seen(t.name) = t
    seen.values.toList.sortBy(_.name)

  private def participants(capability: Capability, graph: ModuleGraph): List[ModuleNode] =
    graph.topologicalSort.flatMap(graph.get).filter(capability.participates)

  /** One sbt session per job, the point of the Aggregate and Layer scopes. */
  private def joinCommands(capability: Capability, nodes: List[ModuleNode]): Option[SbtCommand] =
    if !capability.command.runsSbt then None
    else SbtCommand.join(nodes.map(n => capability.command.commandFor(n)))

  private def cacheForCommand(config: PlanConfig, hasCommand: Boolean): CacheContribution =
    if hasCommand then cacheContribution(config) else CacheContribution()

  private def validateCapabilities(capabilities: List[Capability], graph: ModuleGraph, config: PlanConfig): Unit =
    capabilities.filter(_.gate == Gate.AffectedOnly) match
      case Nil => ()
      case bad =>
        sys.error(
          s"zipx: Gate.AffectedOnly is not implemented, so capabilities ${bad.map(_.name).sorted.mkString(", ")} " +
            "would silently run on every event. Affected-gating is controlled by zipxAffectedOnPR / " +
            "zipxAffectedOnPush / zipxAffectedPublish / zipxAffectedDeploy on Graph capabilities, not by Gate. Use " +
            "Gate.Always (Verify capabilities are affected-gated automatically, Publish and Deploy ones under " +
            "zipxAffectedPublish / zipxAffectedDeploy) or Gate.OnReleaseTag."
        )
    end match
    // `ModuleGraph.cycle` rather than `make`: these are capabilities, so the error has to name them as such.
    ModuleGraph
      .cycle(capabilities.map(c => c.name -> c.needsCapabilities).toMap)
      .foreach(involved => sys.error(s"zipx: needsCapabilities cycle among ${involved.mkString(", ")}"))

    // Same `jobs` key as the roll-up: one would silently replace the other.
    capabilities.filter(_.name == CapabilityName("verify")) match
      case Nil => ()
      case _   =>
        sys.error(
          "zipx: capability name 'verify' is reserved for the Verify roll-up job. Rename the capability. " +
            "Require that one check in the ruleset; it needs every Verify job."
        )
    capabilities.foreach(validateWorkflowCall)
    capabilities.foreach(c => validateSharedTargets(c, graph))
    capabilities.foreach(c => validateSatisfiable(c, graph, config))
    capabilities.foreach(validateSessionTail)
    validateSkipConsumers(capabilities, config)
    capabilities.filter(Coverage.instruments).foreach(validateCoverage)
    validateLocalCacheOwner(capabilities, graph)
    capabilities.foreach(c => c.affectedBy.foreach(validateAffectedBy(c, _, graph)))
  end validateCapabilities

  private def validateAffectedBy(capability: Capability, modules: ModuleNode => Boolean, graph: ModuleGraph): Unit =
    if capability.scope != CapabilityScope.Once then
      sys.error(
        s"zipx: capability '${capability.name}' is ${capability.scope}-scoped with withAffectedBy; only a Once job " +
          "needs it, since Graph jobs are already gated per module"
      )
    if !graph.nodes.exists(modules) then
      sys.error(
        s"zipx: capability '${capability.name}' withAffectedBy matches no module, so it would run only when the diff " +
          "cannot narrow anything; name the modules whose changes it tests"
      )
  end validateAffectedBy

  private def validateCoverage(capability: Capability): Unit =
    if capability.name == Capability.TestName then
      sys.error(
        s"zipx: capability '${capability.name}' runs scoverage, so every PR would wait on an instrumented build. " +
          "Keep the builtin test and measure coverage in its own workflow: zipxCoverageWorkflow := " +
          "Some(Coverage.workflow(CoverageTrigger.Dispatch, CoverageTrigger.prLabel(\"coverage\"))). " +
          "Coverage.once() under its default name also still runs in ci.yml."
      )
    if capability.localCache == LocalCacheMode.Save then
      sys.error(
        s"zipx: capability '${capability.name}' runs scoverage and has LocalCacheMode.Save, so the build snapshot " +
          "that test and image jobs restore would hold instrumented classes. Coverage restores the snapshot and " +
          "never saves it: drop .withLocalCache(LocalCacheMode.Save)."
      )
  end validateCoverage

  /** Two owners race each other's entries, and an owner spanning several jobs writes one entry per job: the eviction
    * [[LocalCacheMode]] exists to stop.
    */
  private def validateLocalCacheOwner(capabilities: List[Capability], graph: ModuleGraph): Unit =
    val owners = capabilities.filter(_.localCache == LocalCacheMode.Save)
    if owners.sizeIs > 1 then
      sys.error(
        s"zipx: LocalCacheMode.Save is set on ${owners.map(_.name).sorted.mkString(", ")}. One capability saves the " +
          "build snapshot each run and every other job restores it. Keep Save on the test capability and set " +
          "LocalCacheMode.Restore on the rest."
      )
    owners.foreach { c =>
      val spread =
        if c.scope == CapabilityScope.Graph then Some("is Graph-scoped")
        else if c.matrixed then Some("is matrixed")
        else if distinctFannedTargets(c, graph).nonEmpty then Some("fans out one job per target")
        else None
      spread.foreach(why =>
        sys.error(
          s"zipx: capability '${c.name}' has LocalCacheMode.Save but $why, so each of its jobs would save its own " +
            "entry. Add .withLocalCache(LocalCacheMode.Restore) to it and keep Save on a single-session test " +
            "capability (Capability.test, testJoined, or testLayers)."
        )
      )
    }
  end validateLocalCacheOwner

  private def validateSessionTail(capability: Capability): Unit =
    capability.sessionTail.foreach { tail =>
      val text                                    = tail.text: String
      def fail(why: String, fix: String): Nothing =
        sys.error(
          s"zipx: capability '${capability.name}' has sessionTail '$text' but $why. $fix"
        )
      if capability.workflowCall.isDefined then
        fail("it is a workflow_call job (no sbt session)", "Drop thenOnce, or use a non-workflowCall capability")
      capability.command match
        case CommandSource.ActionsOnly =>
          fail("it is ActionsOnly (nothing to append to)", "Use running(...) or once(...), or drop thenOnce")
        case _ => ()
      capability.scope match
        case CapabilityScope.Layer =>
          fail(
            "Layer runs one session per wave, so the tail would release a partial bundle per wave",
            "Use Aggregate (or ZipxCentral.release) / Once",
          )
        case CapabilityScope.Graph =>
          fail(
            "Graph runs one session per module, so the tail would run once per module",
            "Use Aggregate / Once",
          )
        case CapabilityScope.Aggregate | CapabilityScope.Once => ()
      end match
    }
  end validateSessionTail

  /** Rejects an Aggregate or Layer capability that needs an affected-gated Graph one: `tolerateSkips` would run it
    * beside a skipped producer, and a joined session cannot drop one module's command. A Graph consumer skips with its
    * producer, and a Once consumer names no module, so neither is refused.
    */
  private def validateSkipConsumers(capabilities: List[Capability], config: PlanConfig): Unit =
    val gatedGraphNames =
      capabilities
        .filter(c => c.scope == CapabilityScope.Graph && affectedGated(c, config))
        .map(_.name)
        .toSet

    // Verify is always gated but produces no artifact; counting it would refuse every build that needs `test`.
    val optInGatedNames =
      capabilities
        .filter(c => gatedGraphNames.contains(c.name) && c.phase != Phase.Verify)
        .map(_.name)
        .toSet

    for
      consumer <- capabilities
      if consumer.scope == CapabilityScope.Aggregate || consumer.scope == CapabilityScope.Layer
      producer <- consumer.needsCapabilities.filter(optInGatedNames.contains).sorted
    do
      val flag = capabilities.find(_.name == producer).map(_.phase) match
        case Some(Phase.Deploy) => "zipxAffectedDeploy"
        case _                  => "zipxAffectedPublish"
      sys.error(
        s"zipx: capability '${consumer.name}' is ${consumer.scope} and needs '$producer', which $flag " +
          s"lets skip per module. One '$producer' job skipping would leave '${consumer.name}' running against an " +
          "artifact nobody built, so this is refused rather than generated. Fixes, in order of preference: give " +
          s"'${consumer.name}' CapabilityScope.Graph so it skips with its own '$producer' job; make its command " +
          s"resolve a moving tag that a skipped '$producer' cannot invalidate; or turn $flag off."
      )
    end for
  end validateSkipConsumers

  private def validateWorkflowCall(capability: Capability): Unit =
    if capability.workflowCall.isDefined then
      val offending =
        List(
          Option.when(capability.container.isDefined)("container"),
          Option.when(capability.services.nonEmpty)("services"),
        ).flatten
      if offending.nonEmpty then
        sys.error(
          s"zipx: capability '${capability.name}' sets both workflowCall and ${offending.mkString(" and ")}, which " +
            "GitHub rejects: a `uses:` job runs the called workflow's own jobs, so it has no runtime of its own to " +
            "configure. Declare them in the called workflow, or drop workflowCall to run steps here."
        )

  /** One shared job has one `if:` and one Environment: dropping a per-destination one or applying it to the whole job
    * would both be silently wrong.
    */
  private def validateSharedTargets(capability: Capability, graph: ModuleGraph): Unit =
    if capability.targetFanOut == TargetFanOut.SharedJob then
      val targets = graph.nodes.filter(capability.participates).flatMap(capability.targets).distinctBy(_.name)
      def refuse(target: Target, field: String): Nothing =
        sys.error(
          s"zipx: capability '${capability.name}' target '${target.name}' sets $field, which one shared job cannot " +
            "honor per destination. Use TargetFanOut.JobPerTarget (the default) when destinations need their own " +
            s"$field, or drop it and gate the whole job with Capability.condition."
        )
      targets.foreach { target =>
        if target.condition.isDefined then refuse(target, "a condition")
        if target.environment.isDefined then refuse(target, "an environment")
      }
    end if
  end validateSharedTargets

  /** Checked per (capability, target): the gate, capability condition and target condition come from different files,
    * and only their conjunction is never-true.
    */
  private def validateSatisfiable(capability: Capability, graph: ModuleGraph, config: PlanConfig): Unit =
    val gate = capability.gate match
      case Gate.OnReleaseTag  => Some(Satisfiable.Clause("Gate.OnReleaseTag", JobCondition.onReleaseTag))
      case Gate.OnDefaultPush =>
        val sat = config.pushBranches.headOption
          .flatMap(b => ExprLiteral.make(s"refs/heads/$b").toOption.map(JobCondition.RefIs(_)))
          .getOrElse(JobCondition.onDefaultPush(config.pushBranches))
        Some(Satisfiable.Clause("Gate.OnDefaultPush", sat))
      case _ => None
    val own = capability.condition.map(Satisfiable.Clause(s"capability '${capability.name}' condition", _))

    def refuse(where: String, problem: String): Nothing =
      sys.error(s"zipx: $where can never run: $problem")

    Satisfiable
      .findContradiction(gate.toList ++ own.toList)
      .foreach(problem => refuse(s"capability '${capability.name}'", problem))

    val targets = graph.nodes.filter(capability.participates).flatMap(capability.targets).distinctBy(_.name)
    targets.foreach { target =>
      target.condition.foreach { condition =>
        val clause = Satisfiable.Clause(s"target '${target.name}' condition", condition)
        Satisfiable
          .findContradiction(gate.toList ++ own.toList :+ clause)
          .foreach(problem => refuse(s"capability '${capability.name}' target '${target.name}'", problem))
      }
    }
  end validateSatisfiable

  val affectedJobId: JobId       = JobId("affected")
  val verifyGateJobId: JobId     = JobId("verify-gate")
  val cacheRehydrateJobId: JobId = JobId("cache-rehydrate")

  /** The required check; [[validateCapabilities]] reserves the same name. */
  val verifyRollupJobId: JobId = JobId("verify")

  /** Callers apply this to Graph scope only. Publish and Deploy opt in because under-publishing fails loudly, while
    * under-verifying is silently unsafe; see [[PlanConfig.affectedPublish]].
    */
  private def affectedGated(capability: Capability, config: PlanConfig): Boolean =
    capability.phase match
      case Phase.Verify  => true
      case Phase.Publish => config.affectedPublish
      case Phase.Deploy  => config.affectedDeploy

  def plan(graph: ModuleGraph, capabilities: List[Capability], config: PlanConfig): Workflow =
    validateCapabilities(capabilities, graph, config)

    // Only Graph jobs are per-module, so only they can be narrowed.
    val usesAffected =
      config.affected == AffectedMode.AffectedOnPR &&
        capabilities.exists(c =>
          (affectedGated(c, config) && c.scope == CapabilityScope.Graph) || c.affectedBy.isDefined
        )

    // Publish and Deploy run on a release tag and a merged-PR push, where Verify does not, so `affected` must run there
    // too (off-PR it emits `all`). Otherwise they read a skipped job's output, which is empty and fails `fromJson`.
    val affectedWhenVerifySkips =
      usesAffected && List(Phase.Publish, Phase.Deploy).exists(phase =>
        capabilities.exists(c => c.phase == phase && c.scope == CapabilityScope.Graph && affectedGated(c, config))
      )

    val hasVerify          = capabilities.exists(_.phase == Phase.Verify)
    val usesVerifyGate     = config.skipMergedPrPush && hasVerify
    val usesCacheRehydrate = emitsCacheRehydrate(config, hasVerify)

    // `allJobIds` also names capabilities with no participants; GitHub rejects a roll-up `needs` on a missing job.
    val verifyIds =
      capabilities
        .filter(c => c.phase == Phase.Verify && emitsJobs(c, graph))
        .flatMap(c => allJobIds(c, graph, config))
        .distinct
        .sorted
    val usesVerifyRollup = verifyIds.nonEmpty

    val byName = capabilities.map(c => c.name -> c).toMap

    // Capabilities whose jobs can skip rather than fail, which dependents must tolerate. Only Graph jobs are gated.
    val affectedGatedNames =
      if !usesAffected then Set.empty[CapabilityName]
      else
        capabilities
          .filter(c => c.scope == CapabilityScope.Graph && affectedGated(c, config))
          .map(_.name)
          .toSet

    val orderedCaps    = capabilities.zipWithIndex.sortBy((c, i) => (c.phase.ordinal, i)).map(_._1)
    val capabilityJobs =
      orderedCaps.flatMap { c =>
        val mode = MatrixCollapse.effective(c, config)
        c.scope match
          case CapabilityScope.Once =>
            List(
              onceJob(c, graph, config, byName, usesVerifyGate, usesVerifyRollup, affectedGatedNames)
            )
          case CapabilityScope.Aggregate =>
            aggregateJobs(c, graph, config, byName, usesVerifyGate, usesVerifyRollup, affectedGatedNames, mode)
          case CapabilityScope.Layer =>
            layerJobs(c, graph, config, byName, usesVerifyGate, usesVerifyRollup, affectedGatedNames, mode)
          case CapabilityScope.Graph =>
            graphCapabilityJobs(
              c,
              graph,
              config,
              usesAffected,
              byName,
              usesVerifyGate,
              usesVerifyRollup,
              affectedGatedNames,
              Pipeline.Ci,
            )
        end match
      }

    val leading =
      List(
        Option.when(usesVerifyGate)(verifyGateJobId         -> verifyGateJob(config)),
        Option.when(usesCacheRehydrate)(cacheRehydrateJobId -> cacheRehydrateJob(config)),
        Option.when(usesAffected)(
          affectedJobId ->
            affectedSetupJob(config, usesVerifyGate && !affectedWhenVerifySkips, affectedWhenVerifySkips)
        ),
      ).flatten

    // After every Verify job, before Publish: for readers and the phase sort, since `needs` does not require it.
    val rolled =
      if !usesVerifyRollup then capabilityJobs
      else
        val (verifyJobs, laterJobs) = capabilityJobs.partition((id, _) => verifyIds.contains(id))
        verifyJobs ++ List(verifyRollupJobId -> verifyRollupJob(config, verifyIds)) ++ laterJobs

    val jobs = ListMap.from[String, Job](leading ++ rolled)

    Workflow(
      name = config.workflowName,
      on = triggersFor(config, capabilities),
      jobs = jobs,
      concurrency = Option.when(config.cancelSupersededRuns)(concurrencyFor(config)),
    )
  end plan

  /** Grouped by workflow name and `github.ref`. Tag runs are never cancelled: publishing is not idempotent, and a
    * half-cancelled release can strand a staged Central bundle.
    */
  private def concurrencyFor(config: PlanConfig): Concurrency =
    Concurrency(
      group = (lit(config.workflowName + "-") ++ Expr.github("ref")).render,
      cancelInProgress = CancelInProgress.When(!onAnyTagPush),
    )

  /** Broader than [[JobCondition.onReleaseTag]]: Verify skips and cancellation is off for every tag, while only a `v`
    * tag publishes.
    */
  private val onAnyTagPush: Expr =
    Expr.startsWith(Expr.github("ref"), Expr.quoted("refs/tags/"))

  /** The `if:` form; [[eventIs]] is the `run:` shell-test form. */
  private inline def onEvent(inline name: String): Expr =
    Expr.github("event_name") === Expr.quoted(name)

  /** Compared against a quoted `'true'` rather than negated, because every `$GITHUB_OUTPUT` value is a string. */
  private val verifyGateRuns: Expr = Expr.JobOutput(verifyGateJobId, OutputName("run"))

  private val verifyGateResult: Expr = Expr.JobResult(verifyGateJobId)

  /** Fail-open: when this job is skipped or fails, Verify still runs. */
  private def verifyGateJob(config: PlanConfig): Job =
    Job(
      name = Some("verify-gate"),
      runsOn = List(config.runnerOs),
      `if` = Some((onEvent("push") && !onAnyTagPush).unwrapped),
      permissions = ListMap("contents" -> "read", "pull-requests" -> "read"),
      env = EnvValue.renderAll(config.env),
      outputs = ListMap("run" -> Expr.stepOutput("check", "run").render),
      steps = List(
        Step
          .run(verifyGateScript)
          .withId("check")
          .named("Skip Verify after merged PR push")
          .withEnv("GH_TOKEN", Expr.githubToken)
          .build
      ),
    )

  /** Merge and squash both associate the landed commit with the merged PR; a direct push does not.
    *
    * The `--jq` filter nests a double-quoted jq string in a double-quoted shell argument; a `Word.Dquote` inside a
    * `Dquote` renders the inner quotes as `\"`.
    *
    * `GET /commits/{sha}/pulls` is eventually consistent (a squash can return `[]` for seconds), so it retries with
    * 1/2/4/8/16s sleeps before failing open into a second Verify.
    */
  private def verifyGateScript: Script =
    val jqFilter = Word.dquote(
      Word.lit("[.[] | select(.merged_at != null and .base.ref == "),
      Word.dquote(Expr.github("ref_name").asWord),
      Word.lit(")] | length"),
    )
    val fetchPrs = Assign(
      "prs",
      Word.subst(
        Continued(
          "gh",
          List(
            List(
              Word.lit("api"),
              Word.dquote(
                Word.lit("repos/"),
                Expr.github("repository").asWord,
                Word.lit("/commits/"),
                Expr.github("sha").asWord,
                Word.lit("/pulls"),
              ),
            ),
            List(Word.lit("--jq"), jqFilter),
          ),
        )
      ),
    )
    val backoff = If(
      ShTest.IntEq(Word.vq("attempt"), Word.lit("2")),
      Block(Exec("sleep", Word.lit("1"))),
      elifs = List(
        ShTest.IntEq(Word.vq("attempt"), Word.lit("3")) -> Block(Exec("sleep", Word.lit("2"))),
        ShTest.IntEq(Word.vq("attempt"), Word.lit("4")) -> Block(Exec("sleep", Word.lit("4"))),
        ShTest.IntEq(Word.vq("attempt"), Word.lit("5")) -> Block(Exec("sleep", Word.lit("8"))),
        ShTest.IntEq(Word.vq("attempt"), Word.lit("6")) -> Block(Exec("sleep", Word.lit("16"))),
      ),
    )
    Script(
      Comment("Commits landed by merging/squashing a PR are associated with that PR via the API."),
      Comment("The commit-to-PR index can lag after squash; retry rather than fail-open into a second Verify."),
      Assign("prs", Word.lit("0")),
      ForIn(
        VarName("attempt"),
        List(Word.lit("1"), Word.lit("2"), Word.lit("3"), Word.lit("4"), Word.lit("5"), Word.lit("6")),
        Block(
          If(
            ShTest.IntEq(Word.vq("prs"), Word.lit("0")),
            Block(backoff, fetchPrs),
          )
        ),
      ),
      If(
        ShTest.IntGt(Word.vq("prs"), Word.lit("0")),
        Block(
          Exec("echo", Word.quoted("Merged PR push, skipping redundant Verify (already ran on the PR)")),
          setOutput("run", Word.lit("false")),
        ),
        elseDo = Some(Block(setOutput("run", Word.lit("true")))),
      ),
    )
  end verifyGateScript

  private inline def setOutput(inline name: String, value: Word.Quotable): Command =
    Exec("echo", Word.dquote(Word.lit(name + "="), value)).appendTo(Word.vq("GITHUB_OUTPUT"))

  /** `inline` rather than a lambda, so the quoted name is a literal the validator can see at compile time. */
  private inline def eventIs(inline name: String): ShTest =
    ShTest.StrEq(Word.dquote(Expr.github("event_name").asWord), Word.quoted(name))

  /** Fail-closed, unlike Verify: it runs only when verify-gate succeeded with `run=false`. */
  private def cacheRehydrateJob(config: PlanConfig): Job =
    val ctx = StepContext(
      node = ModuleNode(id = ModuleId.fromJobId(cacheRehydrateJobId), publishes = false, ciRelevant = false),
      target = None,
      matrixed = false,
      actions = config.actions,
    )
    Job(
      name = Some(cacheRehydrateJobId),
      runsOn = List(config.runnerOs),
      needs = List(verifyGateJobId),
      `if` = Some(
        (
          (verifyGateResult === Expr.quoted("success")) &&
            (verifyGateRuns === Expr.quoted("false"))
        ).unwrapped
      ),
      env = EnvValue.renderAll(config.env) ++ EnvValue.renderAll(config.cacheRehydrateEnv),
      steps = checkoutThenSbtSetup(config, cacheRehydrateJobId, nodeVersion = None, LocalCacheMode.Save) ++
        config.cacheRehydrateExtraSteps(ctx) ++ List(
          Step.run(Script(config.cacheRehydrateTask.render)).named(cacheRehydrateJobId).build
        ),
    )
  end cacheRehydrateJob

  /** One check a ruleset can require. GitHub counts a skipped required check as passing, so this runs under
    * `!cancelled()` and fails only when a need failed or was cancelled.
    */
  private def verifyRollupJob(config: PlanConfig, needs: List[JobId]): Job =
    Job(
      name = Some(verifyRollupJobId),
      runsOn = List(config.runnerOs),
      needs = needs,
      `if` = Some((!Expr.cancelled).unwrapped),
      steps = List(
        Step
          .run(Script(Exec("exit", Word.lit("1"))))
          .named("Fail when a Verify job failed or was cancelled")
          .when(verifyRollupFailed(needs))
          .build
      ),
    )

  private def verifyRollupFailed(needs: List[JobId]): Expr =
    val clause = (id: JobId) =>
      Expr.group(
        (Expr.JobResult(id) === Expr.quoted("failure")) || (Expr.JobResult(id) === Expr.quoted("cancelled"))
      )
    needs match
      case head :: tail => tail.foldLeft(clause(head))((acc, id) => acc || clause(id))
      case Nil          =>
        sys.error("zipx: verify roll-up has no Verify jobs")

  /** Verify never runs on a tag push or a `workflow_dispatch` (a manual run is for a docs-only deploy).
    *
    * @param excludeTagsAndDispatch
    *   `false` (with `usesVerifyGate = false`) keeps `affected` running on a tag and a merged-PR push.
    */
  private def applyVerifyGate(
      needs: List[JobId],
      cond: Option[String],
      phase: Phase,
      usesVerifyGate: Boolean,
      excludeTagsAndDispatch: Boolean = true,
  ): (List[JobId], Option[String]) =
    if phase != Phase.Verify then (needs, cond)
    else
      val notOnTagOrDispatch =
        Option.when(excludeTagsAndDispatch)(
          !onAnyTagPush && (Expr.github("event_name") !== Expr.quoted("workflow_dispatch"))
        )
      if !usesVerifyGate then (needs, andConditions(notOnTagOrDispatch.map(_.unwrapped), cond))
      else
        val gatedNeeds = (verifyGateJobId :: needs).distinct.sorted
        // Fail-open. `!cancelled()` keeps this reachable when the gate was skipped entirely.
        val gateCond = notOnTagOrDispatch.foldLeft(!Expr.cancelled)(_ && _) && Expr.group(
          Expr.group(verifyGateResult !== Expr.quoted("success")) ||
            Expr.group(verifyGateRuns === Expr.quoted("true"))
        )
        (gatedNeeds, andConditions(Some(gateCond.unwrapped), cond))
      end if

  private def affectedSetupJob(config: PlanConfig, usesVerifyGate: Boolean, runsWhenVerifySkips: Boolean): Job =
    val (needs, cond) =
      applyVerifyGate(Nil, None, Phase.Verify, usesVerifyGate, excludeTagsAndDispatch = !runsWhenVerifySkips)
    Job(
      name = Some("affected"),
      runsOn = List(config.runnerOs),
      needs = needs,
      `if` = cond,
      env = EnvValue.renderAll(config.env),
      outputs = ListMap("modules" -> Expr.stepOutput("compute", "modules").render),
      steps = checkoutThenSbtSetup(config, affectedJobId, nodeVersion = None, LocalCacheMode.Off) ++ List(
        Step
          .run(affectedScript(config.affectedOnPush))
          .withId("compute")
          .named("Compute affected modules")
          .build
      ),
    )
  end affectedSetupJob

  private def affectedScript(affectedOnPush: Boolean): Script =
    // Read from a file, not stdout: sbt prints server banners that `modules=$(sbt …)` would put in GITHUB_OUTPUT.
    val runAffected = Block(
      Exec(
        "sbt",
        Word.lit("-batch"),
        Word.lit("--error"),
        Word.dquote(Word.lit("zipxAffectedModules "), Word.v("BASE")),
      ),
      Assign("modules", Word.subst(Exec("cat", Word.lit("target/zipx-affected.json")))),
    )
    val buildEverything = Assign("modules", Word.squote("[\"all\"]"))

    val pushBranch =
      if !affectedOnPush then Nil
      else
        List(
          eventIs("push") -> Block(
            Assign("before", Word.dquote(Expr.github("event.before").asWord)),
            // A force-push or a branch-create reports this all-zero sha, which no diff can be taken against.
            If(
              ShTest.varEmpty("before") ||
                ShTest.varEquals("before", "0000000000000000000000000000000000000000"),
              Block(buildEverything),
              elseDo = Some(Block(Assign("BASE", Word.vq("before")), runAffected.commands)),
            ),
          )
        )

    Script(
      If(
        eventIs("pull_request"),
        Block(Assign("BASE", Word.dquote(Expr.github("event.pull_request.base.sha").asWord)), runAffected.commands),
        elifs = pushBranch,
        elseDo = Some(Block(buildEverything)),
      ),
      setOutput("modules", Word.v("modules")),
    )
  end affectedScript

  private def triggersFor(config: PlanConfig, capabilities: List[Capability]): Triggers =
    val releases =
      capabilities.exists(_.gate == Gate.OnReleaseTag) ||
        capabilities.exists(c => c.condition.exists(mentionsTagRef))
    Triggers(
      push = Some(
        BranchFilter(
          branches = config.pushBranches,
          tags = if releases then List(config.releaseTagPattern) else Nil,
        )
      ),
      pullRequest = Some(PullRequestTrigger()),
      workflowDispatch = Option.when(config.workflowDispatch)(WorkflowDispatch()),
    )
  end triggersFor

  private def mentionsTagRef(condition: JobCondition): Boolean = condition match
    case JobCondition.RefStartsWith(prefix) => prefix.unwrap.startsWith("refs/tags/")
    case JobCondition.RefIs(ref)            => ref.unwrap.startsWith("refs/tags/")
    case JobCondition.All(first, rest)      => mentionsTagRef(first) || rest.exists(mentionsTagRef)
    case JobCondition.Any(first, rest)      => mentionsTagRef(first) || rest.exists(mentionsTagRef)
    case JobCondition.Not(inner)            => mentionsTagRef(inner)
    case _                                  => false

  private def crossCapabilityNeeds(
      capability: Capability,
      graph: ModuleGraph,
      byName: Map[CapabilityName, Capability],
      config: PlanConfig,
  ): List[JobId] =
    (for
      capName <- capability.needsCapabilities
      dep     <- byName.get(capName).toList
      id      <- allJobIds(dep, graph, config)
    yield id).distinct.sorted

  private def onceJob(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      usesVerifyRollup: Boolean,
      affectedGatedNames: Set[CapabilityName],
  ): (JobId, Job) =
    val releaseCond = gateCondition(capability, config)
    val crossNeeds  = crossCapabilityNeeds(capability, graph, byName, config)
    val affectedBy  = affectedByModules(capability, graph, config)
    val phased      = phaseNeeds(capability, config, usesVerifyRollup)
    val rawNeeds    =
      (crossNeeds ++ phased ++ (if affectedBy.nonEmpty then List(affectedJobId) else Nil)).distinct.sorted
    // `phased` can skip (`cache-rehydrate` when Verify ran) or fail (the roll-up); unguarded, GitHub's implicit
    // `success()` would skip the publish.
    val tolerance =
      if affectedBy.isEmpty && phased.nonEmpty then Some(skipTolerantClauses(rawNeeds).mkString(" && "))
      else if affectedBy.isEmpty then tolerateSkips(capability, crossNeeds, affectedGatedNames)
      else
        val gate = Expr
          .group((affectedBy.map(Expr.contains(affectedModulesJson, _)) :+ affectedContainsAll).reduceLeft(_ || _))
          .unwrapped
        val clauses = skipTolerantClauses(rawNeeds.filterNot(_ == affectedJobId)) match
          case first :: rest => first :: gate :: rest
          case Nil           => List(gate)
        Some(clauses.mkString(" && "))
    val (needs, base) =
      applyVerifyGate(rawNeeds, andConditions(tolerance, releaseCond), capability.phase, usesVerifyGate)
    val cond = andConditions(base, JobCondition.renderOpt(capability.condition))
    capability.workflowCall match
      case Some(call) =>
        // GitHub rejects job-level `env` and `runs-on` alongside `uses`, hence neither here.
        capability.name.asJobId -> Job(
          name = Some(capability.name),
          runsOn = Nil,
          needs = needs,
          `if` = cond,
          permissions = ListMap.from(capability.permissions),
          uses = Some(call.uses),
          `with` = ListMap.from(call.withInputs),
        )
      case None =>
        val cache = cacheForCommand(config, capability.command.runsSbt)
        capability.name.asJobId -> Job(
          name = Some(capability.name),
          runsOn = capability.runsOn.getOrElse(List(config.runnerOs)),
          needs = needs,
          `if` = cond,
          permissions = ListMap.from(capability.permissions),
          container = capability.container,
          services = mergeServices(capability, cache),
          env = mergeEnv(config.env, cache.env, capability.env, Map.empty),
          steps = stepsFor(
            capability,
            syntheticNode,
            None,
            config,
            hasMatrix = false,
            cache,
            commandOverride = None,
            jobSuffix = capability.name.asJobId,
          ),
        )
    end match
  end onceJob

  private val syntheticNode = ModuleNode(id = ModuleId("_build"))

  /** A release-tag Publish skips the roll-up because Verify never runs on a tag. `cache-rehydrate` is the roll-up's
    * sibling, not its need, so a merge-push publish names it to wait for the save.
    */
  private def phaseNeeds(capability: Capability, config: PlanConfig, usesVerifyRollup: Boolean): List[JobId] =
    if !usesVerifyRollup || capability.phase != Phase.Publish then Nil
    else
      capability.gate match
        case Gate.Always | Gate.OnDefaultPush =>
          val cache =
            Option
              .when(emitsCacheRehydrate(config, hasVerify = true) && restoresBuildSnapshot(capability))(
                cacheRehydrateJobId
              )
              .toList
          verifyRollupJobId :: cache
        case Gate.OnReleaseTag | Gate.AffectedOnly => Nil

  private def emitsCacheRehydrate(config: PlanConfig, hasVerify: Boolean): Boolean =
    config.skipMergedPrPush && hasVerify && config.cacheRehydrateOnMerge && config.cache == CacheBackend.LocalDir

  private def restoresBuildSnapshot(capability: Capability): Boolean =
    capability.workflowCall.isEmpty && capability.command.runsSbt && capability.localCache != LocalCacheMode.Off

  private def emitsJobs(capability: Capability, graph: ModuleGraph): Boolean =
    capability.scope match
      case CapabilityScope.Once => true
      case _                    => participants(capability, graph).nonEmpty

  /** Empty means ungated, including when affected gating is off. */
  private def affectedByModules(capability: Capability, graph: ModuleGraph, config: PlanConfig): List[Expr] =
    if config.affected != AffectedMode.AffectedOnPR then Nil
    else
      capability.affectedBy.toList.flatMap(p =>
        graph.topologicalSort.flatMap(graph.get).filter(p).map(n => Expr.Quoted(n.id.asExprLiteral))
      )

  private def aggregateJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      usesVerifyRollup: Boolean,
      affectedGatedNames: Set[CapabilityName],
      mode: MatrixCollapse,
  ): List[(JobId, Job)] =
    val nodes = participants(capability, graph)
    if nodes.isEmpty then Nil
    else
      val phased     = phaseNeeds(capability, config, usesVerifyRollup)
      val crossNeeds =
        (crossCapabilityNeeds(capability, graph, byName, config) ++ phased).distinct.sorted
      val joined      = joinCommands(capability, nodes)
      val cache       = cacheForCommand(config, joined.isDefined)
      val runner      = capability.runsOn.getOrElse(List(config.runnerOs))
      val releaseCond = gateCondition(capability, config)
      val tolerance   =
        if phased.nonEmpty then Some(skipTolerantClauses(crossNeeds).mkString(" && "))
        else tolerateSkips(capability, crossNeeds, affectedGatedNames)
      val (baseNeeds, gatedCond) =
        applyVerifyGate(crossNeeds, andConditions(tolerance, releaseCond), capability.phase, usesVerifyGate)
      val baseCond = andConditions(gatedCond, JobCondition.renderOpt(capability.condition))

      val shared = capability.targetFanOut match
        case TargetFanOut.JobPerTarget => Nil
        case TargetFanOut.SharedJob    => distinctTargets(capability, graph)

      distinctFannedTargets(capability, graph) match
        case Nil =>
          List(
            capability.name.asJobId -> Job(
              name = Some(capability.name),
              runsOn = runner,
              needs = baseNeeds,
              `if` = baseCond,
              permissions = ListMap.from(capability.permissions),
              container = capability.container,
              services = mergeServices(capability, cache),
              env = mergeEnv(config.env, cache.env, capability.env, sharedEnv(shared)),
              steps = stepsFor(
                capability,
                nodes.head,
                None,
                config,
                hasMatrix = false,
                cache,
                commandOverride = joined,
                jobSuffix = capability.name.asJobId,
                destinations = shared,
              ),
            )
          )
        case targets =>
          val collapseKind =
            if mode == MatrixCollapse.Off then None
            else
              MatrixCollapse.targetsCompatible(targets) match
                case Right(kind)                            => Some(kind)
                case Left(_) if mode == MatrixCollapse.Auto => None
                case Left(err)                              =>
                  sys.error(s"zipx: capability '${capability.name}': $err")
          collapseKind match
            case Some(MatrixCollapse.TargetMatrix.Simple) =>
              val sharedCond = targets.headOption.flatMap(t => JobCondition.renderOpt(t.condition))
              val envBinding =
                Option.when(targets.exists(_.environment.isDefined))(Expr.matrix("target").render)
              List(
                capability.name.asJobId -> Job(
                  name = Some(capability.name),
                  runsOn = runner,
                  needs = baseNeeds,
                  `if` = andConditions(baseCond, sharedCond),
                  environment = envBinding.map(JobEnvironment(_)),
                  permissions = ListMap.from(capability.permissions),
                  strategy = Some(Strategy(matrix = ListMap("target" -> targets.map(_.name: String)))),
                  container = capability.container,
                  services = mergeServices(capability, cache),
                  env = mergeEnv(config.env, cache.env, capability.env, MatrixCollapse.collapsedTargetEnv(targets)),
                  steps = stepsFor(
                    capability,
                    nodes.head,
                    targets.headOption,
                    config,
                    hasMatrix = true,
                    cache,
                    commandOverride = joined,
                    jobSuffix = capability.name.asJobId,
                    matrixAxes = Set("target"),
                  ),
                )
              )
            case Some(MatrixCollapse.TargetMatrix.Include) =>
              val sharedCond = targets.headOption.flatMap(t => JobCondition.renderOpt(t.condition))
              val envBinding =
                Option.when(targets.exists(_.environment.isDefined))(Expr.matrix("environment").render)
              List(
                capability.name.asJobId -> Job(
                  name = Some(capability.name),
                  runsOn = runner,
                  needs = baseNeeds,
                  `if` = andConditions(baseCond, sharedCond),
                  environment = envBinding.map(JobEnvironment(_)),
                  permissions = ListMap.from(capability.permissions),
                  strategy = Some(
                    Strategy(include = MatrixCollapse.includeRows(Nil, targets))
                  ),
                  container = capability.container,
                  services = mergeServices(capability, cache),
                  env = mergeEnv(config.env, cache.env, capability.env, MatrixCollapse.collapsedIncludeEnv(targets)),
                  steps = stepsFor(
                    capability,
                    nodes.head,
                    targets.headOption,
                    config,
                    hasMatrix = true,
                    cache,
                    commandOverride = joined,
                    jobSuffix = capability.name.asJobId,
                    matrixAxes = Set("target"),
                  ),
                )
              )
            case None =>
              targets.map { target =>
                val id = aggregateTargetJobId(capability, target)
                id -> Job(
                  name = Some(s"${capability.name} (${target.name})"),
                  runsOn = runner,
                  needs = baseNeeds,
                  `if` = andConditions(baseCond, JobCondition.renderOpt(target.condition)),
                  environment = target.environment.map(JobEnvironment(_)),
                  permissions = ListMap.from(capability.permissions),
                  container = capability.container,
                  services = mergeServices(capability, cache),
                  env = mergeEnv(config.env, cache.env, capability.env, target.env),
                  steps = stepsFor(
                    capability,
                    nodes.head,
                    Some(target),
                    config,
                    hasMatrix = false,
                    cache,
                    commandOverride = joined,
                    jobSuffix = id,
                  ),
                )
              }
          end match
      end match
    end if
  end aggregateJobs

  private def layerJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      usesVerifyRollup: Boolean,
      affectedGatedNames: Set[CapabilityName],
      mode: MatrixCollapse,
  ): List[(JobId, Job)] =
    val layers = graph.subsetLayers(capability.participates)
    if layers.isEmpty then Nil
    else
      val phased = phaseNeeds(capability, config, usesVerifyRollup)
      // Only the first wave needs the roll-up; later waves reach it through the previous wave.
      val firstWaveNeeds =
        (crossCapabilityNeeds(capability, graph, byName, config) ++ phased).distinct.sorted
      val runner      = capability.runsOn.getOrElse(List(config.runnerOs))
      val releaseCond = gateCondition(capability, config)
      val tolerance   =
        if phased.nonEmpty then Some(skipTolerantClauses(firstWaveNeeds).mkString(" && "))
        else tolerateSkips(capability, firstWaveNeeds, affectedGatedNames)
      val shared = capability.targetFanOut match
        case TargetFanOut.JobPerTarget => Nil
        case TargetFanOut.SharedJob    => distinctTargets(capability, graph)
      val fanned                                             = distinctFannedTargets(capability, graph)
      val layerCollapse: Option[MatrixCollapse.TargetMatrix] =
        if mode == MatrixCollapse.Off || fanned.isEmpty then None
        else
          MatrixCollapse.targetsCompatible(fanned) match
            case Right(kind)                            => Some(kind)
            case Left(_) if mode == MatrixCollapse.Auto => None
            case Left(err)                              =>
              sys.error(s"zipx: capability '${capability.name}': $err")

      layers.zipWithIndex.flatMap { (layerIds, i) =>
        val firstWave  = i == 0
        val layerNodes = layerIds.flatMap(graph.get)
        val joined     = joinCommands(capability, layerNodes)
        val cache      = cacheForCommand(config, joined.isDefined)

        def waveJob(
            id: JobId,
            display: String,
            target: Option[Target],
            targetCond: Option[String],
            environment: Option[String],
            targetEnv: Map[String, EnvValue],
            destinations: List[Target],
            prev: List[JobId],
            strategy: Option[Strategy] = None,
            matrixAxes: Set[String] = Set.empty,
        ): (JobId, Job) =
          val layerNeeds =
            (prev ++ (if firstWave then firstWaveNeeds else Nil)).distinct.sorted
          val (needs, base) =
            if firstWave then
              applyVerifyGate(layerNeeds, andConditions(tolerance, releaseCond), capability.phase, usesVerifyGate)
            else (layerNeeds, releaseCond)
          val ifCond =
            andConditions(base, andConditions(JobCondition.renderOpt(capability.condition), targetCond))
          id -> Job(
            name = Some(display),
            runsOn = runner,
            needs = needs,
            `if` = ifCond,
            environment = environment.map(JobEnvironment(_)),
            permissions = ListMap.from(capability.permissions),
            strategy = strategy,
            container = capability.container,
            services = mergeServices(capability, cache),
            env = mergeEnv(config.env, cache.env, capability.env, targetEnv),
            steps = stepsFor(
              capability,
              layerNodes.head,
              target,
              config,
              hasMatrix = strategy.isDefined,
              cache,
              commandOverride = joined,
              jobSuffix = id,
              destinations = destinations,
              matrixAxes = matrixAxes,
            ),
          )
        end waveJob

        fanned match
          case Nil =>
            val prev = if firstWave then Nil else List(layerJobId(capability, i - 1))
            List(
              waveJob(
                layerJobId(capability, i),
                s"${capability.name} L$i",
                None,
                None,
                None,
                sharedEnv(shared),
                shared,
                prev,
              )
            )
          case targets =>
            layerCollapse match
              case Some(MatrixCollapse.TargetMatrix.Simple) =>
                val prev       = if firstWave then Nil else List(layerJobId(capability, i - 1))
                val sharedCond = targets.headOption.flatMap(t => JobCondition.renderOpt(t.condition))
                val envBinding =
                  Option.when(targets.exists(_.environment.isDefined))(Expr.matrix("target").render)
                List(
                  waveJob(
                    layerJobId(capability, i),
                    s"${capability.name} L$i",
                    targets.headOption,
                    sharedCond,
                    envBinding,
                    MatrixCollapse.collapsedTargetEnv(targets),
                    Nil,
                    prev,
                    strategy = Some(Strategy(matrix = ListMap("target" -> targets.map(_.name: String)))),
                    matrixAxes = Set("target"),
                  )
                )
              case Some(MatrixCollapse.TargetMatrix.Include) =>
                val prev       = if firstWave then Nil else List(layerJobId(capability, i - 1))
                val sharedCond = targets.headOption.flatMap(t => JobCondition.renderOpt(t.condition))
                val envBinding =
                  Option.when(targets.exists(_.environment.isDefined))(Expr.matrix("environment").render)
                List(
                  waveJob(
                    layerJobId(capability, i),
                    s"${capability.name} L$i",
                    targets.headOption,
                    sharedCond,
                    envBinding,
                    MatrixCollapse.collapsedIncludeEnv(targets),
                    Nil,
                    prev,
                    strategy = Some(Strategy(include = MatrixCollapse.includeRows(Nil, targets))),
                    matrixAxes = Set("target"),
                  )
                )
              case None =>
                targets.map { t =>
                  val prev = if firstWave then Nil else List(layerTargetJobId(capability, i - 1, t))
                  waveJob(
                    layerTargetJobId(capability, i, t),
                    s"${capability.name} L$i (${t.name})",
                    Some(t),
                    JobCondition.renderOpt(t.condition),
                    t.environment,
                    t.env,
                    Nil,
                    prev,
                  )
                }
        end match
      }
    end if
  end layerJobs

  /** One job per participating module, or one matrix job when [[MatrixCollapse]] folds them. */
  private[core] def graphCapabilityJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      usesAffected: Boolean,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      usesVerifyRollup: Boolean,
      affectedGatedNames: Set[CapabilityName],
      pipeline: Pipeline,
  ): List[(JobId, Job)] =
    def perModule =
      for
        moduleId <- graph.topologicalSort
        node     <- graph.get(moduleId).toList
        if capability.participates(node)
        job <- graphJobsFor(
          capability,
          node,
          graph,
          config,
          usesAffected,
          byName,
          usesVerifyGate,
          usesVerifyRollup,
          affectedGatedNames,
          pipeline,
        )
      yield job
    MatrixCollapse.effective(capability, config) match
      case MatrixCollapse.Off                                                              => perModule
      case MatrixCollapse.Auto if !MatrixCollapse.graphCollapseFeasible(capability, graph) => perModule
      case collapse                                                                        =>
        graphMatrixJobs(
          capability,
          graph,
          config,
          usesAffected,
          byName,
          usesVerifyGate,
          usesVerifyRollup,
          affectedGatedNames,
          collapse,
          pipeline,
        )
    end match
  end graphCapabilityJobs

  private def graphMatrixJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      usesAffected: Boolean,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      usesVerifyRollup: Boolean,
      affectedGatedNames: Set[CapabilityName],
      mode: MatrixCollapse,
      pipeline: Pipeline,
  ): List[(JobId, Job)] =
    val nodes = participants(capability, graph)
    if nodes.isEmpty then Nil
    else
      val fannedSample = fannedTargets(capability, nodes.head)
      val foldTargets  = fannedSample.nonEmpty
      if foldTargets && mode == MatrixCollapse.Strict then
        sys.error(
          s"zipx: capability '${capability.name}' is MatrixCollapse.Strict with JobPerTarget fan-out; " +
            "Strict collapses the module axis only when targets are empty or SharedJob. Use Coarse or Auto for " +
            "module × target, or SharedJob / no targets for Strict."
        )
      val targetKind: Option[MatrixCollapse.TargetMatrix] =
        if !foldTargets then None
        else
          val targetSets = nodes.map(n => capability.targets(n).map(_.name).sorted)
          if targetSets.distinct.sizeIs > 1 then
            sys.error(
              s"zipx: capability '${capability.name}': participating modules have divergent target sets; " +
                "refuse matrix collapse"
            )
          MatrixCollapse.targetsCompatible(distinctTargets(capability, graph)) match
            case Right(kind) => Some(kind)
            case Left(err)   => sys.error(s"zipx: capability '${capability.name}': $err")

      if mode == MatrixCollapse.Strict && MatrixCollapse.hasSameCapInterModuleNeeds(capability, nodes, graph) then
        sys.error(
          s"zipx: capability '${capability.name}' is MatrixCollapse.Strict but participating modules have " +
            "same-capability inter-module needs. Use Coarse to drop those needs, or leave collapse Off."
        )

      val commandOverride = MatrixCollapse.isomorphicMatrixCommands(capability, nodes) match
        case Right(cmd) => Some(cmd)
        case Left(err)  => sys.error(s"zipx: capability '${capability.name}': $err")

      val scalaVersions = nodes.map(_.crossScalaVersions).distinct
      val scalaAxis     =
        if capability.matrixed && config.scalaMatrix then
          scalaVersions match
            case List(versions) if versions.sizeIs > 1 => Some(versions)
            case List(_)                               => None
            case _                                     =>
              sys.error(
                s"zipx: capability '${capability.name}': participants have differing crossScalaVersions; " +
                  "refuse matrix collapse with scalaMatrix"
              )
        else None

      val moduleNames              = nodes.map(_.id: String)
      val targets                  = if foldTargets then distinctTargets(capability, graph) else Nil
      val (matrixMap, includeRows) = targetKind match
        case Some(MatrixCollapse.TargetMatrix.Include) =>
          (
            scalaAxis.fold(ListMap.empty[String, List[String]])(v => ListMap("scala" -> v)),
            MatrixCollapse.includeRows(moduleNames, targets),
          )
        case _ =>
          (
            ListMap("module" -> moduleNames) ++
              (if targets.nonEmpty then ListMap("target" -> targets.map(_.name: String)) else ListMap.empty) ++
              scalaAxis.fold(ListMap.empty[String, List[String]])(v => ListMap("scala" -> v)),
            Nil,
          )

      val crossNeeds =
        for
          capName <- capability.needsCapabilities
          dep     <- byName.get(capName).toList
          id      <- allJobIds(dep, graph, config)
        yield id

      val phased          = phaseNeeds(capability, config, usesVerifyRollup)
      val selector        = selectionJobId(pipeline)
      val gatedOnAffected = usesAffected && affectedGated(capability, config)
      val rawNeeds        =
        (crossNeeds ++ phased ++ (if gatedOnAffected then List(selector) else Nil)).distinct.sorted
      val cache        = cacheForCommand(config, commandOverride.isDefined)
      val guardedNeeds = rawNeeds.filterNot(id => id == selector || id == verifyGateJobId)
      val skipTolerant = gatedOnAffected || dependsOnSkippable(capability, affectedGatedNames) || phased.nonEmpty
      val releaseGate  = gateFor(capability, config, pipeline)
      val legTarget    = Option.when(targets.nonEmpty)(Expr.matrix("target"))
      // Job-level `if` cannot use `matrix.*` (GitHub rejects the workflow). Skip the whole job when
      // nothing is selected; per-leg membership is enforced on each step below.
      val affectedGate =
        Option.when(gatedOnAffected)(selectsAny(pipeline, targeted = legTarget.isDefined).unwrapped)
      val stepAffectedGate =
        Option.when(gatedOnAffected)(selects(pipeline, Expr.matrix("module"), legTarget).unwrapped)
      val tolerance =
        if gatedOnAffected || skipTolerant then skipTolerantClauses(guardedNeeds)
        else Nil
      val clauses =
        tolerance.headOption.toList ++ releaseGate.toList ++ affectedGate.toList ++ tolerance.drop(1)
      val baseCond       = if clauses.isEmpty then None else Some(clauses.mkString(" && "))
      val (needs, gated) =
        applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate)
      val targetCond =
        targets.headOption.flatMap(t => JobCondition.renderOpt(t.condition))
      val cond      = andConditions(andConditions(gated, JobCondition.renderOpt(capability.condition)), targetCond)
      val runner    = capability.runsOn.getOrElse(List(config.runnerOs))
      val shared    = sharedTargets(capability, nodes.head)
      val targetEnv =
        targetKind match
          case Some(MatrixCollapse.TargetMatrix.Include) => MatrixCollapse.collapsedIncludeEnv(targets)
          case _ if targets.nonEmpty                     => MatrixCollapse.collapsedTargetEnv(targets)
          case _                                         => sharedEnv(shared)
      val envBinding =
        targetKind match
          case Some(MatrixCollapse.TargetMatrix.Include) if targets.exists(_.environment.isDefined) =>
            Some(Expr.matrix("environment").render)
          case _ if targets.exists(_.environment.isDefined) =>
            Some(Expr.matrix("target").render)
          case _ => None
      val axes  = if includeRows.nonEmpty then Set("module", "target") else matrixMap.keySet
      val steps = stepsFor(
        capability,
        nodes.head,
        targets.headOption,
        config,
        hasMatrix = true,
        cache,
        commandOverride = commandOverride,
        jobSuffix = capability.name.asJobId,
        destinations = shared,
        matrixAxes = axes,
        pipeline = pipeline,
        module = Expr.matrix("module"),
      ).map(andStepIf(_, stepAffectedGate))

      List(
        capability.name.asJobId -> Job(
          name = Some(capability.name),
          runsOn = runner,
          needs = needs,
          `if` = cond,
          environment = jobEnvironment(pipeline, capability, envBinding, Expr.matrix("module")),
          permissions = ListMap.from(capability.permissions),
          strategy = Some(Strategy(matrix = matrixMap, include = includeRows)),
          container = capability.container,
          services = mergeServices(capability, cache),
          env = mergeEnv(config.env, cache.env, capability.env, targetEnv ++ pipelineEnv(pipeline)),
          steps = steps,
        )
      )
    end if
  end graphMatrixJobs

  private def graphJobsFor(
      capability: Capability,
      node: ModuleNode,
      graph: ModuleGraph,
      config: PlanConfig,
      usesAffected: Boolean,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      usesVerifyRollup: Boolean,
      affectedGatedNames: Set[CapabilityName],
      pipeline: Pipeline,
  ): List[(JobId, Job)] =
    val upstreamNeeds = capability.ordering match
      case Ordering.ParallelWithUpstream =>
        graph
          .directDeps(node.id)
          .flatMap(graph.get)
          .filter(capability.participates)
          .flatMap(dep => jobIdsForGraph(capability, dep))
      case Ordering.DependencyOrdered =>
        nearestParticipatingAncestors(node, graph, capability).flatMap { ancId =>
          graph.get(ancId).toList.flatMap(jobIdsForGraph(capability, _))
        }
      case Ordering.Independent => Nil

    val crossNeeds =
      for
        capName <- capability.needsCapabilities
        dep     <- byName.get(capName).toList
        id      <-
          dep.scope match
            case CapabilityScope.Graph =>
              if MatrixCollapse.effective(dep, config) != MatrixCollapse.Off then allJobIds(dep, graph, config)
              else if dep.participates(node) then jobIdsForGraph(dep, node)
              else Nil
            case _ => allJobIds(dep, graph, config)
      yield id

    val phased          = phaseNeeds(capability, config, usesVerifyRollup)
    val selector        = selectionJobId(pipeline)
    val gatedOnAffected = usesAffected && affectedGated(capability, config)
    val rawNeeds        =
      (upstreamNeeds ++ crossNeeds ++ phased ++ (if gatedOnAffected then List(selector) else Nil)).distinct.sorted

    val matrix =
      if capability.matrixed && config.scalaMatrix && node.crossScalaVersions.sizeIs > 1 then
        Some(Strategy(matrix = ListMap("scala" -> node.crossScalaVersions)))
      else None

    val cache = cacheForCommand(config, capability.command.runsSbt)
    // The selector and `verify-gate` have clauses of their own. `crossNeeds` stay guarded so a failed `fmt` still
    // blocks tests that `!cancelled()` would otherwise let through.
    val guardedNeeds = rawNeeds.filterNot(id => id == selector || id == verifyGateJobId)
    val skipTolerant = gatedOnAffected || dependsOnSkippable(capability, affectedGatedNames) || phased.nonEmpty
    val needs        = applyVerifyGate(rawNeeds, None, capability.phase, usesVerifyGate)._1

    // Per target, because the deploy plan selects modules per target. `affected` ignores the target, so ci.yml's
    // conditions are the same for every target of a module.
    def condFor(target: Option[Target]): Option[String] =
      val base = jobCondition(
        capability,
        node,
        guardedNeeds,
        gatedOnAffected,
        skipTolerant,
        config,
        pipeline,
        target.map(t => Expr.Quoted(t.name.asExprLiteral)),
      )
      // Verify carries its own gate rather than relying on a skipped `affected`, which stays running once Publish or
      // Deploy reads it.
      val gated = applyVerifyGate(rawNeeds, base, capability.phase, usesVerifyGate)._2
      andConditions(gated, JobCondition.renderOpt(capability.condition))
    end condFor
    val runner = capability.runsOn.getOrElse(List(config.runnerOs))
    val module = lit(node.id)

    def baseJob(
        id: JobId,
        displayName: String,
        target: Option[Target],
        cond: Option[String],
        targetEnv: Map[String, EnvValue],
        destinations: List[Target] = Nil,
    ): (JobId, Job) =
      val environment = jobEnvironment(pipeline, capability, target.flatMap(_.environment), module)
      id -> Job(
        name = Some(displayName),
        runsOn = runner,
        needs = needs,
        `if` = cond,
        environment = environment,
        concurrency = deployConcurrency(pipeline, environment, id),
        permissions = ListMap.from(capability.permissions),
        strategy = matrix,
        container = capability.container,
        services = mergeServices(capability, cache),
        env = mergeEnv(config.env, cache.env, capability.env, targetEnv ++ pipelineEnv(pipeline)),
        steps = stepsFor(
          capability,
          node,
          target,
          config,
          matrix.isDefined,
          cache,
          commandOverride = None,
          jobSuffix = id,
          destinations = destinations,
          pipeline = pipeline,
          module = module,
        ),
      )
    end baseJob

    fannedTargets(capability, node) match
      case Nil =>
        val shared = sharedTargets(capability, node)
        List(
          baseJob(
            jobId(capability, node.id),
            s"${capability.name} ${node.id}",
            None,
            condFor(None),
            sharedEnv(shared),
            destinations = shared,
          )
        )
      case targets =>
        targets.sortBy(_.name).map { target =>
          baseJob(
            jobId(capability, node.id, target),
            s"${capability.name} ${node.id} (${target.name})",
            Some(target),
            andConditions(condFor(Some(target)), JobCondition.renderOpt(target.condition)),
            target.env,
          )
        }
    end match
  end graphJobsFor

  /** Prefixed per destination so two registries' `AWS_ROLE_TO_ASSUME` coexist instead of `++` keeping the last. Steps
    * read a value back with [[Target.envKey]].
    */
  private def sharedEnv(destinations: List[Target]): Map[String, EnvValue] =
    destinations.flatMap(_.prefixedEnv).toMap

  private def mergeEnv(
      plan: Map[String, EnvValue],
      cache: ListMap[String, String],
      capability: Map[String, EnvValue],
      target: Map[String, EnvValue],
  ): ListMap[String, String] =
    EnvValue.renderAll(plan) ++ cache ++ EnvValue.renderAll(capability) ++ EnvValue.renderAll(target)

  /** The cache sidecar wins a colliding service id: sbt is configured to reach it, while a capability's own sidecar
    * fails loudly in the tests that connect to it.
    */
  private def mergeServices(
      capability: Capability,
      cache: CacheContribution,
  ): ListMap[String, JobService] =
    ListMap.from(capability.services) ++ cache.services

  private def andConditions(a: Option[String], b: Option[String]): Option[String] =
    (a, b) match
      case (Some(x), Some(y)) => Some(s"($x) && ($y)")
      case (Some(x), None)    => Some(x)
      case (None, Some(y))    => Some(y)
      case (None, None)       => None

  /** Tolerates a skipped need but still fails on a failed one. `!cancelled()` overrides GitHub's implicit `success()`,
    * so each need is then guarded with `!= 'failure'`.
    */
  private def skipTolerantClauses(needs: List[JobId]): List[String] =
    (!Expr.cancelled).unwrapped +: needs.distinct.sorted.map(n =>
      (Expr.JobResult(n) !== Expr.quoted("failure")).unwrapped
    )

  /** One hop is enough: a skip-tolerant dependent never skips itself, so its own dependents see `success`. */
  private def dependsOnSkippable(
      capability: Capability,
      affectedGatedNames: Set[CapabilityName],
  ): Boolean =
    capability.needsCapabilities.exists(affectedGatedNames.contains)

  /** For non-Graph scopes, which never skip themselves. `None` when nothing they need can skip, leaving `if:` as is. */
  private def tolerateSkips(
      capability: Capability,
      crossNeeds: List[JobId],
      affectedGatedNames: Set[CapabilityName],
  ): Option[String] =
    Option.when(dependsOnSkippable(capability, affectedGatedNames) && crossNeeds.nonEmpty)(
      skipTolerantClauses(crossNeeds).mkString(" && ")
    )

  /** A dispatched deploy is its own gate: a release-tag or default-push gate there could never be true. */
  private def gateFor(capability: Capability, config: PlanConfig, pipeline: Pipeline): Option[String] =
    pipeline match
      case Pipeline.Ci        => gateCondition(capability, config)
      case Pipeline.Deploy(_) => None

  private def gateCondition(capability: Capability, config: PlanConfig): Option[String] =
    capability.gate match
      case Gate.OnReleaseTag  => Some(JobCondition.onReleaseTag.render)
      case Gate.OnDefaultPush =>
        Some(Expr.group(JobCondition.onDefaultPush(config.pushBranches).expr).unwrapped)
      case _ => None

  private def jobCondition(
      capability: Capability,
      node: ModuleNode,
      guardedNeeds: List[JobId],
      gatedOnAffected: Boolean,
      skipTolerant: Boolean,
      config: PlanConfig,
      pipeline: Pipeline,
      target: Option[Expr],
  ): Option[String] =
    val releaseGate  = gateFor(capability, config, pipeline)
    val affectedGate =
      Option.when(gatedOnAffected)(selects(pipeline, Expr.Quoted(node.id.asExprLiteral), target).unwrapped)
    val tolerance =
      if gatedOnAffected || skipTolerant then skipTolerantClauses(guardedNeeds)
      else Nil

    val clauses =
      tolerance.headOption.toList ++ releaseGate.toList ++ affectedGate.toList ++ tolerance.drop(1)
    if clauses.isEmpty then None else Some(clauses.mkString(" && "))
  end jobCondition

  /** `fromJson` makes `contains` mean array membership rather than substring. */
  private def affectedContains(member: ExprLiteral): Expr =
    Expr.contains(
      Expr.fromJson(Expr.JobOutput(affectedJobId, OutputName("modules"))),
      Expr.Quoted(member),
    )

  /** `all` is the affected job's "could not narrow it down" answer. */
  private val affectedContainsAll: Expr = affectedContains(ExprLiteral("all"))

  /** Job-level, so it reads no `matrix`. [[Expr.lit]] because `'[]'` is outside the [[ExprLiteral]] character set. */
  private val affectedModulesNonEmpty: Expr =
    Expr.JobOutput(affectedJobId, OutputName("modules")) !== Expr.lit("'[]'")

  /** What differs between `ci.yml` and `zipx-deploy.yml` for the Graph jobs they share. */
  private[core] enum Pipeline:

    /** `affected` selects the modules, and jobs run on the event's own commit. */
    case Ci

    /** `resolve` selects the images and each target's modules, and jobs check out and record its `sha`. */
    case Deploy(imagesEnvironment: String)

  private def selectionJobId(pipeline: Pipeline): JobId = pipeline match
    case Pipeline.Ci        => affectedJobId
    case Pipeline.Deploy(_) => DeployWorkflow.ResolveJobId

  private def selects(pipeline: Pipeline, module: Expr, target: Option[Expr]): Expr = pipeline match
    case Pipeline.Ci        => Expr.group(Expr.contains(affectedModulesJson, module) || affectedContainsAll)
    case Pipeline.Deploy(_) =>
      target match
        case None    => resolved && Expr.contains(Expr.fromJson(DeployWorkflow.imagesOutput), module)
        case Some(t) => resolved && Expr.contains(Expr.fromJson(DeployWorkflow.targetsOutput).at(t), module)

  /** Job-level [[selects]] for a matrix job, which cannot read `matrix` there. */
  private def selectsAny(pipeline: Pipeline, targeted: Boolean): Expr = pipeline match
    case Pipeline.Ci        => affectedModulesNonEmpty
    case Pipeline.Deploy(_) =>
      if targeted then resolved && (DeployWorkflow.targetsOutput !== Expr.lit("'{}'"))
      else resolved && (DeployWorkflow.imagesOutput !== Expr.lit("'[]'"))

  /** First, so a skipped `resolve` (an unlabeled PR) short-circuits before `fromJson` reads its empty outputs. */
  private val resolved: Expr = Expr.JobResult(DeployWorkflow.ResolveJobId) === Expr.quoted("success")

  private val affectedModulesJson: Expr = Expr.fromJson(Expr.JobOutput(affectedJobId, OutputName("modules")))

  /** Under [[Pipeline.Deploy]] the url tells the next `changed` deploy which module shipped at which commit (see
    * [[GitHubDeployments]]). Image jobs bind the images Environment so each push is recorded too.
    */
  private def jobEnvironment(
      pipeline: Pipeline,
      capability: Capability,
      environment: Option[String],
      module: Expr,
  ): Option[JobEnvironment] = pipeline match
    case Pipeline.Ci                        => environment.map(JobEnvironment(_))
    case Pipeline.Deploy(imagesEnvironment) =>
      val name =
        environment.orElse(Option.when(DeployWorkflow.isImage(capability))(imagesEnvironment))
      name.map(JobEnvironment(_, Some(DeployWorkflow.deployedUrl(module).render)))

  /** Keyed by job id, so runs reaching one Environment queue instead of interleaving, and two image pushes of one
    * module never race an immutable-tag registry.
    */
  private def deployConcurrency(pipeline: Pipeline, environment: Option[JobEnvironment], id: JobId): Option[String] =
    pipeline match
      case Pipeline.Ci        => None
      case Pipeline.Deploy(_) => environment.map(_ => s"zipx-deploy-$id")

  private def pipelineEnv(pipeline: Pipeline): Map[String, EnvValue] = pipeline match
    case Pipeline.Ci        => Map.empty
    case Pipeline.Deploy(_) => Map(DeployWorkflow.ShaEnv -> EnvValue.typed(DeployWorkflow.shaOutput))

  private def andStepIf(step: Step, cond: Option[String]): Step =
    cond match
      case None    => step
      case Some(c) =>
        step.copy(`if` = step.`if` match
          case Some(existing) => Some(s"($existing) && ($c)")
          case None           => Some(c))

  /** Total: callers pass only validated parts (workflow name, module id) and punctuation, none of which hold the
    * control characters [[ShText]] rejects.
    */
  private def lit(text: String): Expr = Expr.Lit(ShText.unsafeMake(text))

  private def nearestParticipatingAncestors(
      node: ModuleNode,
      graph: ModuleGraph,
      capability: Capability,
  ): List[String] =
    def go(frontier: List[String], found: Set[String], seen: Set[String]): Set[String] =
      frontier match
        case Nil    => found
        case h :: t =>
          val deps                         = graph.directDeps(h).filterNot(seen)
          val (participating, passthrough) =
            deps.partition(d => graph.get(d).exists(capability.participates))
          go(passthrough ++ t, found ++ participating, seen ++ deps)
    go(List(node.id), Set.empty, Set.empty).toList.sorted
  end nearestParticipatingAncestors

  /** `nodeVersion` is the capability's, not the config's: a Node toolchain is per-suite, so a Scala.js test capability
    * can ask for one without putting it on every publish job in the build.
    */
  private def stepsFor(
      capability: Capability,
      node: ModuleNode,
      target: Option[Target],
      config: PlanConfig,
      hasMatrix: Boolean,
      cache: CacheContribution,
      commandOverride: Option[SbtCommand],
      jobSuffix: JobId,
      destinations: List[Target] = Nil,
      matrixAxes: Set[String] = Set.empty,
      pipeline: Pipeline = Pipeline.Ci,
      module: Expr = Expr.matrix("module"),
  ): List[Step] =
    val base =
      commandOverride.orElse(
        Option.when(capability.command.runsSbt)(capability.command.commandFor(node))
      )
    val command  = capability.sessionCommand(base)
    val ctx      = StepContext(node, target, hasMatrix, config.actions, destinations)
    val checkout = checkoutStep(config, pipeline)
    command match
      case None =>
        checkout :: capability.extraSteps(ctx) ++ capability.postSteps(ctx)
      case Some(cmd) =>
        val onMatrixLeg =
          if matrixAxes.contains("scala") || (hasMatrix && matrixAxes.isEmpty && capability.matrixed) then
            underMatrixScala
          else identity[SbtCommand]
        val commandStep =
          if capability.phase == Phase.Verify then verifyCommandStep(capability.name, onMatrixLeg, cmd, config)
          else Step.run(Script(onMatrixLeg(cmd).render)).named(capability.name).build
        val cacheMode =
          if config.cache == CacheBackend.LocalDir && cache.steps.isEmpty then capability.localCache
          else LocalCacheMode.Off
        // An image is pushed at most once per commit: a rebuild is not byte-identical, and an immutable-tag registry
        // rejects the second push.
        val publish = pipeline match
          case Pipeline.Deploy(_) if DeployWorkflow.isImage(capability) =>
            List(DeployWorkflow.imageTagCheck(module), andStepIf(commandStep, Some(DeployWorkflow.imageMissing)))
          case _ => List(commandStep)
        // Local composites need the workspace on disk before `uses: ./.github/actions/…` can resolve.
        checkout :: ZipxComposites.sbtSetupStep(config, jobSuffix, capability.nodeVersion, cacheMode) ::
          cache.steps ++ capability.extraSteps(ctx) ++ publish ++ capability.postSteps(ctx)
    end match
  end stepsFor

  /** Full-history checkout, then [[ZipxComposites.sbtSetupStep]]. Order is load-bearing for local composites. */
  private[core] def checkoutThenSbtSetup(
      config: PlanConfig,
      jobSuffix: JobId,
      nodeVersion: Option[NodeVersion],
      cacheMode: LocalCacheMode,
  ): List[Step] =
    List(
      checkoutStep(config),
      ZipxComposites.sbtSetupStep(config, jobSuffix, nodeVersion, cacheMode),
    )

  private def checkoutStep(config: PlanConfig, pipeline: Pipeline = Pipeline.Ci): Step =
    val ref = pipeline match
      case Pipeline.Ci        => ListMap.empty
      case Pipeline.Deploy(_) => ListMap("ref" -> DeployWorkflow.shaOutput.render)
    Step(uses = Some(config.actions.checkout), `with` = ref ++ checkoutWith)

  private def verifyCommandStep(
      name: String,
      onMatrixLeg: SbtCommand => SbtCommand,
      command: SbtCommand,
      config: PlanConfig,
  ): Step =
    def sbtStep(command: SbtCommand): Command = onMatrixLeg(command).render
    config.verifyClean match
      case VerifyClean.None =>
        config.verifyCleanLabel match
          case Some(label) =>
            // Left wrapped, unlike a job `if:`: an `env:` entry is a plain field, so the runner substitutes the
            // expression to the string the script below compares against.
            val labelled = PlanConfig.pullRequestHasLabel(label)
            Step
              .run(
                Script(
                  If(
                    ShTest.StrEq(Word.Dquote(List(Word.VarRef(verifyCleanFullVar))), Word.quoted("true")),
                    Block(sbtStep(VerifyClean.CleanFull.prefixCommand(command))),
                    elseDo = Some(Block(sbtStep(command))),
                  )
                )
              )
              .named(name)
              .withEnvName(verifyCleanFullName, labelled)
              .build
          case None =>
            Step.run(Script(sbtStep(command))).named(name).build
      case mode =>
        Step.run(Script(sbtStep(mode.prefixCommand(command)))).named(name).build
    end match
  end verifyCommandStep

  /** `test` → `++${{ matrix.scala }}; test`, so a matrixed job's one leg runs under its own Scala version. */
  private def underMatrixScala(command: SbtCommand): SbtCommand =
    SbtCommand.underScalaVersion(Expr.matrix("scala"), command)

  // One name as both the `env:` key and the shell variable the generated script reads.
  private val verifyCleanFullName = EnvName("ZIPX_VERIFY_CLEAN_FULL")
  private val verifyCleanFullVar  = VarName("ZIPX_VERIFY_CLEAN_FULL")

  private case class CacheContribution(
      steps: List[Step] = Nil,
      services: ListMap[String, JobService] = ListMap.empty,
      env: ListMap[String, String] = ListMap.empty,
  )

  /** Full history + tags so affected diffs and [[CacheEpoch.GitTags]] can see release tags. */
  private val checkoutWith: ListMap[String, String] =
    ListMap("fetch-depth" -> "0", "fetch-tags" -> "true")

  /** A `-SNAPSHOT` epoch continues a release, so its first restore fallback is that release's bare epoch. */
  private[core] def priorReleaseEpochKey(prefix: String, cacheEpoch: String): Option[String] =
    Option
      .when(cacheEpoch.endsWith(Modver.UnreleasedSuffix))(cacheEpoch.stripSuffix(Modver.UnreleasedSuffix))
      .filter(_.nonEmpty)
      .map(e => s"$prefix$e-")

  private def cacheContribution(config: PlanConfig): CacheContribution =
    config.cache match
      case CacheBackend.LocalDir =>
        CacheContribution()

      case CacheBackend.BazelRemoteSidecar(image, port) =>
        CacheContribution(
          services = ListMap(
            RemoteCacheProof.serviceName -> JobService(
              image = image,
              ports = List(s"$port:$port"),
              // The image's entrypoint is already bazel-remote; `max_size` is in GiB.
              options = Some("--max_size=1"),
            )
          ),
          env = ListMap(RemoteCacheProof.envUri -> s"grpc://localhost:$port"),
        )

      case CacheBackend.ManagedRemote(uri, headerSecret) =>
        CacheContribution(
          env = ListMap(
            RemoteCacheProof.envUri    -> uri,
            RemoteCacheProof.envHeader -> EnvValue.FromSecret(headerSecret).render,
          )
        )

end Planner
