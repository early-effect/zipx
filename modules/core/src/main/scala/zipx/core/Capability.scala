package zipx.core

import neotype.Subtype
import neotype.unwrap
import zipx.workflow.EnvName
import zipx.workflow.Expr
import zipx.workflow.ExprLiteral
import zipx.workflow.JobId
import zipx.workflow.JobService
import zipx.workflow.Names
import zipx.workflow.Step

/** A capability's name, which prefixes every job id it produces: `test` becomes the job `test`, or `test-<module>`
  * under [[CapabilityScope.Graph]].
  */
type CapabilityName = CapabilityName.Type
object CapabilityName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a capability name must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else
      s"invalid capability name '$input': it becomes a GitHub job id, so it must start with an ASCII letter or _ and " +
        "contain only ASCII letters, digits, - or _"

  extension (name: CapabilityName)
    /** A [[CapabilityScope.Once]] job's id. Total: [[zipx.workflow.JobId]] validates the same rule. */
    def asJobId: JobId = JobId.unsafeMake(name)

    /** Total because `-` is legal after the first character and every caller passes `ActionsId` segments (a
      * [[ModuleId]], a [[TargetName]], or `L<index>`). `private[core]` keeps that checkable by inspection.
      */
    private[core] def jobId(rest: String*): JobId = JobId.unsafeMake((name +: rest).mkString("-"))
  end extension
end CapabilityName

/** A target's name, the job-id suffix that keeps one destination's job distinct from another's. */
type TargetName = TargetName.Type
object TargetName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a target name must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else
      s"invalid target name '$input': it becomes part of a GitHub job id, so it must start with an ASCII letter or _ " +
        "and contain only ASCII letters, digits, - or _"

  extension (name: TargetName)
    /** Total: an Actions id is a subset of an expression literal. */
    def asExprLiteral: ExprLiteral = ExprLiteral.unsafeMake(name)
end TargetName

/** Targets deployed together from one `zipx-deploy.yml` dispatch, as in every `stg` target at once. Offered beside the
  * target names in the `target` input, so it follows the same naming rule.
  */
type TargetGroup = TargetGroup.Type
object TargetGroup extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a target group must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else
      s"invalid target group '$input': it is a dispatch choice beside target names, so it must start with an ASCII " +
        "letter or _ and contain only ASCII letters, digits, - or _"
end TargetGroup

/** What a capability's `extraSteps` / `postSteps` see. `target` is populated only when the capability fans out
  * job-per-target.
  *
  * @param destinations
  *   every target this job serves, populated only under [[TargetFanOut.SharedJob]] (where `target` is `None`).
  *   [[Target.envKey]] names the `env:` key each destination's values land under.
  */
final case class StepContext(
    node: ModuleNode,
    target: Option[Target],
    matrixed: Boolean,
    actions: ActionPins = ActionPins.Defaults,
    destinations: List[Target] = Nil,
)

/** Pipeline position, in run order. [[Phase.Verify]] jobs are affected-gated, [[Phase.Publish]] jobs only under
  * [[PlanConfig.affectedPublish]], and [[Phase.Deploy]] jobs only under [[PlanConfig.affectedDeploy]]. Also fixes
  * top-to-bottom job order in the generated YAML.
  */
enum Phase:
  case Verify, Publish, Deploy

/** How a capability's per-module ([[CapabilityScope.Graph]]) jobs are wired to each other.
  *
  *   - [[Ordering.ParallelWithUpstream]] needs the same-capability jobs of a module's *direct* upstreams, so everything
  *     runs as parallel as the dependency graph allows.
  *   - [[Ordering.DependencyOrdered]] needs the nearest *participating* ancestors, contracting away non-participating
  *     intermediates, which is what makes artifacts publish in true dependency order.
  *   - [[Ordering.Independent]] needs no same-capability jobs. Use when the command compiles `dependsOn` from the
  *     checkout (GH Packages `publish`) so waiting on an upstream job is only serializing uploads.
  */
enum Ordering:
  case ParallelWithUpstream, DependencyOrdered, Independent

