package zipx.core

import neotype.unwrap
import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Maps a [[ModuleGraph]] + capabilities + [[PlanConfig]] to a GitHub Actions [[zipx.workflow.Workflow]], with no sbt
  * dependency. Env maps are merged plan → cache → capability → target, so a target wins every clash.
  */
object Planner:

  def jobId(capability: Capability, moduleId: String): String = s"${capability.name}-$moduleId"

  def jobId(capability: Capability, moduleId: String, target: Target): String =
    s"${capability.name}-$moduleId-${target.name}"

  def aggregateTargetJobId(capability: Capability, target: Target): String =
    s"${capability.name}-${target.name}"

  def layerJobId(capability: Capability, layerIndex: Int): String =
    s"${capability.name}-L$layerIndex"

  /** Every job id a capability produces, which is how one capability's `needs` names another's jobs. */
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

  /** Deduplicated by name, first-seen winning, so two modules naming `prod` differently do not produce two `prod` jobs.
    */
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

  /** Rejects a `needsCapabilities` cycle, and [[Gate.AffectedOnly]], which is an unimplemented seam: honoring it
    * silently as [[Gate.Always]] would emit a green pipeline that runs nothing it was asked to run.
    */
  private def validateCapabilities(capabilities: List[Capability]): Unit =
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

  // The JobId is the definition and the String is derived from it, because these ids are both public API and operands of
  // `Expr.JobOutput` / `Expr.JobResult`, which take a validated value rather than a literal.
  private val affectedId       = JobId("affected")
  private val verifyGateId     = JobId("verify-gate")
  private val cacheRehydrateId = JobId("cache-rehydrate")

  val affectedJobId: String       = affectedId.unwrap
  val verifyGateJobId: String     = verifyGateId.unwrap
  val cacheRehydrateJobId: String = cacheRehydrateId.unwrap

  def plan(graph: ModuleGraph, capabilities: List[Capability], config: PlanConfig): Workflow =
    validateCapabilities(capabilities)

    // Affected-gating is per-module, so only a Graph Verify capability can narrow anything.
    val usesAffected =
      config.affected == AffectedMode.AffectedOnPR &&
        capabilities.exists(c => c.phase == Phase.Verify && c.scope == CapabilityScope.Graph)

    val hasVerify          = capabilities.exists(_.phase == Phase.Verify)
    val usesVerifyGate     = config.skipMergedPrPush && hasVerify
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

  /** The group folds in the workflow name so sibling workflows never contend, and `github.ref` so a PR's pushes cancel
    * each other while other branches are untouched. `cancel-in-progress` is an expression rather than `true` because
    * publishing is not idempotent: a half-cancelled release-tag run can leave a staged-but-unreleased Central bundle
    * behind, which is worse than a wasted runner.
    */
  private def concurrencyFor(config: PlanConfig): Concurrency =
    Concurrency(
      group = (Expr.lit(config.workflowName + "-") ++ Expr.github("ref")).render,
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
  private val verifyGateRuns: Expr = Expr.JobOutput(verifyGateId, OutputName("run"))

  private val verifyGateResult: Expr = Expr.JobResult(verifyGateId)

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

  /** Verify never runs on a tag push (a release tag only needs Publish and Deploy) or a `workflow_dispatch` (a manual
    * run is for a docs-only deploy). Non-Verify phases pass through untouched.
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
        // Fail-open: run when the gate said yes, or when the gate itself did not succeed. `!cancelled()` is what keeps
        // this reachable when the gate was skipped entirely.
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
        // GitHub rejects job-level `env` and `runs-on` alongside `uses`, hence neither here.
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
        val firstWave     = i == 0
        val prevNeed      = if firstWave then Nil else List(layerJobId(capability, i - 1))
        val layerNeeds    = (prevNeed ++ crossNeeds).distinct.sorted
        val (needs, base) =
          // Later waves already wait on L0, so gating them again would be redundant.
          if firstWave then applyVerifyGate(layerNeeds, releaseCond, capability.phase, usesVerifyGate)
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
    // The affected setup job already needs verify-gate, so a gated-on-affected job inherits the skip through it and asks
    // only for the tag exclusion.
    val (needs, gated) =
      if gatedOnAffected then applyVerifyGate(rawNeeds, baseCond, capability.phase, usesVerifyGate = false)
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
    // `'all'` is the affected job's "could not narrow it down" answer.
    val affectedGate =
      Option.when(gatedOnAffected)(
        Expr
          .group(
            affectedContains(node.id) || affectedContains("all")
          )
          .unwrapped
      )
    // `!= 'failure'` rather than `== 'success'`, because a *skipped* upstream is fine here: its module was not affected,
    // and that is exactly the case GitHub's implicit `success()` would wrongly block.
    val upstreamGuards =
      if gatedOnAffected && upstreamNeeds.nonEmpty then
        upstreamNeeds.sorted.map(u => (jobResultOf(u) !== Expr.quoted("failure")).unwrapped)
      else Nil
    val notCancelled = Option.when(gatedOnAffected)((!Expr.cancelled).unwrapped)

    val clauses = notCancelled.toList ++ releaseGate.toList ++ affectedGate.toList ++ upstreamGuards
    if clauses.isEmpty then None else Some(clauses.mkString(" && "))
  end jobCondition

  /** The affected job's output is a JSON array, so `fromJson` is what makes `contains` mean membership rather than
    * substring. `id` comes off the graph, hence the `Make` sibling: a module id that cannot be a quoted literal is a
    * generate-time error rather than a broken `if:`.
    */
  private def affectedContains(id: String): Expr =
    Expr.contains(
      Expr.fromJson(Expr.JobOutput(affectedId, OutputName("modules"))),
      orThrow(s"module id '$id'", Expr.quotedMake(id)),
    )

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

  /** A static [[VerifyClean]] prefix when one is set, otherwise a runtime `cleanFull` decided by
    * [[PlanConfig.verifyCleanLabel]].
    */
  private def verifyCommandStep(name: String, scalaArg: String, raw: String, config: PlanConfig): Step =
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

  /** `sbt '++3.3.6 test'`. The quoted text is an *sbt* command, not shell structure, so it is one word rather than an
    * attempt to model sbt's own syntax. What the type guarantees is that it reaches sbt as a single argument, and that
    * a quote inside it is a generate-time error rather than a script that splits mid-command.
    */
  private def sbt(scalaArg: String, command: String): Command =
    val argument = scalaArg + command
    Exec(
      "sbt",
      Word
        .squoteMake(argument)
        .fold(error => throw IllegalArgumentException(s"zipx: invalid sbt command '$argument': $error"), identity),
    )

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

  /** A resolve step plus a cache action whose keys reference `steps.<id>.outputs.{epoch,release}`, so the namespace is
    * decided at workflow runtime rather than baked in at generate time.
    */
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

  /** Folds in the run id, so every job saves its own entry rather than racing for one key. */
  private def perRunKey(epoch: Expr): Expr = epoch ++ Expr.github("run_id") ++ Expr.lit("-")

  /** `path` and `restore-keys` are newline-joined strings because that is the multi-line form `actions/cache` reads and
    * [[zipx.workflow.YamlPrinter]] emits as a block scalar.
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
