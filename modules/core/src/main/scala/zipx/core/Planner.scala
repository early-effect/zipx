package zipx.core

import neotype.unwrap
import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Maps a [[ModuleGraph]] + capabilities + [[PlanConfig]] to a GitHub Actions [[zipx.workflow.Workflow]].
  *
  * This is the sbt-free heart of zipx: given the real dependency graph, it emits jobs shaped by each capability's
  * [[CapabilityScope]] (Aggregate / Layer / Graph / Once), wires `needs` from the graph, expands a per-module Scala
  * matrix where Graph requests it, attaches caching, and merges typed [[EnvValue]] maps (cache → capability → target)
  * into each job's `env:`. The result is rendered to YAML by [[zipx.workflow.Render]].
  */
object Planner:

  /** The job id for a Graph capability's job on a given module, e.g. `test-schema`. */
  def jobId(capability: Capability, moduleId: String): String = s"${capability.name}-$moduleId"

  /** The job id for a Graph per-target job, e.g. `deploy-service-prod`. */
  def jobId(capability: Capability, moduleId: String, target: Target): String =
    s"${capability.name}-$moduleId-${target.name}"

  /** Aggregate / Layer deploy job id for a target, e.g. `deploy-prod`. */
  def aggregateTargetJobId(capability: Capability, target: Target): String =
    s"${capability.name}-${target.name}"

  /** Layer job id, e.g. `test-L0`. */
  def layerJobId(capability: Capability, layerIndex: Int): String =
    s"${capability.name}-L$layerIndex"

  /** All job ids a capability produces (for cross-capability `needs`). */
  def allJobIds(capability: Capability, graph: ModuleGraph): List[String] =
    capability.scope match
      case CapabilityScope.Once      => List(capability.name)
      case CapabilityScope.Aggregate =>
        distinctTargets(capability, graph) match
          case Nil     => List(capability.name)
          case targets => targets.map(t => aggregateTargetJobId(capability, t))
      case CapabilityScope.Layer =>
        val layers = graph.subsetLayers(capability.participates)
        layers.indices.map(i => layerJobId(capability, i)).toList
      case CapabilityScope.Graph =>
        graph.nodes
          .filter(capability.participates)
          .flatMap(node => jobIdsForGraph(capability, node))
          .distinct
          .sorted

  private def jobIdsForGraph(capability: Capability, node: ModuleNode): List[String] =
    capability.targets(node) match
      case Nil     => List(jobId(capability, node.id))
      case targets => targets.sortBy(_.name).map(t => jobId(capability, node.id, t))

  /** Distinct targets across participating modules, sorted by name. First-seen wins for env/environment/condition. */
  private def distinctTargets(capability: Capability, graph: ModuleGraph): List[Target] =
    val seen = scala.collection.mutable.LinkedHashMap.empty[String, Target]
    for
      moduleId <- graph.topologicalSort
      node     <- graph.get(moduleId).toList
      if capability.participates(node)
      t <- capability.targets(node)
    do if !seen.contains(t.name) then seen(t.name) = t
    seen.values.toList.sortBy(_.name)

  private def participants(capability: Capability, graph: ModuleGraph): List[ModuleNode] =
    graph.topologicalSort.flatMap(graph.get).filter(capability.participates)

  private def joinCommands(capability: Capability, nodes: List[ModuleNode]): String =
    nodes.map(capability.command).mkString("; ")

  /** Guards against a `needsCapabilities` cycle among capabilities, and against gates the planner cannot honor. */
  private def validateCapabilities(capabilities: List[Capability]): Unit =
    // `Gate.AffectedOnly` is an unimplemented design seam (see [[Gate]]): affected-gating is derived from
    // Phase.Verify + PlanConfig.affected, not from Gate. Fail loudly at generate time rather than emit a
    // workflow that quietly runs the capability always; a silently-green pipeline is the worst outcome.
    capabilities.filter(_.gate == Gate.AffectedOnly) match
      case Nil => ()
      case bad =>
        sys.error(
          s"zipx: Gate.AffectedOnly is not implemented, so capabilities ${bad.map(_.name).sorted.mkString(", ")} " +
            "would silently run on every event. Affected-gating is controlled by zipxAffectedOnPR / " +
            "zipxAffectedOnPush on Graph Verify capabilities, not by Gate. Use Gate.Always (Verify capabilities are " +
            "affected-gated automatically) or Gate.OnReleaseTag."
        )
    val names    = capabilities.map(_.name).toSet
    val capGraph = ModuleGraph(
      capabilities.map(c => ModuleNode(c.name, dependsOn = c.needsCapabilities.filter(names.contains)))
    )
    capGraph.topologicalSort
    ()
  end validateCapabilities

  // The `JobId` is the definition and the `String` is derived from it, rather than the other way round: these ids are
  // both public API (a caller reads `Planner.affectedJobId` to find the job) and operands of `Expr.JobOutput` /
  // `Expr.JobResult`, whose validation is compile-time and so needs a validated value rather than a literal reference.
  private val affectedId       = JobId("affected")
  private val verifyGateId     = JobId("verify-gate")
  private val cacheRehydrateId = JobId("cache-rehydrate")

  val affectedJobId: String       = affectedId.unwrap
  val verifyGateJobId: String     = verifyGateId.unwrap
  val cacheRehydrateJobId: String = cacheRehydrateId.unwrap

  def plan(graph: ModuleGraph, capabilities: List[Capability], config: PlanConfig): Workflow =
    validateCapabilities(capabilities)

    // Affected-only applies to Graph Verify jobs (per-module gating). Aggregate/Layer skip the affected setup.
    val usesAffected =
      config.affected == AffectedMode.AffectedOnPR &&
        capabilities.exists(c => c.phase == Phase.Verify && c.scope == CapabilityScope.Graph)

    val hasVerify      = capabilities.exists(_.phase == Phase.Verify)
    val usesVerifyGate = config.skipMergedPrPush && hasVerify
    // LocalDir only: recreate a default-branch actions/cache save when Verify is skipped after merge.
    val usesCacheRehydrate =
      usesVerifyGate && config.cacheRehydrateOnMerge && config.cache == CacheBackend.LocalDir

    val byName = capabilities.map(c => c.name -> c).toMap

    val topoOrder      = graph.topologicalSort
    val orderedCaps    = capabilities.zipWithIndex.sortBy((c, i) => (c.phase.ordinal, i)).map(_._1)
    val capabilityJobs =
      orderedCaps.flatMap {
        case c if c.scope == CapabilityScope.Once =>
          List(onceJob(c, graph, config, byName, usesVerifyGate))
        case c if c.scope == CapabilityScope.Aggregate =>
          aggregateJobs(c, graph, config, byName, usesVerifyGate)
        case c if c.scope == CapabilityScope.Layer =>
          layerJobs(c, graph, config, byName, usesVerifyGate)
        case c =>
          for
            moduleId <- topoOrder
            node     <- graph.get(moduleId).toList
            if c.participates(node)
            job <- graphJobsFor(c, node, graph, config, usesAffected, byName, usesVerifyGate)
          yield job
      }

    val leading =
      List(
        Option.when(usesVerifyGate)(verifyGateJobId         -> verifyGateJob(config)),
        Option.when(usesCacheRehydrate)(cacheRehydrateJobId -> cacheRehydrateJob(config)),
        Option.when(usesAffected)(
          affectedJobId -> affectedSetupJob(config, usesVerifyGate)
        ),
      ).flatten

    val jobs = ListMap.from(leading ++ capabilityJobs)

    Workflow(
      name = config.workflowName,
      on = triggersFor(config, capabilities),
      jobs = jobs,
      concurrency = Option.when(config.cancelSupersededRuns)(concurrencyFor(config)),
    )
  end plan

  /** Workflow-level `concurrency`: one in-flight run per ref, superseded runs cancelled.
    *
    * The group folds in the workflow name (so sibling workflows in the same repo never contend) and `github.ref` (so a
    * PR's pushes cancel each other while other branches are untouched).
    *
    * `cancel-in-progress` is an expression rather than `true`: a release-tag run must never be cancelled. Publishing is
    * not idempotent, and a half-cancelled run can leave a staged-but-unreleased Central bundle behind, far worse than a
    * wasted runner.
    */
  private def concurrencyFor(config: PlanConfig): Concurrency =
    Concurrency(
      group = (Expr.lit(config.workflowName + "-") ++ Expr.github("ref")).render,
      // Wrapped, unlike an `if:`: `cancel-in-progress` is a plain field, so the expression needs its `${{ }}`.
      cancelInProgress = (!onAnyTagPush).render,
    )

  /** `startsWith(github.ref, 'refs/tags/')`: any tag push, not just `v`-prefixed ones.
    *
    * Deliberately broader than [[JobCondition.onReleaseTag]] (`refs/tags/v`). Verify is skipped and cancellation
    * disabled for *every* tag, since a tag push is never the thing Verify exists to check; only a `v` tag *publishes*.
    */
  private val onAnyTagPush: Expr =
    Expr.startsWith(Expr.github("ref"), Expr.quoted("refs/tags/"))

  /** `github.event_name == 'name'` as an [[Expr]]. Distinct from [[eventIs]], which is the *shell* test for the same
    * question; these gates are `if:` expressions and never reach a `run:` script.
    */
  private inline def onEvent(inline name: String): Expr =
    Expr.github("event_name") === Expr.quoted(name)

  /** The verify-gate job's `run` output: `'true'` when Verify should proceed. A *string*, not a boolean, since every
    * `$GITHUB_OUTPUT` value is one, which is why it is compared against `Expr.quoted` rather than negated.
    */
  private val verifyGateRuns: Expr = Expr.JobOutput(verifyGateId, OutputName("run"))

  /** `needs.verify-gate.result`. */
  private val verifyGateResult: Expr = Expr.JobResult(verifyGateId)

  /** Cheap gate: on branch pushes, ask whether this SHA already belongs to a PR merged into the same branch. Merge and
    * squash both associate the landed commit with the merged PR; a direct push typically does not. Fail-open: if the
    * check job is skipped or fails, Verify still runs.
    */
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

  /** Ask the API whether this SHA landed via a merged PR into the same branch, and publish `run` accordingly.
    *
    * The `--jq` filter is the interesting part: it is a double-quoted shell argument containing a *nested*
    * double-quoted jq string, so the inner quotes must reach jq as `\"`. `Word.Dquote` nested inside another `Dquote`
    * renders exactly that, which is why the escaping is a property of the type here rather than backslashes counted by
    * hand.
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

  /** `echo "name=value" >> "$GITHUB_OUTPUT"`: publish a step output. */
  private inline def setOutput(inline name: String, value: Word.Quotable): Command =
    Exec("echo", Word.dquote(Word.lit(name + "="), value)).appendTo(Word.vq("GITHUB_OUTPUT"))

  /** `[ "${{ github.event_name }}" = "name" ]`. `inline` rather than a lambda: the quoted name is validated at compile
    * time, and a lambda parameter is not a literal the validator can see.
    */
  private inline def eventIs(inline name: String): ShTest =
    ShTest.StrEq(Word.dquote(Expr.github("event_name").asWord), Word.quoted(name))

  /** When verify-gate skips Verify after a merged PR, run a minimal LocalDir cache restore/save so the default branch
    * gets an `actions/cache` entry later PRs can restore from. Fail-closed: only runs when the gate succeeds with
    * `run=false`. Does not touch Publish/Deploy needs or conditions; no [[verifyClean]]. Optional
    * [[PlanConfig.cacheRehydrateExtraSteps]] / [[PlanConfig.cacheRehydrateEnv]] are opt-in only (not copied from Verify
    * capabilities).
    */
  private def cacheRehydrateJob(config: PlanConfig): Job =
    val ctx = StepContext(
      node = ModuleNode(id = cacheRehydrateJobId, publishes = false, ciRelevant = false),
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
      ) ++ jdkAndSbtSteps(config) ++ localDirCacheSteps(config, cacheRehydrateJobId) ++
        config.cacheRehydrateExtraSteps(ctx) ++ List(
          Step.run(Script(sbt("", config.cacheRehydrateTask))).named(cacheRehydrateJobId).build
        ),
    )
  end cacheRehydrateJob

  /** Wire Verify jobs: never run on tag pushes (release tags only need Publish/Deploy) or on `workflow_dispatch`
    * (manual runs are for docs-only deploys when `ZipxDocs.pages` is enabled). When [[usesVerifyGate]], also need the
    * gate and run when it was skipped/failed or outputs run=true (fail-open for PRs / API errors).
    */
  private def applyVerifyGate(
      needs: List[String],
      cond: Option[String],
      phase: Phase,
      usesVerifyGate: Boolean,
  ): (List[String], Option[String]) =
    if phase != Phase.Verify then (needs, cond)
    else
      val notOnTagOrDispatch = !onAnyTagPush && (Expr.github("event_name") !== Expr.quoted("workflow_dispatch"))
      if !usesVerifyGate then (needs, andConditions(Some(notOnTagOrDispatch.unwrapped), cond))
      else
        val gatedNeeds = (verifyGateJobId :: needs).distinct.sorted
        // Fail-open, and grouped so the `||` binds only its two clauses: run when the gate said yes *or* when the gate
        // itself did not succeed. `!cancelled()` is what keeps this reachable when the gate was skipped entirely.
        val gateCond = !Expr.cancelled && notOnTagOrDispatch && Expr.group(
          Expr.group(verifyGateResult !== Expr.quoted("success")) ||
            Expr.group(verifyGateRuns === Expr.quoted("true"))
        )
        (gatedNeeds, andConditions(Some(gateCond.unwrapped), cond))
      end if

  private def affectedSetupJob(config: PlanConfig, usesVerifyGate: Boolean): Job =
    val (needs, cond) = applyVerifyGate(Nil, None, Phase.Verify, usesVerifyGate)
    Job(
      name = Some("affected"),
      runsOn = List(config.runnerOs),
      needs = needs,
      `if` = cond,
      env = EnvValue.renderAll(config.env),
      outputs = ListMap("modules" -> Expr.stepOutput("compute", "modules").render),
      steps = List(
        Step(uses = Some(config.actions.checkout), `with` = checkoutWith)
      ) ++ jdkAndSbtSteps(config) ++ List(
        Step
          .run(affectedScript(config.affectedOnPush))
          .withId("compute")
          .named("Compute affected modules")
          .build
      ),
    )
  end affectedSetupJob

  /** Decide which modules this run must build, and publish the list as the `modules` output.
    *
    * Previously assembled by splicing an interpolated `$runAffected` fragment into two nesting depths, which is why it
    * needed a `.replace("\n\n", "\n")` to clean up after an empty branch and why the nested copy came out
    * under-indented by two spaces. Nesting is the AST's job here: an `if` body is a `Block` that renders one level
    * deeper, and an omitted branch is an absent `elif` rather than an empty string to paper over.
    */
  private def affectedScript(affectedOnPush: Boolean): Script =
    // `sbt` writes the answer to a file rather than stdout: sbt 2 prints server banners, and `modules=$(sbt …)` once
    // poisoned GITHUB_OUTPUT with them.
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
            // A force-push or a branch-create reports an all-zero before-sha, which no diff can be taken against.
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
      byName: Map[String, Capability],
  ): List[String] =
    (for
      capName <- capability.needsCapabilities
      dep     <- byName.get(capName).toList
      id      <- allJobIds(dep, graph)
    yield id).distinct.sorted

  private def onceJob(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[String, Capability],
      usesVerifyGate: Boolean,
  ): (String, Job) =
    val releaseCond   = Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
    val crossNeeds    = crossCapabilityNeeds(capability, graph, byName)
    val (needs, base) = applyVerifyGate(crossNeeds, releaseCond, capability.phase, usesVerifyGate)
    val cond          = andConditions(base, JobCondition.renderOpt(capability.condition))
    capability.workflowCall match
      case Some(call) =>
        // GHA forbids job-level env (and runs-on) on reusable-workflow caller jobs.
        capability.name -> Job(
          name = Some(capability.name),
          runsOn = Nil,
          needs = needs,
          `if` = cond,
          permissions = ListMap.from(capability.permissions),
          uses = Some(call.uses),
          `with` = ListMap.from(call.withInputs),
        )
      case None =>
        val cache = cacheContribution(config)
        capability.name -> Job(
          name = Some(capability.name),
          runsOn = capability.runsOn.getOrElse(List(config.runnerOs)),
          needs = needs,
          `if` = cond,
          permissions = ListMap.from(capability.permissions),
          services = cache.services,
          env = mergeEnv(config.env, cache.env, capability.env, Map.empty),
          steps = stepsFor(
            capability,
            syntheticNode,
            None,
            config,
            hasMatrix = false,
            cache,
            commandOverride = None,
            jobSuffix = capability.name,
          ),
        )
    end match
  end onceJob

  private val syntheticNode = ModuleNode(id = "_build")

  /** Aggregate: one job (joined commands), or one job per distinct target (deploy). */
  private def aggregateJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[String, Capability],
      usesVerifyGate: Boolean,
  ): List[(String, Job)] =
    val nodes = participants(capability, graph)
    if nodes.isEmpty then Nil
    else
      val crossNeeds  = crossCapabilityNeeds(capability, graph, byName)
      val cache       = cacheContribution(config)
      val runner      = capability.runsOn.getOrElse(List(config.runnerOs))
      val releaseCond =
        Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
      val (baseNeeds, gatedCond) = applyVerifyGate(crossNeeds, releaseCond, capability.phase, usesVerifyGate)
      val baseCond               = andConditions(gatedCond, JobCondition.renderOpt(capability.condition))

      distinctTargets(capability, graph) match
        case Nil =>
          val cmd = joinCommands(capability, nodes)
          List(
            capability.name -> Job(
              name = Some(capability.name),
              runsOn = runner,
              needs = baseNeeds,
              `if` = baseCond,
              permissions = ListMap.from(capability.permissions),
              services = cache.services,
              env = mergeEnv(config.env, cache.env, capability.env, Map.empty),
              steps = stepsFor(
                capability,
                nodes.head,
                None,
                config,
                hasMatrix = false,
                cache,
                commandOverride = Some(cmd),
                jobSuffix = capability.name,
              ),
            )
          )
        case targets =>
          targets.map { target =>
            val id  = aggregateTargetJobId(capability, target)
            val cmd = joinCommands(capability, nodes)
            id -> Job(
              name = Some(s"${capability.name} (${target.name})"),
              runsOn = runner,
              needs = baseNeeds,
              `if` = andConditions(baseCond, JobCondition.renderOpt(target.condition)),
              environment = target.environment,
              permissions = ListMap.from(capability.permissions),
              services = cache.services,
              env = mergeEnv(config.env, cache.env, capability.env, target.env),
              steps = stepsFor(
                capability,
                nodes.head,
                Some(target),
                config,
                hasMatrix = false,
                cache,
                commandOverride = Some(cmd),
                jobSuffix = id,
              ),
            )
          }
      end match
    end if
  end aggregateJobs

  /** Layer: one job per toposort wave; each needs the previous wave. */
  private def layerJobs(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[String, Capability],
      usesVerifyGate: Boolean,
  ): List[(String, Job)] =
    val layers = graph.subsetLayers(capability.participates)
    if layers.isEmpty then Nil
    else
      val crossNeeds  = crossCapabilityNeeds(capability, graph, byName)
      val cache       = cacheContribution(config)
      val runner      = capability.runsOn.getOrElse(List(config.runnerOs))
      val releaseCond =
        Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)

      layers.zipWithIndex.map { (layerIds, i) =>
        val id            = layerJobId(capability, i)
        val prevNeed      = if i == 0 then Nil else List(layerJobId(capability, i - 1))
        val layerNeeds    = (prevNeed ++ crossNeeds).distinct.sorted
        val (needs, base) =
          // Only the first wave depends on verify-gate; later waves already wait on L0.
          if i == 0 then applyVerifyGate(layerNeeds, releaseCond, capability.phase, usesVerifyGate)
          else (layerNeeds, releaseCond)
        val cond       = andConditions(base, JobCondition.renderOpt(capability.condition))
        val layerNodes = layerIds.flatMap(graph.get)
        val cmd        = joinCommands(capability, layerNodes)
        id -> Job(
          name = Some(s"${capability.name} L$i"),
          runsOn = runner,
          needs = needs,
          `if` = cond,
          permissions = ListMap.from(capability.permissions),
          services = cache.services,
          env = mergeEnv(config.env, cache.env, capability.env, Map.empty),
          steps = stepsFor(
            capability,
            layerNodes.head,
            None,
            config,
            hasMatrix = false,
            cache,
            commandOverride = Some(cmd),
            jobSuffix = id,
          ),
        )
      }
    end if
  end layerJobs

  /** Graph: one job per (module × optional target), today's fan-out. */
  private def graphJobsFor(
      capability: Capability,
      node: ModuleNode,
      graph: ModuleGraph,
      config: PlanConfig,
      usesAffected: Boolean,
      byName: Map[String, Capability],
      usesVerifyGate: Boolean,
  ): List[(String, Job)] =
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
              if dep.participates(node) then jobIdsForGraph(dep, node) else Nil
            case _ => allJobIds(dep, graph)
      yield id

    val gatedOnAffected = usesAffected && capability.phase == Phase.Verify
    val rawNeeds        =
      (upstreamNeeds ++ crossNeeds ++ (if gatedOnAffected then List(affectedJobId) else Nil)).distinct.sorted

    val matrix =
      if capability.matrixed && config.scalaMatrix && node.crossScalaVersions.sizeIs > 1 then
        Some(Strategy(matrix = ListMap("scala" -> node.crossScalaVersions)))
      else None

    val cache    = cacheContribution(config)
    val baseCond = jobCondition(capability, node, upstreamNeeds, gatedOnAffected)
    // When affected setup already needs verify-gate, Graph Verify jobs inherit the skip via affected.
    // Otherwise (no affected, or non-Verify), apply the gate directly.
    val (needs, gated) =
      if gatedOnAffected then
        // Affected setup already needs verify-gate; still exclude tags (release pushes only Publish/Deploy).
        applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate = false)
      else applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate)
    val cond   = andConditions(gated, JobCondition.renderOpt(capability.condition))
    val runner = capability.runsOn.getOrElse(List(config.runnerOs))

    def baseJob(
        id: String,
        displayName: String,
        target: Option[Target],
        cond: Option[String],
        environment: Option[String],
        targetEnv: Map[String, EnvValue],
    ): (String, Job) =
      id -> Job(
        name = Some(displayName),
        runsOn = runner,
        needs = needs,
        `if` = cond,
        environment = environment,
        permissions = ListMap.from(capability.permissions),
        strategy = matrix,
        services = cache.services,
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
        ),
      )

    capability.targets(node) match
      case Nil =>
        List(baseJob(jobId(capability, node.id), s"${capability.name} ${node.id}", None, cond, None, Map.empty))
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

  private def mergeEnv(
      plan: Map[String, EnvValue],
      cache: ListMap[String, String],
      capability: Map[String, EnvValue],
      target: Map[String, EnvValue],
  ): ListMap[String, String] =
    EnvValue.renderAll(plan) ++ cache ++ EnvValue.renderAll(capability) ++ EnvValue.renderAll(target)

  private def andConditions(a: Option[String], b: Option[String]): Option[String] =
    (a, b) match
      case (Some(x), Some(y)) => Some(s"($x) && ($y)")
      case (Some(x), None)    => Some(x)
      case (None, Some(y))    => Some(y)
      case (None, None)       => None

  private def jobCondition(
      capability: Capability,
      node: ModuleNode,
      upstreamNeeds: List[String],
      gatedOnAffected: Boolean,
  ): Option[String] =
    val releaseGate =
      Option.when(capability.gate == Gate.OnReleaseTag)(JobCondition.onReleaseTag.render)
    // `contains(fromJson(…), 'id')` rather than a string match: the output is a JSON array, so `fromJson` is what makes
    // `contains` mean membership instead of substring. `'all'` is the affected job's "could not narrow it down" answer.
    val affectedGate =
      Option.when(gatedOnAffected)(
        Expr
          .group(
            affectedContains(node.id) || affectedContains("all")
          )
          .unwrapped
      )
    // `!= 'failure'` rather than `== 'success'`: a *skipped* upstream is fine here (its module was not affected), and
    // that is exactly the case an implicit `success()` would wrongly block.
    val upstreamGuards =
      if gatedOnAffected && upstreamNeeds.nonEmpty then
        upstreamNeeds.sorted.map(u => (jobResultOf(u) !== Expr.quoted("failure")).unwrapped)
      else Nil
    val notCancelled = Option.when(gatedOnAffected)((!Expr.cancelled).unwrapped)

    val clauses = notCancelled.toList ++ releaseGate.toList ++ affectedGate.toList ++ upstreamGuards
    if clauses.isEmpty then None else Some(clauses.mkString(" && "))
  end jobCondition

  /** `contains(fromJson(needs.affected.outputs.modules), 'id')`.
    *
    * `id` is a module id off the graph, so it is runtime data and goes through the `Make` sibling rather than the
    * `inline` constructor. A module id that cannot be a quoted literal is a generate-time error, not a broken `if:`.
    */
  private def affectedContains(id: String): Expr =
    Expr.contains(
      Expr.fromJson(Expr.JobOutput(affectedId, OutputName("modules"))),
      orThrow(s"module id '$id'", Expr.quotedMake(id)),
    )

  /** `needs.<id>.result` for a job id computed from the graph. */
  private def jobResultOf(jobId: String): Expr =
    orThrow(s"job id '$jobId'", Expr.jobResultMake(jobId))

  private def orThrow(what: String, result: Either[String, Expr]): Expr =
    result.fold(error => throw IllegalArgumentException(s"zipx: invalid $what: $error"), identity)

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

  private def jdkAndSbtSteps(config: PlanConfig): List[Step] =
    val setupSbtWith =
      if config.cache == CacheBackend.LocalDir then ListMap("disk-cache" -> "false")
      else ListMap.empty[String, String]
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
    )
  end jdkAndSbtSteps

  private def stepsFor(
      capability: Capability,
      node: ModuleNode,
      target: Option[Target],
      config: PlanConfig,
      hasMatrix: Boolean,
      cache: CacheContribution,
      commandOverride: Option[String],
      jobSuffix: String,
  ): List[Step] =
    val scalaArg    = if hasMatrix then "++${{ matrix.scala }} " else ""
    val raw         = commandOverride.getOrElse(capability.command(node))
    val commandStep =
      if capability.phase == Phase.Verify then verifyCommandStep(capability.name, scalaArg, raw, config)
      else Step.run(Script(sbt(scalaArg, raw))).named(capability.name).build
    val cacheSteps =
      if cache.steps.isEmpty then localDirCacheSteps(config, jobSuffix) else cache.steps
    List(
      Step(uses = Some(config.actions.checkout), `with` = checkoutWith)
    ) ++ jdkAndSbtSteps(config) ++ cacheSteps ++ capability.extraSteps(
      StepContext(node, target, hasMatrix, config.actions)
    ) ++ List(commandStep) ++ capability.postSteps(
      StepContext(node, target, hasMatrix, config.actions)
    )
  end stepsFor

  /** Verify sbt step: static [[VerifyClean]] prefix when set; otherwise optional runtime `cleanFull` when the PR has
    * [[PlanConfig.verifyCleanLabel]].
    */
  private def verifyCommandStep(name: String, scalaArg: String, raw: String, config: PlanConfig): Step =
    config.verifyClean match
      case VerifyClean.None =>
        config.verifyCleanLabel match
          case Some(label) =>
            // The env value stays *wrapped*, unlike a job `if:`: an `env:` entry is a plain field, and the runner
            // substitutes the expression to the string `true` or `false` for the script below to compare against.
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
                    Block(sbt(scalaArg, VerifyClean.CleanFull.prefixCommand(raw))),
                    elseDo = Some(Block(sbt(scalaArg, raw))),
                  )
                )
              )
              .named(name)
              .withEnvName(verifyCleanFullName, labelled)
              .build
          case None =>
            Step.run(Script(sbt(scalaArg, raw))).named(name).build
      case mode =>
        Step.run(Script(sbt(scalaArg, mode.prefixCommand(raw)))).named(name).build

  /** `sbt '++3.3.6 test'`: one single-quoted argument.
    *
    * The quoted text is an *sbt* command, not shell structure, so the AST models it as one word rather than trying to
    * express sbt's own syntax. What the type does guarantee is the part that matters here: the command reaches sbt as a
    * single argument, and a quote inside it is a generate-time error rather than a script that splits mid-command.
    */
  private def sbt(scalaArg: String, command: String): Command =
    val argument = scalaArg + command
    Exec(
      "sbt",
      Word
        .squoteMake(argument)
        .fold(error => throw IllegalArgumentException(s"zipx: invalid sbt command '$argument': $error"), identity),
    )

  /** The env key holding the runtime `cleanFull` decision, in both types the two layers need it in: an `env:` key and
    * the shell variable the generated script reads. `EnvName` and `VarName` agree on the shape by construction
    * (`EnvName` delegates to `zipx.shell.Patterns.Ident`), which is what makes one name legal in both positions.
    */
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

  private def localDirCacheSteps(config: PlanConfig, jobSuffix: String): List[Step] =
    config.cache match
      case CacheBackend.LocalDir =>
        val prefix = s"${config.runnerOs}-jdk${config.javaVersion}-sbt-"
        val paths  = List("~/.sbt", "~/.cache/sbt", "~/.cache/coursier", "target").mkString("\n")
        config.cacheEpoch match
          case CacheEpoch.Fixed(value) =>
            val epoch        = Expr.lit(s"$prefix$value-")
            val run          = perRunKey(epoch)
            val priorRelease = priorReleaseEpochKey(prefix, value)
            List(
              cacheStep(
                cacheAction = config.actions.cache,
                paths = paths,
                key = run ++ Expr.lit(jobSuffix),
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

  /** Resolve step + cache action whose key/restore-keys reference `steps.<id>.outputs.{epoch,release}`. */
  private def runtimeEpochCacheSteps(
      prefix: String,
      paths: String,
      jobSuffix: String,
      stepId: String,
      resolveRun: String,
      cacheAction: String,
  ): List[Step] =
    def output(name: String): Expr =
      Expr.lit(prefix) ++ orThrow(s"cache-epoch step id '$stepId'", Expr.stepOutputMake(stepId, name)) ++ Expr.lit("-")
    val epoch   = output("epoch")
    val run     = perRunKey(epoch)
    val release = output("release")
    List(
      Step(
        id = Some(stepId),
        name = Some("Resolve cache epoch"),
        run = Some(resolveRun),
      ),
      cacheStep(
        cacheAction = cacheAction,
        paths = paths,
        key = run ++ Expr.lit(jobSuffix),
        restoreKeys = List(run.render, epoch.render, release.render, prefix),
      ),
    )
  end runtimeEpochCacheSteps

  /** `<epoch>-<run id>-`: the write key's namespace, unique per run so every job saves its own entry. */
  private def perRunKey(epoch: Expr): Expr = epoch ++ Expr.github("run_id") ++ Expr.lit("-")

  /** The `actions/cache` step. `restore-keys` is newline-joined, which the block-scalar printer emits as a list; `path`
    * is newline-joined for the same reason, but is plain data (directories) rather than an expression.
    */
  private def cacheStep(cacheAction: String, paths: String, key: Expr, restoreKeys: List[String]): Step =
    Step(
      name = Some("Cache sbt"),
      uses = Some(cacheAction),
      `with` = ListMap(
        "path"         -> paths,
        "key"          -> key.render,
        "restore-keys" -> restoreKeys.mkString("\n"),
      ),
    )

  /** When a Fixed epoch is a post-tag CI suffix (`*-ci` / `*-SNAPSHOT`), restore from the bare release epoch first. */
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
              // Official image entrypoint is already bazel-remote; max_size keeps the ephemeral service bounded.
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