/** When a capability's jobs may run, ANDed with [[Capability.condition]], [[Target.condition]] and affected-gating. The
  * planner rejects a gate/condition pair it can prove never true (see `Satisfiable`), such as `OnReleaseTag` with a
  * `refs/heads/main` condition.
  *
  * [[Gate.AffectedOnly]] is reserved: affected-gating comes from the phase and the [[PlanConfig.affected]] flags, so
  * the planner rejects it rather than degrading silently to [[Gate.Always]].
  */
enum Gate:
  case Always, OnReleaseTag, AffectedOnly, OnDefaultPush

/** How a capability turns participating modules into jobs, the main CI-cost lever.
  *
  *   - [[CapabilityScope.Aggregate]] joins module commands with `;` into one sbt session, so the fewest JVM starts. One
  *     job per stage, or one per [[Target]] for deploy.
  *   - [[CapabilityScope.Layer]] is one job per toposort wave, commands joined within a wave, waves chained by `needs`.
  *   - [[CapabilityScope.Graph]] is one job per participating module (times matrix and targets), and the only scope
  *     affected-gating can narrow: an Aggregate job runs one sbt session over every module, so there is nothing in it
  *     to skip.
  *   - [[CapabilityScope.Once]] is a single build-wide job, independent of module tasks. Its job id is the capability
  *     name. The command may be absent ([[Capability.steps]]), in which case the job is action-only.
  */
enum CapabilityScope:
  case Aggregate, Layer, Graph, Once

/** Whether a capability's [[Target]]s each get a job, or all share one.
  *
  *   - [[TargetFanOut.JobPerTarget]], the default, is what a deploy wants: separate GitHub Environments, separate
  *     approvals, separate `if:`. One job per (module × target).
  *   - [[TargetFanOut.SharedJob]] is what a *registry* wants: sbt-native-packager's `Docker / publish` builds the image
  *     once and then pushes every `dockerAliases` entry, so N registries is naturally one job. Job ids are unchanged
  *     from a capability with no targets at all, and each destination's `env` lands under a
  *     [[Target.envPrefix]]-prefixed key.
  *
  * For registries it is a cost: `JobPerTarget` rebuilds the same image once per registry, and only `SharedJob`
  * guarantees every registry holds identical bytes.
  *
  * `SharedJob` rejects a [[Target.condition]] and a [[Target.environment]] at generate time rather than dropping them:
  * a job has one `if:` and binds one Environment, so a per-destination one is a request for `JobPerTarget`.
  */
enum TargetFanOut:
  case JobPerTarget, SharedJob

/** A destination a capability fans out over, fully resolved at generate time. Under [[TargetFanOut.JobPerTarget]]: one
  * job per (module × target) with [[CapabilityScope.Graph]], one job per distinct target name with
  * [[CapabilityScope.Aggregate]], and one job per (toposort wave × target) with [[CapabilityScope.Layer]]. Under
  * [[TargetFanOut.SharedJob]]: destinations share each Aggregate/Layer/Graph job. Targets never merge across names, so
  * GitHub Environments and per-destination `env` stay independent.
  *
  * @param name
  *   the job-id suffix under [[TargetFanOut.JobPerTarget]], and the `env:`-key prefix under [[TargetFanOut.SharedJob]].
  *   Unique within a capability.
  * @param environment
  *   the GitHub Environment to bind. GitHub enforces its own protection rules; zipx emits the binding and generates no
  *   approval steps of its own. Rejected under [[TargetFanOut.SharedJob]], which has one job to bind.
  * @param env
  *   merged *after* [[Capability.env]], so a target wins on a key clash. Under [[TargetFanOut.SharedJob]] every key is
  *   prefixed (see [[envKey]]) instead, since several destinations' values coexist in one job.
  * @param group
  *   under [[DeployTrigger.Manual]] and [[DeployTrigger.Staged]], a dispatch choice that deploys every target in the
  *   group at once.
  * @param stage
  *   under [[DeployTrigger.Manual]] and [[DeployTrigger.Staged]], which commits may reach this target; see
  *   [[DeployStage]]. `Production` unless a target opts out, so forgetting it never widens a production gate.
  */
