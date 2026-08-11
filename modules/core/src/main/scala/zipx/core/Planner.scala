package zipx.core

import neotype.unwrap
import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Maps a [[ModuleGraph]] + capabilities + [[PlanConfig]] to a GitHub Actions [[zipx.workflow.Workflow]], with no sbt
  * dependency. Env maps are merged plan → cache → capability → target, so a target wins every clash.
  */
object Planner:

  // Every id below is *built* from validated pieces rather than assembled as text and validated afterwards: a
  // `CapabilityName`, a `TargetName` and a `ModuleId` all satisfy `Names.ActionsId`, and `-` joins two such strings into
  // a third. That is the whole reason those three types exist, and it is what makes this file free of a job-id failure
  // case to report.

  def jobId(capability: Capability, moduleId: ModuleId): JobId = capability.name.jobId(moduleId)

  def jobId(capability: Capability, moduleId: ModuleId, target: Target): JobId =
    capability.name.jobId(moduleId, target.name)

  def aggregateTargetJobId(capability: Capability, target: Target): JobId =
    capability.name.jobId(target.name)

  /** `L<index>` is an ASCII letter followed by digits, so it is an `ActionsId` segment like the others. */
  def layerJobId(capability: Capability, layerIndex: Int): JobId =
    capability.name.jobId(s"L$layerIndex")

  /** One Layer job per toposort wave and target under [[TargetFanOut.JobPerTarget]]. */
  def layerTargetJobId(capability: Capability, layerIndex: Int, target: Target): JobId =
    capability.name.jobId(s"L$layerIndex", target.name)

  /** Every job id a capability produces, which is how one capability's `needs` names another's jobs.
    *
    * When [[MatrixCollapse.effective]] is not [[MatrixCollapse.Off]], returns the collapsed job id(s) the emitter will
    * produce (eligibility failures still abort [[plan]] before dependents are wired).
    */
  def allJobIds(capability: Capability, graph: ModuleGraph, config: PlanConfig = PlanConfig()): List[JobId] =
    val mode = MatrixCollapse.effective(capability, config)
    capability.scope match
      case CapabilityScope.Once      => List(capability.name.asJobId)
      case CapabilityScope.Aggregate =>
        distinctFannedTargets(capability, graph) match
          case Nil                             => List(capability.name.asJobId)
          case _ if mode != MatrixCollapse.Off => List(capability.name.asJobId)
          case targets                         => targets.map(t => aggregateTargetJobId(capability, t))
      case CapabilityScope.Layer =>
        val layers = graph.subsetLayers(capability.participates)
        distinctFannedTargets(capability, graph) match
          case Nil                             => layers.indices.map(i => layerJobId(capability, i)).toList
          case _ if mode != MatrixCollapse.Off => layers.indices.map(i => layerJobId(capability, i)).toList
          case targets                         =>
            (for
              i <- layers.indices
              t <- targets
            yield layerTargetJobId(capability, i, t)).toList
      case CapabilityScope.Graph =>
        mode match
          case MatrixCollapse.Off =>
            graph.nodes
              .filter(capability.participates)
              .flatMap(node => jobIdsForGraph(capability, node))
              .distinct
              .sorted
          case _ => List(capability.name.asJobId)
    end match
  end allJobIds

  private def jobIdsForGraph(capability: Capability, node: ModuleNode): List[JobId] =
    fannedTargets(capability, node) match
      case Nil     => List(jobId(capability, node.id))
      case targets => targets.sortBy(_.name).map(t => jobId(capability, node.id, t))

  /** The targets that produce a job of their own, so `Nil` for a [[TargetFanOut.SharedJob]] capability: its
    * destinations share the job a capability with no targets would have had, and so its job ids too. That is what keeps
    * a `needsCapabilities` edge onto it correct without every caller knowing about fan-out.
    */
  private def fannedTargets(capability: Capability, node: ModuleNode): List[Target] =
    capability.targetFanOut match
      case TargetFanOut.JobPerTarget => capability.targets(node)
      case TargetFanOut.SharedJob    => Nil

  /** Every destination one [[TargetFanOut.SharedJob]] job serves, `Nil` under [[TargetFanOut.JobPerTarget]]. The
    * complement of [[fannedTargets]]: exactly one of the two is non-empty for a capability with targets.
    */
  private def sharedTargets(capability: Capability, node: ModuleNode): List[Target] =
    capability.targetFanOut match
      case TargetFanOut.JobPerTarget => Nil
      case TargetFanOut.SharedJob    => capability.targets(node).sortBy(_.name)

  /** [[distinctTargets]] restricted to the targets that get a job of their own; see [[fannedTargets]]. */
  private def distinctFannedTargets(capability: Capability, graph: ModuleGraph): List[Target] =
    capability.targetFanOut match
      case TargetFanOut.JobPerTarget => distinctTargets(capability, graph)
      case TargetFanOut.SharedJob    => Nil

  /** Deduplicated by name, first-seen winning, so two modules naming `prod` differently do not produce two `prod` jobs.
    */
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

  /** One sbt session per job, the point of the Aggregate and Layer scopes. `None` when there are no commands to join
    * (no participating nodes, or every node's command is action-only).
    */
  private def joinCommands(capability: Capability, nodes: List[ModuleNode]): Option[SbtCommand] =
    if !capability.command.runsSbt then None
    else SbtCommand.join(nodes.map(n => capability.command.commandFor(n)))

  /** Cache sidecar / env only when the job will run sbt; action-only jobs get an empty contribution. */
  private def cacheForCommand(config: PlanConfig, hasCommand: Boolean): CacheContribution =
    if hasCommand then cacheContribution(config) else CacheContribution()

  /** Rejects a `needsCapabilities` cycle, [[Gate.AffectedOnly]] (an unimplemented seam: honoring it silently as
    * [[Gate.Always]] would emit a green pipeline that runs nothing it was asked to run), a per-destination field a
    * [[TargetFanOut.SharedJob]] job cannot honor, a gate/condition conjunction that can never be true, and a non-Graph
    * consumer of an affected-gated publish, which would run against an artifact nobody built.
    */
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

    capabilities.foreach(validateWorkflowCall)
    capabilities.foreach(c => validateSharedTargets(c, graph))
    capabilities.foreach(c => validateSatisfiable(c, graph))
    capabilities.foreach(validateSessionTail)
    validateSkipConsumers(capabilities, config)
  end validateCapabilities

  /** Rejects [[Capability.sessionTail]] on scopes where a tail would run too often or with a partial module set. */
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
            "Use Aggregate (or ZipxCentral.release) / Once, or Graph + releaseOnce",
          )
        case CapabilityScope.Graph =>
          fail(
            "Graph runs one session per module, so the tail would run once per module",
            "Use Aggregate / Once, or Graph publishSigned + releaseOnce",
          )
        case CapabilityScope.Aggregate | CapabilityScope.Once => ()
      end match
    }
  end validateSessionTail

  /** Rejects an [[CapabilityScope.Aggregate]] or [[CapabilityScope.Layer]] capability that needs an affected-gated
    * Graph one.
    *
    * The trap this closes: [[Capability.deploy]] needs [[Capability.DockerName]] by default and is Aggregate by
    * default, so turning on [[PlanConfig.affectedPublish]] leaves the deploy *running* beside a skipped
    * `docker-<module>`, pulling an image tag that publish never pushed. `tolerateSkips` is what makes it run, and that
    * tolerance is right for an Aggregate job (it spans every module, so one module's skip cannot cancel the others'
    * work), which is exactly why the combination has to be refused here instead of softened there.
    *
    * Three scopes, three different answers, and the difference is whether the job's command names modules:
    *   - [[CapabilityScope.Graph]] is fine: it carries the same per-module affected expression as its producer, so the
    *     two skip together. That is the shape this error points at.
    *   - Aggregate and Layer join *several* modules' commands into one job, so a skipped producer leaves that job
    *     naming a module whose artifact does not exist, with no way to drop one command from an already-joined session.
    *   - [[CapabilityScope.Once]] runs a fixed build-wide command that names no module, so a skipped producer costs it
    *     nothing it was going to use. An `announce` that needs `publish` is not broken by one module not publishing.
    */
  private def validateSkipConsumers(capabilities: List[Capability], config: PlanConfig): Unit =
    val gatedGraphNames =
      capabilities
        .filter(c => c.scope == CapabilityScope.Graph && affectedGatedPhase(c.phase, config))
        .map(_.name)
        .toSet

    // Verify is always gated, and a Verify producer is not something a later phase consumes an artifact from, so
    // restricting to the opt-in phases keeps this from firing on every build that needs `test`.
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

  /** Rejects [[Capability.container]] or [[Capability.services]] on a [[Capability.workflowCall]] capability.
    *
    * A `uses:` job delegates its whole runtime to the called workflow, so GitHub rejects `container:` and `services:`
    * beside it. `onceJob` therefore has no place to put either, and dropping them silently would leave a job with no
    * sidecar its steps expect. The called workflow declares its own.
    */
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

  /** Rejects a [[Target.condition]] or [[Target.environment]] on a [[TargetFanOut.SharedJob]] destination.
    *
    * One job has one `if:` and binds one Environment, so there is no honest way to apply a per-destination one:
    * dropping it would push to a registry the author said to skip, and applying it to the whole job would skip the
    * destinations that were fine. Either is a silent wrong answer, so this is an error naming both fields and the
    * alternative.
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

  /** Rejects a job whose `if:` the planner would render never-true, per [[Satisfiable]].
    *
    * Checked per (capability, target) rather than per capability, because the gate, the capability condition and the
    * target condition come from three different files and only their conjunction is wrong. That is precisely how
    * `examples/monorepo` shipped a `deploy-prod` job gated on `refs/tags/v*` *and* `refs/heads/main`.
    *
    * Targets are collected over the graph's nodes, since `Capability.targets` is a function of a node.
    */
  private def validateSatisfiable(capability: Capability, graph: ModuleGraph): Unit =
    val gate = Option.when(capability.gate == Gate.OnReleaseTag)(
      Satisfiable.Clause("Gate.OnReleaseTag", JobCondition.onReleaseTag)
    )
    val own = capability.condition.map(Satisfiable.Clause(s"capability '${capability.name}' condition", _))

    def refuse(where: String, problem: String): Nothing =
      sys.error(s"zipx: $where can never run: $problem")

    Satisfiable
      .contradiction(gate.toList ++ own.toList)
      .foreach(problem => refuse(s"capability '${capability.name}'", problem))

    val targets = graph.nodes.filter(capability.participates).flatMap(capability.targets).distinctBy(_.name)
    targets.foreach { target =>
      target.condition.foreach { condition =>
        val clause = Satisfiable.Clause(s"target '${target.name}' condition", condition)
        Satisfiable
          .contradiction(gate.toList ++ own.toList :+ clause)
          .foreach(problem => refuse(s"capability '${capability.name}' target '${target.name}'", problem))
      }
    }
  end validateSatisfiable

  // The three jobs zipx invents. `JobId` is a subtype of `String`, so one val serves both roles these had to be split
  // for before: the operand of `Expr.JobOutput` / `Expr.JobResult`, which take a validated value, and the `jobs` key.
  val affectedJobId: JobId       = JobId("affected")
  val verifyGateJobId: JobId     = JobId("verify-gate")
  val cacheRehydrateJobId: JobId = JobId("cache-rehydrate")

  /** Whether a phase's Graph jobs may be narrowed to the affected modules.
    *
    * [[Phase.Verify]] always may; [[Phase.Publish]] only under [[PlanConfig.affectedPublish]]; [[Phase.Deploy]] only
    * under [[PlanConfig.affectedDeploy]].
    *
    * Verify is not opt-in and the other two are, because the failures are not symmetric: **under-verifying is silently
    * unsafe** (the PR is green and the code was never tested), while **under-publishing is loudly broken** (the deploy
    * that wants the missing artifact fails immediately). Verify's default is the safe one; the savings on the later
    * phases are real but have to be asked for.
    *
    * Deploy's own knob rather than [[PlanConfig.affectedPublish]] widened to cover it: narrowing image pushes while
    * still reconciling every destination on every run is a legitimate combination, and one switch would take it away.
    * Note that only Graph scope is ever gated (see the `usesAffected` filters below), so an Aggregate deploy is
    * unaffected by this either way.
    */
  private def affectedGatedPhase(phase: Phase, config: PlanConfig): Boolean = phase match
    case Phase.Verify  => true
    case Phase.Publish => config.affectedPublish
    case Phase.Deploy  => config.affectedDeploy

  def plan(graph: ModuleGraph, capabilities: List[Capability], config: PlanConfig): Workflow =
    validateCapabilities(capabilities, graph, config)

    // Affected-gating is per-module, so only a Graph capability can narrow anything: an Aggregate job runs one sbt
    // session over every module, and there is nothing there to skip.
    val usesAffected =
      config.affected == AffectedMode.AffectedOnPR &&
        capabilities.exists(c => affectedGatedPhase(c.phase, config) && c.scope == CapabilityScope.Graph)

    // Publish and Deploy jobs run on a release tag, where Verify does not, so the `affected` job they now depend on has
    // to run there too. It emits the `all` sentinel for a tag push already (see `affectedScript`), which is what makes a
    // release publish and deploy everything regardless of any diff.
    //
    // Both phases, not just Publish: a tag-gated Graph deploy would otherwise carry `needs: affected` and an expression
    // reading its output on a ref where that job does not exist.
    val affectedOnTags =
      usesAffected && List(Phase.Publish, Phase.Deploy).exists(phase =>
        affectedGatedPhase(phase, config) && capabilities.exists(c =>
          c.phase == phase && c.scope == CapabilityScope.Graph
        )
      )

    val hasVerify          = capabilities.exists(_.phase == Phase.Verify)
    val usesVerifyGate     = config.skipMergedPrPush && hasVerify
    val usesCacheRehydrate =
      usesVerifyGate && config.cacheRehydrateOnMerge && config.cache == CacheBackend.LocalDir

    val byName = capabilities.map(c => c.name -> c).toMap

    // The capabilities whose jobs can *skip* rather than fail, which is what a dependent has to tolerate. Only Graph
    // scope, matching `gatedOnAffected` below: an Aggregate or Layer job is never affected-gated, so it never skips.
    val affectedGatedNames =
      if !usesAffected then Set.empty[CapabilityName]
      else
        capabilities
          .filter(c => c.scope == CapabilityScope.Graph && affectedGatedPhase(c.phase, config))
          .map(_.name)
          .toSet

    val topoOrder      = graph.topologicalSort
    val orderedCaps    = capabilities.zipWithIndex.sortBy((c, i) => (c.phase.ordinal, i)).map(_._1)
    val capabilityJobs =
      orderedCaps.flatMap { c =>
        val mode = MatrixCollapse.effective(c, config)
        c.scope match
          case CapabilityScope.Once =>
            List(onceJob(c, graph, config, byName, usesVerifyGate, affectedGatedNames))
          case CapabilityScope.Aggregate =>
            aggregateJobs(c, graph, config, byName, usesVerifyGate, affectedGatedNames, mode)
          case CapabilityScope.Layer =>
            layerJobs(c, graph, config, byName, usesVerifyGate, affectedGatedNames, mode)
          case CapabilityScope.Graph =>
            mode match
              case MatrixCollapse.Off =>
                for
                  moduleId <- topoOrder
                  node     <- graph.get(moduleId).toList
                  if c.participates(node)
                  job <- graphJobsFor(c, node, graph, config, usesAffected, byName, usesVerifyGate, affectedGatedNames)
                yield job
              case collapse =>
                graphMatrixJobs(c, graph, config, usesAffected, byName, usesVerifyGate, affectedGatedNames, collapse)
        end match
      }

    val leading =
      List(
        Option.when(usesVerifyGate)(verifyGateJobId         -> verifyGateJob(config)),
        Option.when(usesCacheRehydrate)(cacheRehydrateJobId -> cacheRehydrateJob(config)),
        Option.when(usesAffected)(
          affectedJobId -> affectedSetupJob(config, usesVerifyGate, affectedOnTags)
        ),
      ).flatten

    // Widening the key to `String` is the last responsible moment: `Workflow` is the serialization model, and a
    // `jobs:` key is a YAML scalar. Every id above is a `JobId`, which is what the widening is allowed to forget.
    val jobs = ListMap.from[String, Job](leading ++ capabilityJobs)

    Workflow(
      name = config.workflowName,
      on = triggersFor(config, capabilities),
      jobs = jobs,
      concurrency = Option.when(config.cancelSupersededRuns)(concurrencyFor(config)),
    )
  end plan

  /** The group folds in the workflow name so sibling workflows never contend, and `github.ref` so a PR's pushes cancel
    * each other while other branches are untouched. `cancel-in-progress` is an expression rather than `true` because
    * publishing is not idempotent: a half-cancelled release-tag run can leave a staged-but-unreleased Central bundle
    * behind, which is worse than a wasted runner.
    */
  private def concurrencyFor(config: PlanConfig): Concurrency =
    Concurrency(
      group = (lit(config.workflowName + "-") ++ Expr.github("ref")).render,
      // `render`, not `unwrapped`: `cancel-in-progress` is a plain field, so the expression needs its `${{ }}`.
      cancelInProgress = (!onAnyTagPush).render,
    )

  /** Deliberately broader than [[JobCondition.onReleaseTag]] (`refs/tags/v`): Verify is skipped and cancellation
    * disabled for *every* tag, since a tag push is never what Verify exists to check, while only a `v` tag publishes.
    */
  private val onAnyTagPush: Expr =
    Expr.startsWith(Expr.github("ref"), Expr.quoted("refs/tags/"))

  /** The `if:` expression form. [[eventIs]] is the shell-test form of the same question, for a `run:` script. */
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

  /** Asks the API whether this SHA landed via a PR merged into the same branch. Merge and squash both associate the
    * landed commit with the merged PR; a direct push does not.
    *
    * The `--jq` filter is a double-quoted shell argument containing a *nested* double-quoted jq string, so the inner
    * quotes must reach jq as `\"`. A `Word.Dquote` inside another `Dquote` renders exactly that, which is why no
    * backslashes are counted by hand here.
    */
  private def verifyGateScript: Script =
    val jqFilter = Word.dquote(
      Word.lit("[.[] | select(.merged_at != null and .base.ref == "),
      Word.dquote(Expr.github("ref_name").asWord),
      Word.lit(")] | length"),
    )
    Script(
      Comment("Commits landed by merging/squashing a PR are associated with that PR via the API."),
      Assign(
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

  /** A minimal LocalDir restore/save, so the default branch still gets an `actions/cache` entry that later PRs can
    * restore from when verify-gate skipped Verify. Fail-closed, unlike Verify itself: it runs only when the gate
    * *succeeded* with `run=false`.
    */
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
      steps = List(
        Step(uses = Some(config.actions.checkout), `with` = checkoutWith)
      ) ++ jdkAndSbtSteps(config, None) ++ localDirCacheSteps(config, cacheRehydrateJobId) ++
        config.cacheRehydrateExtraSteps(ctx) ++ List(
          Step.run(Script(config.cacheRehydrateTask.render)).named(cacheRehydrateJobId).build
        ),
    )
  end cacheRehydrateJob

  /** Verify never runs on a tag push (a release tag only needs Publish and Deploy) or a `workflow_dispatch` (a manual
    * run is for a docs-only deploy). Non-Verify phases pass through untouched.
    *
    * @param excludeTagsAndDispatch
    *   `false` keeps the merged-PR skip but drops the tag/dispatch exclusion, for the one job that is Verify-shaped and
    *   yet has to run on a tag: the `affected` setup job, once Publish reads its output too.
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
        // Fail-open: run when the gate said yes, or when the gate itself did not succeed. `!cancelled()` is what keeps
        // this reachable when the gate was skipped entirely.
        val gateCond = notOnTagOrDispatch.foldLeft(!Expr.cancelled)(_ && _) && Expr.group(
          Expr.group(verifyGateResult !== Expr.quoted("success")) ||
            Expr.group(verifyGateRuns === Expr.quoted("true"))
        )
        (gatedNeeds, andConditions(Some(gateCond.unwrapped), cond))
      end if

  /** @param runsOnTags
    *   an affected-gated Publish job runs on a release tag, so the job it reads its module list from has to as well.
    *   Cheap: on a tag push [[affectedScript]] takes no diff at all, it emits the `all` sentinel directly, which is
    *   what makes a release publish everything.
    */
  private def affectedSetupJob(config: PlanConfig, usesVerifyGate: Boolean, runsOnTags: Boolean): Job =
    val (needs, cond) =
      applyVerifyGate(Nil, None, Phase.Verify, usesVerifyGate, excludeTagsAndDispatch = !runsOnTags)
    Job(
      name = Some("affected"),
      runsOn = List(config.runnerOs),
      needs = needs,
      `if` = cond,
      env = EnvValue.renderAll(config.env),
      outputs = ListMap("modules" -> Expr.stepOutput("compute", "modules").render),
      steps = List(
        Step(uses = Some(config.actions.checkout), `with` = checkoutWith)
      ) ++ jdkAndSbtSteps(config, None) ++ List(
        Step
          .run(affectedScript(config.affectedOnPush))
          .withId("compute")
          .named("Compute affected modules")
          .build
      ),
    )
  end affectedSetupJob

  private def affectedScript(affectedOnPush: Boolean): Script =
    // sbt writes the answer to a file rather than stdout, because sbt 2 prints server banners and `modules=$(sbt …)`
    // would put them in GITHUB_OUTPUT.
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
    val releases = capabilities.exists(_.gate == Gate.OnReleaseTag)
    Triggers(
      push = Some(
        BranchFilter(
          branches = config.pushBranches,
          tags = if releases then List(config.releaseTagPattern) else Nil,
        )
      ),
      pullRequest = Some(BranchFilter()),
      workflowDispatch = config.workflowDispatch,
    )
  end triggersFor

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
      affectedGatedNames: Set[CapabilityName],
  ): (JobId, Job) =
    val releaseCond   = Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
    val crossNeeds    = crossCapabilityNeeds(capability, graph, byName, config)
    val tolerance     = tolerateSkips(capability, crossNeeds, affectedGatedNames)
    val (needs, base) =
      applyVerifyGate(crossNeeds, andConditions(tolerance, releaseCond), capability.phase, usesVerifyGate)
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

  private def aggregateJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      affectedGatedNames: Set[CapabilityName],
      mode: MatrixCollapse,
  ): List[(JobId, Job)] =
    val nodes = participants(capability, graph)
    if nodes.isEmpty then Nil
    else
      val crossNeeds  = crossCapabilityNeeds(capability, graph, byName, config)
      val joined      = joinCommands(capability, nodes)
      val cache       = cacheForCommand(config, joined.isDefined)
      val runner      = capability.runsOn.getOrElse(List(config.runnerOs))
      val releaseCond =
        Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
      val tolerance              = tolerateSkips(capability, crossNeeds, affectedGatedNames)
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
        case targets if mode != MatrixCollapse.Off =>
          MatrixCollapse
            .targetsAllowSimpleMatrix(targets)
            .left
            .foreach(err => sys.error(s"zipx: capability '${capability.name}': $err"))
          val sharedCond = targets.headOption.flatMap(t => JobCondition.renderOpt(t.condition))
          val envBinding =
            Option.when(targets.exists(_.environment.isDefined))(Expr.matrix("target").render)
          List(
            capability.name.asJobId -> Job(
              name = Some(capability.name),
              runsOn = runner,
              needs = baseNeeds,
              `if` = andConditions(baseCond, sharedCond),
              environment = envBinding,
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
        case targets =>
          targets.map { target =>
            val id = aggregateTargetJobId(capability, target)
            id -> Job(
              name = Some(s"${capability.name} (${target.name})"),
              runsOn = runner,
              needs = baseNeeds,
              `if` = andConditions(baseCond, JobCondition.renderOpt(target.condition)),
              environment = target.environment,
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
    end if
  end aggregateJobs

  private def layerJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      affectedGatedNames: Set[CapabilityName],
      mode: MatrixCollapse,
  ): List[(JobId, Job)] =
    val layers = graph.subsetLayers(capability.participates)
    if layers.isEmpty then Nil
    else
      val crossNeeds  = crossCapabilityNeeds(capability, graph, byName, config)
      val runner      = capability.runsOn.getOrElse(List(config.runnerOs))
      val releaseCond =
        Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
      val tolerance = tolerateSkips(capability, crossNeeds, affectedGatedNames)
      val shared    = capability.targetFanOut match
        case TargetFanOut.JobPerTarget => Nil
        case TargetFanOut.SharedJob    => distinctTargets(capability, graph)
      val fanned = distinctFannedTargets(capability, graph)
      if mode != MatrixCollapse.Off && fanned.nonEmpty then
        MatrixCollapse
          .targetsAllowSimpleMatrix(fanned)
          .left
          .foreach(err => sys.error(s"zipx: capability '${capability.name}': $err"))

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
            (prev ++ (if firstWave then crossNeeds else Nil)).distinct.sorted
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
            environment = environment,
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
          case targets if mode != MatrixCollapse.Off =>
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
          case targets =>
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

  /** One Graph job with `strategy.matrix` over modules (and optionally targets under Coarse). */
  private def graphMatrixJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      usesAffected: Boolean,
      byName: Map[CapabilityName, Capability],
      usesVerifyGate: Boolean,
      affectedGatedNames: Set[CapabilityName],
      mode: MatrixCollapse,
  ): List[(JobId, Job)] =
    val nodes = participants(capability, graph)
    if nodes.isEmpty then Nil
    else
      val fannedSample = fannedTargets(capability, nodes.head)
      val foldTargets  = fannedSample.nonEmpty
      if foldTargets && mode == MatrixCollapse.Strict then
        sys.error(
          s"zipx: capability '${capability.name}' is MatrixCollapse.Strict with JobPerTarget fan-out; " +
            "Strict collapses the module axis only when targets are empty or SharedJob. Use Coarse for " +
            "module × target, or SharedJob / no targets for Strict."
        )
      if foldTargets then
        val targetSets = nodes.map(n => capability.targets(n).map(_.name).sorted)
        if targetSets.distinct.sizeIs > 1 then
          sys.error(
            s"zipx: capability '${capability.name}': participating modules have divergent target sets; " +
              "refuse matrix collapse"
          )
        MatrixCollapse
          .targetsAllowSimpleMatrix(distinctTargets(capability, graph))
          .left
          .foreach(err => sys.error(s"zipx: capability '${capability.name}': $err"))
      end if

      if mode == MatrixCollapse.Strict && MatrixCollapse.hasSameCapInterModuleNeeds(capability, nodes, graph) then
        sys.error(
          s"zipx: capability '${capability.name}' is MatrixCollapse.Strict but participating modules have " +
            "same-capability inter-module needs. Use Coarse to drop those needs, or leave collapse Off."
        )

      val matrixCommands = nodes.map { n =>
        MatrixCollapse
          .underMatrixModule(n, capability.command.commandFor(n))
          .fold(err => sys.error(s"zipx: capability '${capability.name}': $err"), identity)
      }
      val commandOverride =
        matrixCommands.distinct match
          case List(one) => Some(one)
          case other     =>
            sys.error(
              s"zipx: capability '${capability.name}': module commands are not isomorphic under matrix.module " +
                s"(${other.map(_.text).mkString(" vs ")})"
            )

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

      val moduleNames                              = nodes.map(_.id: String)
      val targets                                  = if foldTargets then distinctTargets(capability, graph) else Nil
      val matrixMap: ListMap[String, List[String]] =
        ListMap("module" -> moduleNames) ++
          (if targets.nonEmpty then ListMap("target" -> targets.map(_.name: String)) else ListMap.empty) ++
          scalaAxis.fold(ListMap.empty[String, List[String]])(v => ListMap("scala" -> v))

      val crossNeeds =
        for
          capName <- capability.needsCapabilities
          dep     <- byName.get(capName).toList
          id      <- allJobIds(dep, graph, config)
        yield id

      val gatedOnAffected = usesAffected && affectedGatedPhase(capability.phase, config)
      val rawNeeds        =
        (crossNeeds ++ (if gatedOnAffected then List(affectedJobId) else Nil)).distinct.sorted
      val cache        = cacheForCommand(config, commandOverride.isDefined)
      val guardedNeeds = rawNeeds.filterNot(id => id == affectedJobId || id == verifyGateJobId)
      val skipTolerant = dependsOnSkippable(capability, affectedGatedNames)
      val releaseGate  =
        Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
      // Job-level `if` cannot use `matrix.*` (GitHub rejects the workflow). Skip the whole job when
      // affected found nothing; per-leg membership is enforced on each step below.
      val affectedGate =
        Option.when(gatedOnAffected)(affectedModulesNonEmpty.unwrapped)
      val stepAffectedGate =
        Option.when(gatedOnAffected)(
          Expr.group(affectedContainsMatrixModule || affectedContainsAll).unwrapped
        )
      val tolerance =
        if gatedOnAffected || skipTolerant then skipTolerantClauses(guardedNeeds) else Nil
      val clauses        = tolerance.headOption.toList ++ releaseGate.toList ++ affectedGate.toList ++ tolerance.drop(1)
      val baseCond       = if clauses.isEmpty then None else Some(clauses.mkString(" && "))
      val (needs, gated) =
        if gatedOnAffected then applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate = false)
        else applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate)
      val targetCond =
        targets.headOption.flatMap(t => JobCondition.renderOpt(t.condition))
      val cond      = andConditions(andConditions(gated, JobCondition.renderOpt(capability.condition)), targetCond)
      val runner    = capability.runsOn.getOrElse(List(config.runnerOs))
      val shared    = sharedTargets(capability, nodes.head)
      val targetEnv =
        if targets.nonEmpty then MatrixCollapse.collapsedTargetEnv(targets)
        else sharedEnv(shared)
      val envBinding =
        Option.when(targets.exists(_.environment.isDefined))(Expr.matrix("target").render)
      val axes  = matrixMap.keySet
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
      ).map(andStepIf(_, stepAffectedGate))

      List(
        capability.name.asJobId -> Job(
          name = Some(capability.name),
          runsOn = runner,
          needs = needs,
          `if` = cond,
          environment = envBinding,
          permissions = ListMap.from(capability.permissions),
          strategy = Some(Strategy(matrix = matrixMap)),
          container = capability.container,
          services = mergeServices(capability, cache),
          env = mergeEnv(config.env, cache.env, capability.env, targetEnv),
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
      affectedGatedNames: Set[CapabilityName],
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

    val gatedOnAffected = usesAffected && affectedGatedPhase(capability.phase, config)
    val rawNeeds        =
      (upstreamNeeds ++ crossNeeds ++ (if gatedOnAffected then List(affectedJobId) else Nil)).distinct.sorted

    val matrix =
      if capability.matrixed && config.scalaMatrix && node.crossScalaVersions.sizeIs > 1 then
        Some(Strategy(matrix = ListMap("scala" -> node.crossScalaVersions)))
      else None

    val cache = cacheForCommand(config, capability.command.runsSbt)
    // Every need except the two jobs with a clause of their own: `affected` is read through its *output*, and
    // `verify-gate` through `applyVerifyGate`. That includes `crossNeeds`, so a failed `fmt` still blocks the tests
    // whose `!cancelled()` would otherwise let them through.
    val guardedNeeds = rawNeeds.filterNot(id => id == affectedJobId || id == verifyGateJobId)
    val skipTolerant = dependsOnSkippable(capability, affectedGatedNames)
    val baseCond     = jobCondition(capability, node, guardedNeeds, gatedOnAffected, skipTolerant)
    // The affected setup job already needs verify-gate, so a gated-on-affected job inherits the skip through it and asks
    // only for the tag exclusion.
    val (needs, gated) =
      if gatedOnAffected then applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate = false)
      else applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate)
    val cond   = andConditions(gated, JobCondition.renderOpt(capability.condition))
    val runner = capability.runsOn.getOrElse(List(config.runnerOs))

    def baseJob(
        id: JobId,
        displayName: String,
        target: Option[Target],
        cond: Option[String],
        environment: Option[String],
        targetEnv: Map[String, EnvValue],
        destinations: List[Target] = Nil,
    ): (JobId, Job) =
      id -> Job(
        name = Some(displayName),
        runsOn = runner,
        needs = needs,
        `if` = cond,
        environment = environment,
        permissions = ListMap.from(capability.permissions),
        strategy = matrix,
        container = capability.container,
        services = mergeServices(capability, cache),
        env = mergeEnv(config.env, cache.env, capability.env, targetEnv),
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
        ),
      )

    fannedTargets(capability, node) match
      case Nil =>
        // Shared destinations, if any, ride along in this one job: `sharedTargets` is `Nil` unless the capability
        // asked for `TargetFanOut.SharedJob`, in which case `fannedTargets` above is what is empty.
        val shared = sharedTargets(capability, node)
        List(
          baseJob(
            jobId(capability, node.id),
            s"${capability.name} ${node.id}",
            None,
            cond,
            None,
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
            andConditions(cond, JobCondition.renderOpt(target.condition)),
            target.environment,
            target.env,
          )
        }
    end match
  end graphJobsFor

  /** Every shared destination's `env` under its own prefix, so several accounts' values coexist in one job.
    *
    * Prefixing rather than merging is what makes the shape safe: two registries both wanting `AWS_ROLE_TO_ASSUME` would
    * otherwise silently keep whichever `++` saw last, and the job would push twice to one account. A step reads a value
    * back with [[Target.envKey]], so neither side writes the prefix out.
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

  /** A capability's sidecars plus the cache backend's, **cache winning** a colliding service id.
    *
    * Not `++` order by accident: a build cannot function without its cache sidecar, since the sbt invocation is
    * configured to reach it, while a capability's own sidecar is something its test code connects to and can therefore
    * report a connection failure about. Losing the cache one instead would make every job in the workflow fail on a
    * name nobody chose deliberately.
    *
    * Cache-backend ids are zipx's own (`RemoteCacheProof.serviceName`), so a collision means a capability picked the
    * same id, which the docs name.
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

  /** The clauses that let a job tolerate a **skipped** need while still failing on a **failed** one.
    *
    * `!cancelled()` is what makes the job reachable at all once a need can skip, since GitHub's implicit `success()`
    * would block it. That opens the other direction, so every need is then guarded explicitly: `!= 'failure'` rather
    * than `== 'success'`, because `skipped` is the answer being tolerated.
    *
    * Without this, affected-gating a Publish capability would break every dependent: `Capability.deploy` needs `docker`
    * by default, so one skipped `docker-<module>` would silently skip the deploy that wanted the other modules'.
    */
  private def skipTolerantClauses(needs: List[JobId]): List[String] =
    (!Expr.cancelled).unwrapped +: needs.distinct.sorted.map(n =>
      (Expr.JobResult(n) !== Expr.quoted("failure")).unwrapped
    )

  /** Whether a job depending on these capability names has a need that affected-gating can skip. One hop is enough: a
    * direct dependent becomes skip-tolerant and therefore never skips itself, so its own dependents keep seeing
    * `success`.
    */
  private def dependsOnSkippable(
      capability: Capability,
      affectedGatedNames: Set[CapabilityName],
  ): Boolean =
    capability.needsCapabilities.exists(affectedGatedNames.contains)

  /** [[skipTolerantClauses]] for the non-Graph scopes, which are never affected-gated themselves and so need this only
    * when something they depend on is. `None` when nothing they need can skip, which keeps every existing `if:`
    * byte-for byte unchanged.
    */
  private def tolerateSkips(
      capability: Capability,
      crossNeeds: List[JobId],
      affectedGatedNames: Set[CapabilityName],
  ): Option[String] =
    Option.when(dependsOnSkippable(capability, affectedGatedNames) && crossNeeds.nonEmpty)(
      skipTolerantClauses(crossNeeds).mkString(" && ")
    )

  private def jobCondition(
      capability: Capability,
      node: ModuleNode,
      guardedNeeds: List[JobId],
      gatedOnAffected: Boolean,
      skipTolerant: Boolean,
  ): Option[String] =
    val releaseGate =
      Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
    val affectedGate =
      Option.when(gatedOnAffected)(
        Expr.group(affectedContains(node.id.asExprLiteral) || affectedContainsAll).unwrapped
      )
    val tolerance =
      if gatedOnAffected || skipTolerant then skipTolerantClauses(guardedNeeds) else Nil

    val clauses = tolerance.headOption.toList ++ releaseGate.toList ++ affectedGate.toList ++ tolerance.drop(1)
    if clauses.isEmpty then None else Some(clauses.mkString(" && "))
  end jobCondition

  /** The affected job's output is a JSON array, so `fromJson` is what makes `contains` mean membership rather than
    * substring.
    *
    * Total, with no `Either` to report: the two members it is called with are a module id, validated when the graph was
    * built and converted by [[ModuleId.asExprLiteral]], and the literal `'all'` below.
    */
  private def affectedContains(member: ExprLiteral): Expr =
    Expr.contains(
      Expr.fromJson(Expr.JobOutput(affectedJobId, OutputName("modules"))),
      Expr.Quoted(member),
    )

  /** `'all'` is the affected job's "could not narrow it down" answer, and the one member of that array that is not a
    * module id.
    */
  private val affectedContainsAll: Expr = affectedContains(ExprLiteral("all"))

  /** Job-level skip when affected found no modules. Legal in `jobs.<id>.if` (no `matrix` context).
    *
    * Right-hand side is [[Expr.lit]] rather than [[Expr.quoted]]: `'[]'` is not a valid [[ExprLiteral]] character set.
    */
  private val affectedModulesNonEmpty: Expr =
    Expr.JobOutput(affectedJobId, OutputName("modules")) !== Expr.lit("'[]'")

  /** Per-leg affected membership for a Graph matrix-collapsed job. Must live on **step** `if`, not job `if`: GitHub
    * forbids `matrix` in `jobs.<job_id>.if` (workflow fails validation with 0 jobs).
    */
  private val affectedContainsMatrixModule: Expr =
    Expr.contains(
      Expr.fromJson(Expr.JobOutput(affectedJobId, OutputName("modules"))),
      Expr.matrix("module"),
    )

  /** AND a condition onto a step's existing `if`, or set it when absent. */
  private def andStepIf(step: Step, cond: Option[String]): Step =
    cond match
      case None    => step
      case Some(c) =>
        step.copy(`if` = step.`if` match
          case Some(existing) => Some(s"($existing) && ($c)")
          case None           => Some(c))

  /** [[Expr.lit]] over text built here from validated parts: a [[WorkflowName]], a [[RunnerOs]], a [[JdkVersion]], a
    * job id, and the punctuation joining them. Every one of those forbids the control characters [[ShText]] rejects,
    * which is what makes this total.
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
  private def jdkAndSbtSteps(config: PlanConfig, nodeVersion: Option[NodeVersion]): List[Step] =
    val setupSbtWith =
      if config.cache == CacheBackend.LocalDir then ListMap("disk-cache" -> "false")
      else ListMap.empty[String, String]
    val nodeSteps = nodeVersion.toList.map { version =>
      Step(
        name = Some(s"Setup Node $version"),
        uses = Some(config.actions.setupNode),
        `with` = ListMap("node-version" -> version),
      )
    }
    List(
      Step(
        name = Some(s"Setup JDK ${config.javaVersion}"),
        uses = Some(config.actions.setupJava),
        `with` = ListMap(
          "distribution" -> "temurin",
          "java-version" -> config.javaVersion,
        ),
      ),
      Step(uses = Some(config.actions.setupSbt), `with` = setupSbtWith),
    ) ++ nodeSteps
  end jdkAndSbtSteps

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
  ): List[Step] =
    val base =
      commandOverride.orElse(
        Option.when(capability.command.runsSbt)(capability.command.commandFor(node))
      )
    val command  = capability.sessionCommand(base)
    val ctx      = StepContext(node, target, hasMatrix, config.actions, destinations)
    val checkout =
      List(Step(uses = Some(config.actions.checkout), `with` = checkoutWith))
    command match
      case None =>
        checkout ++ capability.extraSteps(ctx) ++ capability.postSteps(ctx)
      case Some(cmd) =>
        val onMatrixLeg =
          if matrixAxes.contains("scala") || (hasMatrix && matrixAxes.isEmpty && capability.matrixed) then
            underMatrixScala
          else identity[SbtCommand]
        val commandStep =
          if capability.phase == Phase.Verify then verifyCommandStep(capability.name, onMatrixLeg, cmd, config)
          else Step.run(Script(onMatrixLeg(cmd).render)).named(capability.name).build
        val cacheSteps =
          if cache.steps.isEmpty then localDirCacheSteps(config, jobSuffix) else cache.steps
        checkout ++ jdkAndSbtSteps(config, capability.nodeVersion) ++ cacheSteps ++ capability.extraSteps(ctx) ++
          List(commandStep) ++
          capability.postSteps(ctx)
    end match
  end stepsFor

  /** A static [[VerifyClean]] prefix when one is set, otherwise a runtime `cleanFull` decided by
    * [[PlanConfig.verifyCleanLabel]].
    *
    * `onMatrixLeg` rather than a matrix flag: whether this job has a Scala axis is [[stepsFor]]'s to know, and the two
    * branches below each have to apply the switch to a *different* command, the cleaned one and the plain one.
    */
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
            val labelled =
              onEvent("pull_request") && Expr.contains(
                Expr.github("event.pull_request.labels.*.name"),
                Expr.Quoted(label),
              )
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

  // One name in the two types its two positions need: an `env:` key and the shell variable the generated script reads.
  // `EnvName` delegates to `zipx.shell.Patterns.Ident`, so the two agree on shape by construction.
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

  private def localDirCacheSteps(config: PlanConfig, jobSuffix: JobId): List[Step] =
    config.cache match
      case CacheBackend.LocalDir =>
        val prefix = s"${config.runnerOs}-jdk${config.javaVersion}-sbt-"
        val paths  = List("~/.sbt", "~/.cache/sbt", "~/.cache/coursier", "target").mkString("\n")
        config.cacheEpoch match
          case CacheEpoch.Fixed(value) =>
            val epoch        = lit(s"$prefix$value-")
            val run          = perRunKey(epoch)
            val priorRelease = priorReleaseEpochKey(prefix, value)
            List(
              cacheStep(
                cacheAction = config.actions.cache,
                paths = paths,
                key = run ++ lit(jobSuffix),
                restoreKeys = run.render :: epoch.render :: priorRelease.toList ::: prefix :: Nil,
              )
            )

          case CacheEpoch.GitTags(tagMatch) =>
            runtimeEpochCacheSteps(
              prefix = prefix,
              paths = paths,
              jobSuffix = jobSuffix,
              stepId = CacheEpoch.GitTagsStepId,
              resolveRun = CacheEpoch.gitTagsResolveScript(tagMatch),
              cacheAction = config.actions.cache,
            )

          case CacheEpoch.Script(run, stepId) =>
            runtimeEpochCacheSteps(
              prefix = prefix,
              paths = paths,
              jobSuffix = jobSuffix,
              stepId = stepId,
              resolveRun = run,
              cacheAction = config.actions.cache,
            )
        end match

      case _ => Nil

  /** A resolve step plus a cache action whose keys reference `steps.<id>.outputs.{epoch,release}`, so the namespace is
    * decided at workflow runtime rather than baked in at generate time.
    */
  private def runtimeEpochCacheSteps(
      prefix: String,
      paths: String,
      jobSuffix: JobId,
      stepId: StepId,
      resolveRun: String,
      cacheAction: ActionRef,
  ): List[Step] =
    // `Expr.StepOutput` directly rather than `stepOutputMake`: both arguments are already validated, so there is no
    // failure left for a caller to report. `CacheEpoch` holding a `StepId` is what bought that.
    def output(name: OutputName): Expr =
      lit(prefix) ++ Expr.StepOutput(stepId, name) ++ Expr.lit("-")
    val epoch   = output(OutputName("epoch"))
    val run     = perRunKey(epoch)
    val release = output(OutputName("release"))
    List(
      Step(
        id = Some(stepId.unwrap),
        name = Some("Resolve cache epoch"),
        run = Some(resolveRun),
      ),
      cacheStep(
        cacheAction = cacheAction,
        paths = paths,
        key = run ++ lit(jobSuffix),
        restoreKeys = List(run.render, epoch.render, release.render, prefix),
      ),
    )
  end runtimeEpochCacheSteps

  /** Folds in the run id, so every job saves its own entry rather than racing for one key. */
  private def perRunKey(epoch: Expr): Expr = epoch ++ Expr.github("run_id") ++ Expr.lit("-")

  /** `path` and `restore-keys` are newline-joined strings because that is the multi-line form `actions/cache` reads and
    * [[zipx.workflow.YamlPrinter]] emits as a block scalar.
    */
  private def cacheStep(cacheAction: ActionRef, paths: String, key: Expr, restoreKeys: List[String]): Step =
    Step(
      name = Some("Cache sbt"),
      uses = Some(cacheAction),
      `with` = ListMap(
        "path"         -> paths,
        "key"          -> key.render,
        "restore-keys" -> restoreKeys.mkString("\n"),
      ),
    )

  /** A `-ci` / `-SNAPSHOT` epoch is the post-tag continuation of a release, so its first restore fallback is that
    * release's own bare epoch.
    */
  private[core] def priorReleaseEpochKey(prefix: String, cacheEpoch: String): Option[String] =
    val release =
      if cacheEpoch.endsWith("-ci") then Some(cacheEpoch.stripSuffix("-ci"))
      else if cacheEpoch.endsWith("-SNAPSHOT") then Some(cacheEpoch.stripSuffix("-SNAPSHOT"))
      else None
    release.filter(_.nonEmpty).map(e => s"$prefix$e-")

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
              // No command needed: the official image's entrypoint is already bazel-remote. `max_size` bounds the
              // ephemeral service to 1 GiB.
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
