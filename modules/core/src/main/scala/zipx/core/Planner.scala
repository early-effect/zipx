package zipx.core

import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Maps a [[ModuleGraph]] + capabilities + [[PlanConfig]] to a GitHub Actions [[Workflow]].
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

  /** Guards against a `needsCapabilities` cycle among capabilities. */
  private def validateCapabilities(capabilities: List[Capability]): Unit =
    val names    = capabilities.map(_.name).toSet
    val capGraph = ModuleGraph(
      capabilities.map(c => ModuleNode(c.name, dependsOn = c.needsCapabilities.filter(names.contains)))
    )
    capGraph.topologicalSort
    ()

  val affectedJobId   = "affected"
  val verifyGateJobId = "verify-gate"

  def plan(graph: ModuleGraph, capabilities: List[Capability], config: PlanConfig): Workflow =
    validateCapabilities(capabilities)

    // Affected-only applies to Graph Verify jobs (per-module gating). Aggregate/Layer skip the affected setup.
    val usesAffected =
      config.affected == AffectedMode.AffectedOnPR &&
        capabilities.exists(c => c.phase == Phase.Verify && c.scope == CapabilityScope.Graph)

    val hasVerify      = capabilities.exists(_.phase == Phase.Verify)
    val usesVerifyGate = config.skipMergedPrPush && hasVerify

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
        Option.when(usesVerifyGate)(verifyGateJobId -> verifyGateJob(config)),
        Option.when(usesAffected)(
          affectedJobId -> affectedSetupJob(config, usesVerifyGate)
        ),
      ).flatten

    val jobs = ListMap.from(leading ++ capabilityJobs)

    Workflow(
      name = config.workflowName,
      on = triggersFor(config, capabilities),
      jobs = jobs,
    )
  end plan

  /** Cheap gate: on branch pushes, ask whether this SHA already belongs to a PR merged into the same branch. Merge and
    * squash both associate the landed commit with the merged PR; a direct push typically does not. Fail-open: if the
    * check job is skipped or fails, Verify still runs.
    */
  private def verifyGateJob(config: PlanConfig): Job =
    Job(
      name = Some("verify-gate"),
      runsOn = List(config.runnerOs),
      `if` = Some("""github.event_name == 'push' && !startsWith(github.ref, 'refs/tags/')"""),
      permissions = ListMap("contents" -> "read", "pull-requests" -> "read"),
      outputs = ListMap("run" -> "${{ steps.check.outputs.run }}"),
      steps = List(
        Step(
          id = Some("check"),
          name = Some("Skip Verify after merged PR push"),
          env = ListMap("GH_TOKEN" -> "${{ github.token }}"),
          run = Some(
            """|# Commits landed by merging/squashing a PR are associated with that PR via the API.
               |prs=$(gh api "repos/${{ github.repository }}/commits/${{ github.sha }}/pulls" \
               |  --jq "[.[] | select(.merged_at != null and .base.ref == \"${{ github.ref_name }}\")] | length")
               |if [ "$prs" -gt 0 ]; then
               |  echo "Merged PR push — skipping redundant Verify (already ran on the PR)"
               |  echo "run=false" >> "$GITHUB_OUTPUT"
               |else
               |  echo "run=true" >> "$GITHUB_OUTPUT"
               |fi""".stripMargin
          ),
        )
      ),
    )

  /** Wire Verify jobs: never run on tag pushes (release tags only need Publish/Deploy) or on `workflow_dispatch`
    * (manual runs are for docs-only deploys when [[zipx.specular.ZipxDocs.pages]] is enabled). When
    * [[usesVerifyGate]], also need the gate and run when it was skipped/failed or outputs run=true (fail-open for PRs
    * / API errors).
    */
  private def applyVerifyGate(
      needs: List[String],
      cond: Option[String],
      phase: Phase,
      usesVerifyGate: Boolean,
  ): (List[String], Option[String]) =
    if phase != Phase.Verify then (needs, cond)
    else
      val notOnTagOrDispatch =
        "!startsWith(github.ref, 'refs/tags/') && github.event_name != 'workflow_dispatch'"
      if !usesVerifyGate then (needs, andConditions(Some(notOnTagOrDispatch), cond))
      else
        val gatedNeeds = (verifyGateJobId :: needs).distinct.sorted
        val gateCond   =
          s"!cancelled() && $notOnTagOrDispatch && ((needs.$verifyGateJobId.result != 'success') || (needs.$verifyGateJobId.outputs.run == 'true'))"
        (gatedNeeds, andConditions(Some(gateCond), cond))

  private def affectedSetupJob(config: PlanConfig, usesVerifyGate: Boolean): Job =
    val (needs, cond) = applyVerifyGate(Nil, None, Phase.Verify, usesVerifyGate)
    Job(
      name = Some("affected"),
      runsOn = List(config.runnerOs),
      needs = needs,
      `if` = cond,
      outputs = ListMap("modules" -> "${{ steps.compute.outputs.modules }}"),
      steps = List(
        Step(uses = Some(config.actions.checkout), `with` = ListMap("fetch-depth" -> "0"))
      ) ++ jdkAndSbtSteps(config) ++ List(
        Step(
          id = Some("compute"),
          name = Some("Compute affected modules"),
          run = Some(affectedScript(config.affectedOnPush)),
        )
      ),
    )
  end affectedSetupJob

  private def affectedScript(affectedOnPush: Boolean): String =
    val runAffected =
      """sbt -batch --error "zipxAffectedModules $BASE"
        |  modules=$(cat target/zipx-affected.json)""".stripMargin
    val pushBranch =
      if affectedOnPush then s"""elif [ "$${{ github.event_name }}" = "push" ]; then
          |  before="$${{ github.event.before }}"
          |  if [ -z "$$before" ] || [ "$$before" = "0000000000000000000000000000000000000000" ]; then
          |    modules='["all"]'
          |  else
          |    BASE="$$before"
          |    $runAffected
          |  fi""".stripMargin
      else ""
    s"""if [ "$${{ github.event_name }}" = "pull_request" ]; then
       |  BASE="$${{ github.event.pull_request.base.sha }}"
       |  $runAffected
       |$pushBranch
       |else
       |  modules='["all"]'
       |fi
       |echo "modules=$$modules" >> "$$GITHUB_OUTPUT"""".stripMargin
      .replace("\n\n", "\n")
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
    val releaseCond   = Option.when(capability.gate == Gate.OnReleaseTag)("startsWith(github.ref, 'refs/tags/v')")
    val crossNeeds    = crossCapabilityNeeds(capability, graph, byName)
    val (needs, base) = applyVerifyGate(crossNeeds, releaseCond, capability.phase, usesVerifyGate)
    val cond          = andConditions(base, JobCondition.renderOpt(capability.condition))
    capability.workflowCall match
      case Some(call) =>
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
          env = mergeEnv(cache.env, capability.env, Map.empty),
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
        Option.when(capability.gate == Gate.OnReleaseTag)("startsWith(github.ref, 'refs/tags/v')")
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
              env = mergeEnv(cache.env, capability.env, Map.empty),
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
              env = mergeEnv(cache.env, capability.env, target.env),
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
        Option.when(capability.gate == Gate.OnReleaseTag)("startsWith(github.ref, 'refs/tags/v')")

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
          env = mergeEnv(cache.env, capability.env, Map.empty),
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

  /** Graph: one job per (module × optional target) — today's fan-out. */
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
        env = mergeEnv(cache.env, capability.env, targetEnv),
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
      cache: ListMap[String, String],
      capability: Map[String, EnvValue],
      target: Map[String, EnvValue],
  ): ListMap[String, String] =
    cache ++ EnvValue.renderAll(capability) ++ EnvValue.renderAll(target)

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
      Option.when(capability.gate == Gate.OnReleaseTag)(
        s"startsWith(github.ref, 'refs/tags/v')"
      )
    val affectedGate =
      Option.when(gatedOnAffected)(
        s"(contains(fromJson(needs.$affectedJobId.outputs.modules), '${node.id}') || " +
          s"contains(fromJson(needs.$affectedJobId.outputs.modules), 'all'))"
      )
    val upstreamGuards =
      if gatedOnAffected && upstreamNeeds.nonEmpty then upstreamNeeds.sorted.map(u => s"needs.$u.result != 'failure'")
      else Nil
    val notCancelled = Option.when(gatedOnAffected)("!cancelled()")

    val clauses = notCancelled.toList ++ releaseGate.toList ++ affectedGate.toList ++ upstreamGuards
    if clauses.isEmpty then None else Some(clauses.mkString(" && "))
  end jobCondition

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
    val scalaArg = if hasMatrix then "++${{ matrix.scala }} " else ""
    val raw      = commandOverride.getOrElse(capability.command(node))
    val command  =
      if capability.phase == Phase.Verify then config.verifyClean.prefixCommand(raw) else raw
    val cacheSteps =
      if cache.steps.isEmpty then localDirCacheSteps(config, jobSuffix) else cache.steps
    List(
      Step(uses = Some(config.actions.checkout), `with` = ListMap("fetch-depth" -> "0"))
    ) ++ jdkAndSbtSteps(config) ++ cacheSteps ++ capability.extraSteps(
      StepContext(node, target, hasMatrix, config.actions)
    ) ++ List(
      Step(
        name = Some(capability.name),
        run = Some(s"sbt '$scalaArg$command'"),
      )
    ) ++ capability.postSteps(StepContext(node, target, hasMatrix, config.actions))
  end stepsFor

  private case class CacheContribution(
      steps: List[Step] = Nil,
      services: ListMap[String, JobService] = ListMap.empty,
      env: ListMap[String, String] = ListMap.empty,
  )

  private def localDirCacheSteps(config: PlanConfig, jobSuffix: String): List[Step] =
    config.cache match
      case CacheBackend.LocalDir =>
        val prefix = s"${config.runnerOs}-jdk${config.javaVersion}-sbt-"
        val epoch  = s"$prefix${config.cacheEpoch}-"
        val run    = s"$epoch$${{ github.run_id }}-"
        // After a v* tag, dynver-ci (and similar) use "<tag>-ci" until the next release. Prefer the tag epoch before
        // the bare OS+JDK prefix so the first post-tag PR warms from the release job, not an older -ci blob.
        val priorRelease = priorReleaseEpochKey(prefix, config.cacheEpoch)
        val restoreKeys  = (run :: epoch :: priorRelease.toList ::: prefix :: Nil).mkString("\n")
        List(
          Step(
            name = Some("Cache sbt"),
            uses = Some(config.actions.cache),
            `with` = ListMap(
              "path"         -> List("~/.sbt", "~/.cache/sbt", "~/.cache/coursier", "target").mkString("\n"),
              "key"          -> s"$run$jobSuffix",
              "restore-keys" -> restoreKeys,
            ),
          )
        )
      case _ => Nil

  /** When `cacheEpoch` is a post-tag CI suffix (`*-ci` / `*-SNAPSHOT`), restore from the bare release epoch first. */
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
            RemoteCacheProof.envHeader -> EnvValue.secret(headerSecret).render,
          )
        )

end Planner