final case class Target(
    name: TargetName,
    environment: Option[String] = None,
    env: Map[String, EnvValue] = Map.empty,
    condition: Option[JobCondition] = None,
    group: Option[TargetGroup] = None,
    stage: DeployStage = DeployStage.Production,
):

  /** `-` becomes `_` because an env name cannot contain `-`. The fixed `ZIPX_` keeps a target named `github` out of
    * GitHub's reserved `GITHUB_` namespace, which is what makes [[envName]] total.
    */
  def envPrefix: String = s"ZIPX_${name.toUpperCase.replace('-', '_')}"

  /** `ZIPX_PROD_AWS_ROLE_TO_ASSUME` for target `prod` and key `AWS_ROLE_TO_ASSUME`. */
  def envKey(key: String): String = s"${envPrefix}_$key"

  /** `unsafeMake` is total: [[envPrefix]] is `Z`-initial over `[A-Za-z0-9_]` and `key` is already an `EnvName`. */
  def envName(key: EnvName): EnvName = EnvName.unsafeMake(envKey(key.unwrap))

  def prefixedEnv: Map[String, EnvValue] = env.map((k, v) => envKey(k) -> v)

end Target

/** A pipeline stage shaped by [[CapabilityScope]]: usually one or more sbt invocations, or action-only steps. The
  * planner derives `needs`, matrix and gating from the graph and scope.
  *
  * @param ordering
  *   applies to [[CapabilityScope.Graph]] only.
  * @param command
  *   Aggregate and Layer join per-module commands with `;`. [[CommandSource.ActionsOnly]] is an action-only job:
  *   checkout plus [[extraSteps]] / [[postSteps]], with no JDK, sbt, cache, or command step.
  * @param matrixed
  *   expands a Graph job over Scala versions. Aggregate and Layer are never matrixed.
  * @param targetFanOut
  *   ignored when `targets` is empty.
  * @param extraSteps
  *   steps before the command step. Prefer a [[Steps]] bundle over a bare lambda: it composes, gates with `when`, and
  *   carries a name into diagnostics.
  * @param postSteps
  *   steps after the command step.
  * @param container
  *   `actions/setup-java` and `sbt/setup-sbt` then install into the container, so an image without `tar`, `curl` or
  *   `git` fails in setup. Prefer [[services]] unless the toolchain itself must differ.
  * @param services
  *   reachable at `localhost:<mapped port>` (or the service id, under [[container]]). GitHub gives no readiness signal
  *   beyond a `--health-cmd` in `options`.
  * @param nodeVersion
  *   adds an `actions/setup-node` step after the JDK. Off by default because sbt-scalajs downloads its own Node for
  *   `jsEnv`; set it for a specific Node or a step running `npm ci`.
  * @param workflowCall
  *   emits a reusable-workflow job instead of sbt steps. Rejected with [[container]] or [[services]], which GitHub does
  *   not accept alongside `uses:`.
  * @param condition
  *   ANDed into every job's `if`, after the [[Gate]] and affected clauses.
  */
