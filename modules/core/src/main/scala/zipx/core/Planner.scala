package zipx.core

import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Maps a [[ModuleGraph]] + capabilities + [[PlanConfig]] to a GitHub Actions [[Workflow]].
  *
  * This is the sbt-free heart of zipx: given the real dependency graph, it emits one job per (capability, participating
  * module), wires `needs` from the graph, expands a per-module Scala matrix, attaches caching, and merges typed
  * [[EnvValue]] maps (cache → capability → target) into each job's `env:`. The result is rendered to YAML by
  * [[zipx.workflow.Render]].
  */
object Planner:

  /** The job id for a capability's job on a given module, e.g. `test-schema`. Deterministic and collision-free because
    * sbt project ids are unique.
    */
  def jobId(capability: Capability, moduleId: String): String = s"${capability.name}-$moduleId"

  /** The job id for a per-target job, e.g. `deploy-service-prod`. Target names are unique within a capability. */
  def jobId(capability: Capability, moduleId: String, target: Target): String =
    s"${capability.name}-$moduleId-${target.name}"

  /** All job ids a capability produces for a module: one when it has no targets, or one per target. A `Once` capability
    * has a single build-wide job whose id is just the capability name (independent of the module).
    */
  private def jobIdsFor(capability: Capability, node: ModuleNode): List[String] =
    if capability.scope == CapabilityScope.Once then List(capability.name)
    else
      capability.targets(node) match
        case Nil     => List(jobId(capability, node.id))
        case targets => targets.sortBy(_.name).map(t => jobId(capability, node.id, t))

  /** Guards against a `needsCapabilities` cycle among capabilities (which would produce an unsatisfiable GHA `needs`
    * graph). Reuses the module-graph toposort purely for cycle detection; throws [[CyclicGraphError]] on a cycle.
    */
  private def validateCapabilities(capabilities: List[Capability]): Unit =
    val names    = capabilities.map(_.name).toSet
    val capGraph = ModuleGraph(
      capabilities.map(c => ModuleNode(c.name, dependsOn = c.needsCapabilities.filter(names.contains)))
    )
    capGraph.topologicalSort // throws CyclicGraphError on a cycle
    ()

  /** Job id of the leading affected-detection setup job. */
  val affectedJobId = "affected"

  def plan(graph: ModuleGraph, capabilities: List[Capability], config: PlanConfig): Workflow =
    validateCapabilities(capabilities)

    // Affected-only applies to Verify-phase (test/build) jobs; publish is release-gated instead.
    val usesAffected = config.affected == AffectedMode.AffectedOnPR && capabilities.exists(_.phase == Phase.Verify)

    val byName = capabilities.map(c => c.name -> c).toMap

    // Deterministic job ordering: optional affected setup first, then capabilities ordered by phase (Verify → Publish →
    // Deploy) so a job appears after everything it needs, and within a phase in declared order; modules in topological
    // order (deps first) so the YAML reads top-to-bottom as the build actually proceeds.
    val topoOrder      = graph.topologicalSort
    val orderedCaps    = capabilities.zipWithIndex.sortBy((c, i) => (c.phase.ordinal, i)).map(_._1)
    val capabilityJobs =
      orderedCaps.flatMap {
        case c if c.scope == CapabilityScope.Once => List(onceJob(c, graph, config, byName))
        case c                                    =>
          for
            moduleId <- topoOrder
            node     <- graph.get(moduleId).toList
            if c.participates(node)
            job <- jobsFor(c, node, graph, config, usesAffected, byName)
          yield job
      }

    val jobs = ListMap.from(
      (if usesAffected then List(affectedJobId -> affectedSetupJob(config)) else Nil) ++ capabilityJobs
    )

    Workflow(
      name = config.workflowName,
      on = triggersFor(config, capabilities),
      jobs = jobs,
    )
  end plan

  /** The leading setup job: computes the affected module set as a JSON array output consumed by Verify jobs' `if`
    * conditions. On a PR it diffs against the target-branch base sha. On push it either emits the `"all"` sentinel
    * (default) or, when `affectedOnPush`, diffs against the push `before` sha (with a guard for force-push /
    * first-push). Tags always build everything.
    */
  private def affectedSetupJob(config: PlanConfig): Job =
    Job(
      name = Some("affected"),
      runsOn = List(config.runnerOs),
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

  /** The compute-affected shell script. The push branch is present only when `affectedOnPush`; the `before` sha is
    * validated (an all-zero sha means a branch-create or force-push with no reliable prior state → build everything).
    *
    * Reads `target/zipx-affected.json` written by [[zipx.sbt.ZipxPlugin]] rather than capturing sbt stdout (sbt 2
    * prints server banners that break `GITHUB_OUTPUT`).
    */
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
      .replace("\n\n", "\n") // drop the blank line when pushBranch is empty
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

  /** A single build-wide job for a `Once` capability (a lint/format gate, or a post-publish `sonaRelease`).
    *
    * When `needsCapabilities` is set, the once job `needs` **every** job of those capabilities across all participating
    * modules (and all of their targets) — e.g. Central release waits on the full publish wave.
    *
    * When [[Capability.workflowCall]] is set, emit a reusable-workflow job (no local checkout / sbt steps).
    */
  private def onceJob(
      capability: Capability,
      graph: ModuleGraph,
      config: PlanConfig,
      byName: Map[String, Capability],
  ): (String, Job) =
    val cond       = Option.when(capability.gate == Gate.OnReleaseTag)("startsWith(github.ref, 'refs/tags/v')")
    val crossNeeds =
      (for
        capName <- capability.needsCapabilities
        dep     <- byName.get(capName).toList
        node    <- graph.nodes
        if dep.participates(node)
        id <- jobIdsFor(dep, node)
      yield id).sorted
    capability.workflowCall match
      case Some(call) =>
        capability.name -> Job(
          name = Some(capability.name),
          runsOn = Nil,
          needs = crossNeeds,
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
          needs = crossNeeds,
          `if` = cond,
          permissions = ListMap.from(capability.permissions),
          services = cache.services,
          env = mergeEnv(cache.env, capability.env, Map.empty),
          steps = stepsFor(capability, syntheticNode, None, config, hasMatrix = false, cache),
        )
    end match
  end onceJob

  /** A placeholder node for `Once` capabilities, whose command ignores the module. */
  private val syntheticNode = ModuleNode(id = "_build")

  /** The jobs for one (capability, module): a single job when the capability has no targets, or one job per target
    * (fanned out with distinct ids/environments/env/conditions) when it does. Emitted in target order (sorted by name).
    */
  private def jobsFor(
      capability: Capability,
      node: ModuleNode,
      graph: ModuleGraph,
      config: PlanConfig,
      usesAffected: Boolean,
      byName: Map[String, Capability],
  ): List[(String, Job)] =
    val upstreamNeeds = capability.ordering match
      case Ordering.ParallelWithUpstream =>
        // Need the same-capability jobs of direct upstream modules that also participate.
        graph
          .directDeps(node.id)
          .flatMap(graph.get)
          .filter(capability.participates)
          .map(dep => jobId(capability, dep.id))
      case Ordering.DependencyOrdered =>
        nearestParticipatingAncestors(node, graph, capability).map(jobId(capability, _))

    // Cross-capability needs: the same-module job(s) of each named capability that this module participates in. When
    // that capability fans out over targets, depend on all of its per-target jobs for this module.
    val crossNeeds =
      for
        capName <- capability.needsCapabilities
        dep     <- byName.get(capName).toList
        if dep.participates(node)
        id <- jobIdsFor(dep, node)
      yield id

    // Verify jobs also depend on the affected setup job (to read its output); publish/deploy jobs do not.
    val gatedOnAffected = usesAffected && capability.phase == Phase.Verify
    val needs           = (upstreamNeeds ++ crossNeeds ++ (if gatedOnAffected then List(affectedJobId) else Nil)).sorted

    val matrix =
      if capability.matrixed && config.scalaMatrix && node.crossScalaVersions.sizeIs > 1 then
        Some(Strategy(matrix = ListMap("scala" -> node.crossScalaVersions)))
      else None

    val cache    = cacheContribution(config)
    val baseCond = jobCondition(capability, node, upstreamNeeds, gatedOnAffected)
    val runner   = capability.runsOn.getOrElse(List(config.runnerOs))

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
        // Precedence (later wins): cache < capability.env < target.env.
        env = mergeEnv(cache.env, capability.env, targetEnv),
        // Steps are computed per target so extraSteps (the extension seam) can reference the target's env.
        steps = stepsFor(capability, node, target, config, matrix.isDefined, cache),
      )

    capability.targets(node) match
      case Nil =>
        List(baseJob(jobId(capability, node.id), s"${capability.name} ${node.id}", None, baseCond, None, Map.empty))
      case targets =>
        targets.sortBy(_.name).map { target =>
          baseJob(
            jobId(capability, node.id, target),
            s"${capability.name} ${node.id} (${target.name})",
            Some(target),
            andConditions(baseCond, target.condition),
            target.environment,
            target.env,
          )
        }
    end match
  end jobsFor

  /** Merge job env layers into a deterministic `ListMap`. Precedence (later wins on key clash): cache contribution →
    * [[Capability.env]] → [[Target.env]].
    */
  private def mergeEnv(
      cache: ListMap[String, String],
      capability: Map[String, EnvValue],
      target: Map[String, EnvValue],
  ): ListMap[String, String] =
    cache ++ EnvValue.renderAll(capability) ++ EnvValue.renderAll(target)

  /** AND two optional `if` clauses, wrapping each in parens so operator precedence is unambiguous. */
  private def andConditions(a: Option[String], b: Option[String]): Option[String] =
    (a, b) match
      case (Some(x), Some(y)) => Some(s"($x) && ($y)")
      case (Some(x), None)    => Some(x)
      case (None, Some(y))    => Some(y)
      case (None, None)       => None

  /** The job's `if:` condition — the delicate part of affected-only CI.
    *
    * Two gates compose:
    *   - **Release gate** (publish): only run when the ref is a matching version tag.
    *   - **Affected gate** (Verify jobs on PRs): only run when this module is in the affected set.
    *
    * The subtle hazard: when an upstream job is *skipped* (not affected), a downstream job that `needs` it would, by
    * GitHub's default, also be skipped — even if the downstream itself is affected. To let an affected module run
    * regardless of skipped upstreams, we lead with `!cancelled()` and explicitly allow upstream results of `success` or
    * `skipped` (only a genuine upstream `failure` should block). Returns None when no gating applies.
    */
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
    // When an upstream may be skipped (affected gating), a plain `needs` would skip us too. Guard with !cancelled() and
    // require each upstream to not have failed.
    val upstreamGuards =
      if gatedOnAffected && upstreamNeeds.nonEmpty then upstreamNeeds.sorted.map(u => s"needs.$u.result != 'failure'")
      else Nil
    val notCancelled = Option.when(gatedOnAffected)("!cancelled()")

    val clauses = notCancelled.toList ++ releaseGate.toList ++ affectedGate.toList ++ upstreamGuards
    if clauses.isEmpty then None else Some(clauses.mkString(" && "))
  end jobCondition

  /** For dependency-ordered capabilities, contract away non-participating intermediates: walk up the dependency graph
    * and collect the nearest ancestors that participate in this capability. Example: a client that `dependsOn(api)`
    * where `api` publishes but an intermediate test-only module does not → the client's publish needs `api`'s publish.
    */
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

  /** JDK then sbt. For [[CacheBackend.LocalDir]] we own the dependency/action-cache via an epoch-keyed `actions/cache`
    * step, so setup-java must NOT set `cache: sbt` (hashFiles) and setup-sbt must set `disk-cache: false` (also
    * hashFiles). The setup-sbt *distribution* cache (keyed by runner version) stays on.
    */
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
  ): List[Step] =
    val scalaArg = if hasMatrix then "++${{ matrix.scala }} " else ""
    val jobSuffix =
      if capability.scope == CapabilityScope.Once then capability.name
      else target.fold(s"${capability.name}-${node.id}")(t => jobId(capability, node.id, t))
    val cacheSteps =
      if cache.steps.isEmpty then localDirCacheSteps(config, jobSuffix) else cache.steps
    List(
      Step(uses = Some(config.actions.checkout), `with` = ListMap("fetch-depth" -> "0")),
    ) ++ jdkAndSbtSteps(config) ++ cacheSteps ++ capability.extraSteps(
      StepContext(node, target, hasMatrix, config.actions)
    ) ++ List(
      Step(
        name = Some(capability.name),
        run = Some(s"sbt '$scalaArg${capability.command(node)}'"),
      )
    ) ++ capability.postSteps(StepContext(node, target, hasMatrix, config.actions))
  end stepsFor

  /** A cache backend's contribution to a job: pre-run steps, service sidecars, and job-level env. */
  private case class CacheContribution(
      steps: List[Step] = Nil,
      services: ListMap[String, JobService] = ListMap.empty,
      env: ListMap[String, String] = ListMap.empty,
  )

  /** Caching for the chosen backend:
    *   - `LocalDir`: persist sbt's local dirs + build `target/` with `actions/cache`. Primary key is
    *     OS+JDK+epoch+**run id**+**job id** so every job misses the primary key within a run (a hit skips save) and
    *     can save its updated tree. `restore-keys` prefer the same run (accumulated upstream jobs), then the same
    *     epoch from prior runs, then older epochs. setup-sbt disk-cache and setup-java `cache: sbt` stay off.
    *   - `BazelRemoteSidecar`: run `buchgr/bazel-remote` as a job service and point the sbt remote cache at it via env;
    *     the plugin reads `ZIPX_REMOTE_CACHE` and wires `Global / remoteCache` + `addRemoteCachePlugin`.
    *   - `ManagedRemote`: no sidecar; point the remote cache at a managed gRPC endpoint, auth via a header secret.
    *
    * For remote backends sbt's own cache key omits OS/JDK, so a heterogeneous runner pool could poison a shared cache;
    * the plugin folds those axes into `Global / cacheVersion` (documented), and here we surface the endpoint via env.
    */
  private def localDirCacheSteps(config: PlanConfig, jobSuffix: String): List[Step] =
    config.cache match
      case CacheBackend.LocalDir =>
        val prefix = s"${config.runnerOs}-jdk${config.javaVersion}-sbt-"
        val epoch  = s"$prefix${config.cacheEpoch}-"
        // run_id keeps same-run upstream saves ahead of a stale per-job hit from a previous run.
        val run = s"$epoch$${{ github.run_id }}-"
        List(
          Step(
            name = Some("Cache sbt"),
            uses = Some(config.actions.cache),
            `with` = ListMap(
              "path"         -> List("~/.sbt", "~/.cache/sbt", "~/.cache/coursier", "target").mkString("\n"),
              "key"          -> s"$run$jobSuffix",
              "restore-keys" -> List(run, epoch, prefix).mkString("\n"),
            ),
          )
        )
      case _ => Nil

  private def cacheContribution(config: PlanConfig): CacheContribution =
    config.cache match
      case CacheBackend.LocalDir =>
        // Steps are built per-job in [[localDirCacheSteps]] (primary key includes run id + job id).
        CacheContribution()

      case CacheBackend.BazelRemoteSidecar(image, port) =>
        // A gRPC bazel-remote sidecar reachable at localhost:<port>. sbt talks to it via the plaintext grpc:// scheme.
        CacheContribution(
          services = ListMap(
            "bazel-remote" -> JobService(
              image = image,
              ports = List(s"$port:$port"),
              options = Some(s"--entrypoint bazel-remote"),
            )
          ),
          env = ListMap("ZIPX_REMOTE_CACHE" -> s"grpc://localhost:$port"),
        )

      case CacheBackend.ManagedRemote(uri, headerSecret) =>
        // No sidecar: point sbt at a managed gRPC endpoint; the auth header is injected from a repository secret.
        // headerSecret is a *name* (validated like EnvValue.secret) — never a secret value.
        CacheContribution(
          env = ListMap(
            "ZIPX_REMOTE_CACHE"        -> uri,
            "ZIPX_REMOTE_CACHE_HEADER" -> EnvValue.secret(headerSecret).render,
          )
        )

end Planner