final case class Capability(
    name: CapabilityName,
    phase: Phase,
    ordering: Ordering,
    gate: Gate,
    participates: ModuleNode => Boolean,
    command: CommandSource,
    matrixed: Boolean,
    targets: ModuleNode => List[Target] = _ => Nil,
    targetFanOut: TargetFanOut = TargetFanOut.JobPerTarget,
    needsCapabilities: List[CapabilityName] = Nil,
    permissions: Map[String, String] = Map.empty,
    runsOn: Option[List[String]] = None,
    extraSteps: StepContext => List[Step] = Steps.empty,
    postSteps: StepContext => List[Step] = Steps.empty,
    scope: CapabilityScope = CapabilityScope.Aggregate,
    env: Map[String, EnvValue] = Map.empty,
    container: Option[String] = None,
    services: Map[String, JobService] = Map.empty,
    nodeVersion: Option[NodeVersion] = None,
    workflowCall: Option[WorkflowCall] = None,
    condition: Option[JobCondition] = None,
    /** Overrides [[PlanConfig.matrixCollapse]]; `None` inherits the plan allowlist, else Off. */
    matrixCollapse: Option[MatrixCollapse] = None,
    /** Appended once after the joined module commands; see [[thenOnce]]. */
    sessionTail: Option[SbtCommand] = None,
    localCache: LocalCacheMode = LocalCacheMode.Restore,
    affectedBy: Option[ModuleNode => Boolean] = None,
):
  def withCondition(condition: JobCondition): Capability =
    copy(condition = Some(condition))

  /** Under [[AffectedMode.AffectedOnPR]], runs this Once or Aggregate job only when a matching module is affected (or
    * the diff could not narrow). For inputs the classpath graph cannot see, such as images that `Docker/publishLocal`
    * builds. A Graph capability is already gated per module and refuses this.
    */
  def withAffectedBy(modules: ModuleNode => Boolean): Capability =
    copy(affectedBy = Some(modules))

  /** [[LocalCacheMode.Save]] makes this capability the build snapshot's owner in place of the builtin test. At most one
    * capability may save, and not a Graph one: its jobs would each write an entry.
    */
  def withLocalCache(mode: LocalCacheMode): Capability =
    copy(localCache = mode)

  def withCondition(condition: Option[JobCondition]): Capability =
    copy(condition = condition)

  /** For layering a filter onto a pack that already ships a [[condition]], such as `ZipxDocs.pages`. */
  def andCondition(extra: JobCondition): Capability =
    copy(condition = Some(condition.fold(extra)(_ && extra)))

  def withMatrixCollapse(mode: MatrixCollapse): Capability =
    copy(matrixCollapse = Some(mode))

  def withOrdering(ordering: Ordering): Capability =
    copy(ordering = ordering)

  def withoutUpstreamJobs: Capability =
    withOrdering(Ordering.Independent)

  def withPermissions(permissions: Map[String, String]): Capability =
    copy(permissions = permissions)

  /** Lets this job read `registry`'s release metadata. GitHub Packages answers 401 without `packages: read` and the
    * token; Central's metadata is public, so it gets neither.
    */
  def readingRelease(
      registry: ArtifactRegistry,
      credentials: RegistryCredentials = RegistryCredentials.Anonymous,
  ): Capability =
    val authed =
      if registry == ArtifactRegistry.MavenCentral then this
      else plusEnv(credentials.env.toSeq*)
    if registry.usesGithubToken then authed.copy(permissions = authed.permissions + ("packages" -> "read"))
    else authed

  /** Sets the targets and [[TargetFanOut.SharedJob]] together, since either alone is a mistake. The shape for
    * registries.
    */
  def withSharedTargets(targets: ModuleNode => List[Target]): Capability =
    copy(targets = targets, targetFanOut = TargetFanOut.SharedJob)

  def withSharedTargets(targets: List[Target]): Capability =
    withSharedTargets(_ => targets)

  /** One job per target. The shape for deploy environments. */
  def withTargets(targets: ModuleNode => List[Target]): Capability =
    copy(targets = targets, targetFanOut = TargetFanOut.JobPerTarget)

  /** `id` is the hostname a step reaches the sidecar at.
    *
    * {{{
    * Capability.testGraph.withService("postgres", JobService("postgres:17", ports = List("5432:5432")))
    * }}}
    */
  def withService(id: String, service: JobService): Capability =
    copy(services = services + (id -> service))

  def withServices(services: Map[String, JobService]): Capability =
    copy(services = services)

  /** See [[Capability.container]] for what the runner stops providing. */
  def inContainer(image: String): Capability =
    copy(container = Some(image))

  /** Rarely needed for Scala.js; see [[Capability.nodeVersion]]. */
  def withNodeVersion(version: NodeVersion): Capability =
    copy(nodeVersion = Some(version))

  def running(command: SbtCommand): Capability =
    copy(command = CommandSource.Fixed(command))

  /** `<module>/<task>` for each participating module. */
  def runningEach(task: SbtCommand): Capability =
    copy(command = CommandSource.PerModule(n => SbtCommand.module(n, task)))

  /** `+<module>/<task>` for each participating module. */
  def runningEachCross(task: SbtCommand): Capability =
    copy(command = CommandSource.PerModule(n => SbtCommand.crossModule(n, task)))

  /** Prefer [[runningEach]] / [[runningEachCross]]. */
  def runningPerModule(build: ModuleNode => SbtCommand): Capability =
    copy(command = CommandSource.PerModule(build))

  def runningNothing: Capability =
    copy(command = CommandSource.ActionsOnly)

  /** Runs `tail` once after the joined module commands. Repeated calls accumulate. */
  def thenOnce(tail: SbtCommand): Capability =
    copy(sessionTail = Some(sessionTail.fold(tail)(_.andThen(tail))))

  def needing(names: CapabilityName*): Capability =
    copy(needsCapabilities = needsCapabilities ++ names.toList)

  def withEnv(env: Map[String, EnvValue]): Capability =
    copy(env = env)

  def plusEnv(entries: (String, EnvValue)*): Capability =
    copy(env = env ++ entries.toMap)

  def withExtraSteps(steps: Steps): Capability =
    copy(extraSteps = steps)

  def withPostSteps(steps: Steps): Capability =
    copy(postSteps = steps)

  def plusExtraSteps(steps: Steps): Capability =
    copy(extraSteps = Capability.asSteps(extraSteps, "extra") ++ steps)

  def plusPostSteps(steps: Steps): Capability =
    copy(postSteps = Capability.asSteps(postSteps, "post") ++ steps)

  def dropExtraSteps(name: String): Capability =
    copy(extraSteps = Capability.asSteps(extraSteps, "extra").without(name))

  def dropPostSteps(name: String): Capability =
    copy(postSteps = Capability.asSteps(postSteps, "post").without(name))

  def declaredNames: List[SbtCommandName] =
    command.declaredNames ++ sessionTail.toList.flatMap(_.declaredNames)

  def sessionCommand(base: Option[SbtCommand]): Option[SbtCommand] =
    (base, sessionTail) match
      case (Some(b), Some(t)) => Some(b.andThen(t))
      case (Some(b), None)    => Some(b)
      case (None, Some(t))    => Some(t)
      case (None, None)       => None

end Capability

object Capability:

  private def asSteps(field: StepContext => List[Step], fallback: String): Steps =
    field match
      case s: Steps => s
      case f        => Steps(fallback)(f)

  /** Wire form for native-packager's `Docker / publish` until a build passes the real key via zipxTasks. */
  private val dockerPublish: SbtCommand = SbtCommand.unsafeTask("Docker/publish")

  /** Built-in names, which a build also writes in `needsCapabilities` to depend on one. */
  val TestName: CapabilityName          = CapabilityName("test")
  val PublishName: CapabilityName       = CapabilityName("publish")
  val DockerName: CapabilityName        = CapabilityName("docker")
  val DeployName: CapabilityName        = CapabilityName("deploy")
  val PinCheckName: CapabilityName      = CapabilityName("pin-check")
  val FmtName: CapabilityName           = CapabilityName("fmt")
  val WorkflowCheckName: CapabilityName = CapabilityName("workflow-check")
  val AdvisoriesName: CapabilityName    = CapabilityName("advisories")
  val ModverCheckName: CapabilityName   = CapabilityName("modver-check")
  val ModverSuggestName: CapabilityName = CapabilityName("modver-suggest")
  val SnapshotsName: CapabilityName     = CapabilityName("snapshots")
  val PrSnapshotsName: CapabilityName   = CapabilityName("snapshots-pr")

  private def testBody(scope: CapabilityScope, matrixed: Boolean): Capability = Capability(
    name = TestName,
    phase = Phase.Verify,
    ordering = Ordering.ParallelWithUpstream,
    gate = Gate.Always,
    participates = _.ciRelevant,
    command = CommandSource.PerModule(n => SbtCommand.module(n, n.testTask)),
    matrixed = matrixed,
    scope = scope,
    // Graph test jobs compile disjoint slices, so none of them is the build snapshot.
    localCache = if scope == CapabilityScope.Graph then LocalCacheMode.Restore else LocalCacheMode.Save,
  )

  private def publishBody(scope: CapabilityScope): Capability = Capability(
    name = PublishName,
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.publishes,
    command = CommandSource.PerModule(n => SbtCommand.crossModule(n, n.publishTask)),
    matrixed = false,
    scope = scope,
  )

  private def dockerBody(scope: CapabilityScope): Capability = Capability(
    name = DockerName,
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.docker,
    command = CommandSource.PerModule(n => SbtCommand.module(n, dockerPublish)),
    matrixed = false,
    scope = scope,
  )

  /** PR advisory merge gate, injected by the plugin when `zipxPinFeeds` warrants it. */
  def pinCheck(command: SbtCommand = SbtCommand.unsafeTask("zipxPinCheckPr")): Capability =
    Capability.once(
      name = PinCheckName,
      command = command,
      phase = Phase.Verify,
      gate = Gate.Always,
      condition = Some(JobCondition.eventIs("pull_request")),
      env = Map(PinCheck.BaseShaEnv -> EnvValue.typed(Expr.github("event.pull_request.base.sha"))),
    )

  def modverCheck(command: SbtCommand = SbtCommand.unsafeTask("zipxModverCheck")): Capability =
    Capability.once(
      name = ModverCheckName,
      command = command,
      phase = Phase.Verify,
      gate = Gate.Always,
      needsCapabilities = Nil,
      permissions = Map("contents" -> "read"),
      extraSteps = ModverCheck.fetchBaseSha,
      condition = Some(JobCondition.eventIs("pull_request")),
      env = Map(ModverCheck.BaseShaEnv -> EnvValue.typed(Expr.github("event.pull_request.base.sha"))),
    )

  def modverSuggest(command: SbtCommand = SbtCommand.unsafeTask("zipxModverSuggest")): Capability =
    Capability.once(
      name = ModverSuggestName,
      command = command,
      phase = Phase.Verify,
      gate = Gate.Always,
      needsCapabilities = Nil,
      permissions = Map("contents" -> "read", "pull-requests" -> "write"),
      extraSteps = ModverCheck.fetchBaseSha,
      condition = Some(JobCondition.eventIs("pull_request")),
      env = Map(ModverCheck.BaseShaEnv -> EnvValue.typed(Expr.github("event.pull_request.base.sha"))),
    )

  /** Publishes every unreleased row at `<row>-<sha>-SNAPSHOT` on a default-branch push, then the `<row>-SNAPSHOT`
    * pointer. Needs no `test`: the planner adds the Verify roll-up, and `cache-rehydrate` when it owns the save.
    */
  def snapshots(command: SbtCommand = SbtCommand.unsafeCommand("zipxSnapshotPublish")): Capability =
    Capability.once(
      name = SnapshotsName,
      command = command,
      phase = Phase.Publish,
      gate = Gate.OnDefaultPush,
    )

  /** Publishes every unreleased row at that commit's `<row>-<sha>-SNAPSHOT` on each push to a same-repo PR carrying
    * `label`, from the PR's own build cache. The planner needs the Verify roll-up, same as [[snapshots]]. A fork's PR
    * has no publishing secrets, so it never runs there.
    */
  def pullRequestSnapshots(
      label: ExprLiteral,
      command: SbtCommand = SbtCommand.unsafeBuilt("zipxSnapshotPublish pr"),
  ): Capability =
    Capability.once(
      name = PrSnapshotsName,
      command = command,
      phase = Phase.Publish,
      gate = Gate.Always,
      condition = Some(
        JobCondition.eventIs("pull_request") && JobCondition.HasPrLabel(label) && JobCondition.fromSameRepository
      ),
    )

  /** A Verify Once job that prints `zipx: skipping <gate>: <reason>` and exits 0. The check name stays on the PR. */
  def skipOnce(name: CapabilityName, gate: String, reason: String): Capability =
    Capability.steps(
      name = name,
      steps = _ =>
        List(
          Step(
            name = Some(s"skip $gate"),
            run = Some(s"echo 'zipx: skipping $gate: $reason'"),
          )
        ),
      phase = Phase.Verify,
      gate = Gate.Always,
    )

  /** One root sbt task, mirroring sbt's own `.aggregate`. `zipxTestTask` overrides the task and `zipxVerifyClean`
    * prepends a clean.
    */
  val test: Capability =
    Capability
      .once(name = TestName, command = ModuleNode.DefaultTestTask, phase = Phase.Verify, gate = Gate.Always)
      .withLocalCache(LocalCacheMode.Save)

  /** The builtin `test` under [[AffectedMode.AffectedOnPR]], which is what a build gets by default: `zipxTestAffected`
    * against the PR base, or also the pushed-over commit when `onPush`. See [[TestAffected]].
    */
  def testAffected(onPush: Boolean): Capability =
    val prBase = Expr.github("event.pull_request.base.sha")
    test.running(TestAffected.command(if onPush then prBase || Expr.github("event.before") else prBase))

  /** Joins per-module `<id>/<testTask>` commands instead of running one root task. The escape hatch for a build with
    * mixed `zipxTestTask` overrides, where a root aggregate task would run the wrong thing.
    */
  val testJoined: Capability = testBody(CapabilityScope.Aggregate, matrixed = false)

  val testLayers: Capability = testBody(CapabilityScope.Layer, matrixed = false)
  val testGraph: Capability  = testBody(CapabilityScope.Graph, matrixed = true)

  val publish: Capability       = publishBody(CapabilityScope.Aggregate)
  val publishLayers: Capability = publishBody(CapabilityScope.Layer)
  val publishGraph: Capability  = publishBody(CapabilityScope.Graph)

  val docker: Capability       = dockerBody(CapabilityScope.Aggregate)
  val dockerLayers: Capability = dockerBody(CapabilityScope.Layer)
  val dockerGraph: Capability  = dockerBody(CapabilityScope.Graph)

  def deploy(
      participates: ModuleNode => Boolean,
      command: ModuleNode => SbtCommand,
      targets: ModuleNode => List[Target],
      name: CapabilityName = DeployName,
      needsCapabilities: List[CapabilityName] = List(DockerName),
      permissions: Map[String, String] = Map.empty,
      env: Map[String, EnvValue] = Map.empty,
      gate: Gate = Gate.OnReleaseTag,
      condition: Option[JobCondition] = None,
  ): Capability =
    deployBody(
      CapabilityScope.Aggregate,
      participates,
      command,
      targets,
      name,
      needsCapabilities,
      permissions,
      env,
      gate,
      condition,
    )

  def deployGraph(
      participates: ModuleNode => Boolean,
      command: ModuleNode => SbtCommand,
      targets: ModuleNode => List[Target],
      name: CapabilityName = DeployName,
      needsCapabilities: List[CapabilityName] = List(DockerName),
      permissions: Map[String, String] = Map.empty,
      env: Map[String, EnvValue] = Map.empty,
      gate: Gate = Gate.OnReleaseTag,
      condition: Option[JobCondition] = None,
  ): Capability =
    deployBody(
      CapabilityScope.Graph,
      participates,
      command,
      targets,
      name,
      needsCapabilities,
      permissions,
      env,
      gate,
      condition,
    )

  private def deployBody(
      scope: CapabilityScope,
      participates: ModuleNode => Boolean,
      command: ModuleNode => SbtCommand,
      targets: ModuleNode => List[Target],
      name: CapabilityName,
      needsCapabilities: List[CapabilityName],
      permissions: Map[String, String],
      env: Map[String, EnvValue],
      gate: Gate,
      condition: Option[JobCondition],
  ): Capability =
    Capability(
      name = name,
      phase = Phase.Deploy,
      ordering = Ordering.DependencyOrdered,
      gate = gate,
      participates = participates,
      command = CommandSource.PerModule(command),
      matrixed = false,
      targets = targets,
      needsCapabilities = needsCapabilities,
      permissions = permissions,
      env = env,
      scope = scope,
      condition = condition,
    )

  /** A stage zipx doesn't model directly. Defaults to [[CapabilityScope.Graph]] because the usual reason to reach for a
    * custom capability is per-module target fan-out, such as multi-registry docker.
    */
  def custom(
      name: CapabilityName,
      command: ModuleNode => SbtCommand,
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
      extraSteps: StepContext => List[Step] = Steps.empty,
      postSteps: StepContext => List[Step] = Steps.empty,
      env: Map[String, EnvValue] = Map.empty,
      scope: CapabilityScope = CapabilityScope.Graph,
      container: Option[String] = None,
      services: Map[String, JobService] = Map.empty,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability(
      name = name,
      phase = phase,
      ordering = ordering,
      gate = gate,
      participates = participates,
      command = CommandSource.PerModule(command),
      matrixed = matrixed,
      targets = targets,
      targetFanOut = targetFanOut,
      needsCapabilities = needsCapabilities,
      permissions = permissions,
      runsOn = runsOn,
      extraSteps = extraSteps,
      postSteps = postSteps,
      scope = scope,
      env = env,
      container = container,
      services = services,
      condition = condition,
    )

  /** A single build-wide job running one fixed command, such as `scalafmtCheckAll` or a post-publish `sonaRelease`.
    * `needsCapabilities` works in both directions: others name this capability to depend on it, and naming them here
    * makes this job wait on every one of their jobs.
    *
    * For a reusable-workflow call with no local steps, use [[steps]] (or `.runningNothing` plus a
    * [[Capability.workflowCall]]); see `ZipxDocs`.
    */
  def once(
      name: CapabilityName,
      command: SbtCommand,
      phase: Phase = Phase.Verify,
      gate: Gate = Gate.Always,
      runsOn: Option[List[String]] = None,
      extraSteps: StepContext => List[Step] = Steps.empty,
      postSteps: StepContext => List[Step] = Steps.empty,
      env: Map[String, EnvValue] = Map.empty,
      needsCapabilities: List[CapabilityName] = Nil,
      permissions: Map[String, String] = Map.empty,
      container: Option[String] = None,
      services: Map[String, JobService] = Map.empty,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability(
      name = name,
      phase = phase,
      ordering = Ordering.ParallelWithUpstream,
      gate = gate,
      participates = _ => true,
      command = CommandSource.Fixed(command),
      matrixed = false,
      needsCapabilities = needsCapabilities,
      permissions = permissions,
      runsOn = runsOn,
      extraSteps = extraSteps,
      postSteps = postSteps,
      scope = CapabilityScope.Once,
      env = env,
      container = container,
      services = services,
      condition = condition,
    )

  /** A single build-wide action-only job: checkout plus the given steps, with no sbt command and no JDK / sbt / cache
    * toolchain.
    */
  def steps(
      name: CapabilityName,
      steps: StepContext => List[Step],
      phase: Phase = Phase.Verify,
      gate: Gate = Gate.Always,
      runsOn: Option[List[String]] = None,
      postSteps: StepContext => List[Step] = Steps.empty,
      env: Map[String, EnvValue] = Map.empty,
      needsCapabilities: List[CapabilityName] = Nil,
      permissions: Map[String, String] = Map.empty,
      container: Option[String] = None,
      services: Map[String, JobService] = Map.empty,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability(
      name = name,
      phase = phase,
      ordering = Ordering.ParallelWithUpstream,
      gate = gate,
      participates = _ => true,
      command = CommandSource.ActionsOnly,
      matrixed = false,
      needsCapabilities = needsCapabilities,
      permissions = permissions,
      runsOn = runsOn,
      extraSteps = steps,
      postSteps = postSteps,
      scope = CapabilityScope.Once,
      env = env,
      container = container,
      services = services,
      condition = condition,
    )
end Capability
