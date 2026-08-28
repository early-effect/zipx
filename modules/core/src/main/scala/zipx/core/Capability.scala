package zipx.core

import neotype.Subtype
import neotype.unwrap
import zipx.workflow.EnvName
import zipx.workflow.Expr
import zipx.workflow.JobId
import zipx.workflow.JobService
import zipx.workflow.Names
import zipx.workflow.Step

/** A capability's name, which is also the prefix of every job id it produces: `test` becomes the job `test`, or
  * `test-<module>` under [[CapabilityScope.Graph]].
  *
  * Typed for the same reason [[ModuleId]] is, and against the same rule: a name reaches a `jobs.<job_id>` key, so a
  * space or a `/` in one produced a workflow GitHub rejects on push. The planner noticed neither, because it assembled
  * the id by interpolation. This catches it where the capability is declared.
  *
  * A `Subtype` rather than a `Newtype`, so `CapabilityName <: String`: `c.name == "test"` and
  * `s"${capability.name} ${node.id}"` keep working, and only construction is checked. Its character set is also what
  * lets the planner *build* a [[zipx.workflow.JobId]] rather than validate one; see `asJobId` below.
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
    /** This name as a job id in its own right, which is what a [[CapabilityScope.Once]] capability's job is called.
      *
      * Total, and `unsafeMake` only because neotype cannot see it: [[zipx.workflow.JobId]] validates the same two
      * things this type does, [[zipx.workflow.Names.ActionsId]] and non-empty. That is not a coincidence, it is *why* a
      * capability name is constrained.
      */
    def asJobId: JobId = JobId.unsafeMake(name)

    /** This name joined with the segments that distinguish one of its jobs from another, `-` between each.
      *
      * Also total: `-` is in [[zipx.workflow.Names.ActionsId]]'s trailing character set, and every caller passes
      * segments drawn from it, a [[ModuleId]], a [[TargetName]] or `L<index>`. Restricted to this module so that stays
      * true by inspection.
      */
    private[core] def jobId(rest: String*): JobId = JobId.unsafeMake((name +: rest).mkString("-"))
  end extension
end CapabilityName

/** A target's name, the job-id suffix that keeps one destination's job distinct from another's.
  *
  * Same rule and same reason as [[CapabilityName]]: it lands in a `jobs.<job_id>` key, joined on with `-`.
  */
type TargetName = TargetName.Type
object TargetName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a target name must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else
      s"invalid target name '$input': it becomes part of a GitHub job id, so it must start with an ASCII letter or _ " +
        "and contain only ASCII letters, digits, - or _"
end TargetName

/** What a capability's `extraSteps` / `postSteps` see. `target` is populated only when the capability fans out
  * job-per-target.
  *
  * @param destinations
  *   every target this one job serves, populated only under [[TargetFanOut.SharedJob]], where `target` is `None`
  *   instead: there is no single target such a job belongs to. Steps read it to emit one login (or one push) per
  *   destination, and [[Target.envKey]] gives them the `env:` key each destination's values landed under.
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
  */
enum Ordering:
  case ParallelWithUpstream, DependencyOrdered

/** When a capability's jobs may run, ANDed with [[Capability.condition]], [[Target.condition]] and affected-gating.
  *
  * Because the conjunction spans three places nobody reads together, the planner rejects a gate/condition pair it can
  * prove never true (see `Satisfiable`): a `OnReleaseTag` gate with a `refs/heads/main` condition is a job that looks
  * deliberate and cannot run.
  *
  * [[Gate.AffectedOnly]] is a design seam, not a shipped feature: affected-gating is derived from the phase plus
  * [[PlanConfig.affected]], [[PlanConfig.affectedPublish]] and [[PlanConfig.affectedDeploy]], never from `Gate`, so the
  * planner rejects it with an explaining error rather than degrading silently to [[Gate.Always]]. A green, untested
  * pipeline is the failure mode zipx exists to prevent.
  *
  * Which phases can be narrowed: [[Phase.Verify]] always, [[Phase.Publish]] under [[PlanConfig.affectedPublish]], and
  * [[Phase.Deploy]] under [[PlanConfig.affectedDeploy]]. Verify is not opt-in and the other two are, because
  * **under-verifying is silently unsafe** while **under-publishing is loudly broken**.
  */
enum Gate:
  case Always, OnReleaseTag, AffectedOnly

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
  * The distinction is a cost, not a preference: `JobPerTarget` over 6 registries and 8 images is 48 jobs each
  * rebuilding the same image, where `SharedJob` is 8, and only the second guarantees every registry holds identical
  * bytes (#71).
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
  */
final case class Target(
    name: TargetName,
    environment: Option[String] = None,
    env: Map[String, EnvValue] = Map.empty,
    condition: Option[JobCondition] = None,
):

  /** This target's `env:`-key prefix under [[TargetFanOut.SharedJob]]: `ZIPX_` then the name upper-cased with `-`
    * turned into `_`, because a [[TargetName]] may contain `-` and an env name may not.
    *
    * The fixed `ZIPX_` is what makes [[envName]] total rather than an `Either`. Without it a target legitimately named
    * `github` would derive `GITHUB_…`, which [[zipx.workflow.EnvName]] refuses because GitHub reserves that namespace.
    */
  def envPrefix: String = s"ZIPX_${name.toUpperCase.replace('-', '_')}"

  /** Where `key` from this target's [[env]] lands in a [[TargetFanOut.SharedJob]] job: `ZIPX_PROD_AWS_ROLE_TO_ASSUME`
    * for target `prod` and key `AWS_ROLE_TO_ASSUME`. A step reads it back with [[envName]], so neither side spells the
    * prefix out.
    */
  def envKey(key: String): String = s"${envPrefix}_$key"

  /** [[envKey]] as an [[zipx.workflow.EnvName]], for a step building an `${{ env.… }}` reference to one destination's
    * value.
    *
    * Total, and `unsafeMake` only because neotype cannot see it: [[envPrefix]] is `Z`-initial and drawn from
    * `[A-Za-z0-9_]` (a [[TargetName]]'s character set with `-` mapped to `_`), and `key` is already an `EnvName`, so
    * the result satisfies `Ident` and cannot be `GITHUB_`-prefixed.
    */
  def envName(key: EnvName): EnvName = EnvName.unsafeMake(envKey(key.unwrap))

  /** This target's [[env]] under [[envKey]], the block a [[TargetFanOut.SharedJob]] job merges. */
  def prefixedEnv: Map[String, EnvValue] = env.map((k, v) => envKey(k) -> v)

end Target

/** A pipeline stage shaped by [[CapabilityScope]]: usually one or more sbt invocations, or action-only steps when
  * [[command]] is empty.
  *
  * This is what keeps zipx registry- and tool-agnostic: test, library publish and docker publish are all `Capability`
  * values, and any sbt task becomes a stage. The planner derives `needs`, matrix and gating from the graph and scope.
  *
  * @param name
  *   the job-id prefix: `"test"` becomes Aggregate job `test`, Graph job `test-<module>`.
  * @param ordering
  *   applies to [[CapabilityScope.Graph]] only; ignored for the other scopes.
  * @param command
  *   the sbt command for one participating module, as an [[SbtCommand]] rather than a `String`: the combinators on its
  *   companion build the `<module>/<task>` and `+<module>/<task>` shapes, and `SbtCommand.raw` takes command text zipx
  *   did not build. Aggregate and Layer join these with `;`. `None` means an action-only job: checkout plus
  *   [[extraSteps]] / [[postSteps]], with no JDK, sbt, cache, or command step.
  * @param matrixed
  *   expands a Graph job over Scala versions. Aggregate and Layer are never matrixed.
  * @param targets
  *   empty means no target fan-out.
  * @param targetFanOut
  *   whether those targets each get a job ([[TargetFanOut.JobPerTarget]], the default) or share one
  *   ([[TargetFanOut.SharedJob]]). Ignored when `targets` is empty.
  * @param needsCapabilities
  *   other capabilities whose jobs this one must also `needs`.
  * @param extraSteps
  *   steps injected before the command step. Prefer a [[Steps]] bundle over a bare lambda: it composes with `++`, gates
  *   with `when`, carries a name into diagnostics, and can be published for reuse across repos.
  * @param postSteps
  *   steps injected after the command step.
  * @param container
  *   runs every step of this capability's jobs inside this image, `Job.container`. The runner's own tools are then
  *   absent, so `actions/setup-java` and `sbt/setup-sbt` install into the container rather than the host: an image with
  *   no `tar`, `curl` or `git` fails in setup, not in the build. Prefer [[services]] plus the default runner unless the
  *   *toolchain* is what has to differ, since zipx already pins the JDK and sbt.
  * @param services
  *   sidecar containers for this capability's jobs, `Job.services`. Reachable from a step at `localhost:<mapped port>`
  *   (or at the service id, under [[container]]). GitHub starts them before the first step and gives no readiness
  *   signal beyond a `--health-cmd` in `options`, so a test that needs one to be *ready* is often better off owning the
  *   lifecycle itself; see the Testcontainers note in the docs.
  * @param nodeVersion
  *   when set, an `actions/setup-node` step runs after the JDK setup, pinning Node for this capability's jobs. Off by
  *   default because sbt-scalajs downloads its own Node for `jsEnv`, so a plain Scala.js test suite needs nothing here.
  *   Set it when the version matters: a `jsEnv` requiring a specific Node, or a step running `npm ci` for a bundler.
  * @param workflowCall
  *   when set (typically on [[CapabilityScope.Once]]), emits a reusable-workflow job instead of sbt steps. Rejected
  *   together with [[container]] or [[services]], which GitHub does not accept alongside `uses:`.
  * @param condition
  *   ANDed into every job's `if`, after the [[Gate]] and affected clauses. Prefer [[withCondition]] on a built-in or
  *   pack val; the factories below take it explicitly.
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
    extraSteps: StepContext => List[Step] = _ => Nil,
    postSteps: StepContext => List[Step] = _ => Nil,
    scope: CapabilityScope = CapabilityScope.Aggregate,
    env: Map[String, EnvValue] = Map.empty,
    container: Option[String] = None,
    services: Map[String, JobService] = Map.empty,
    nodeVersion: Option[NodeVersion] = None,
    workflowCall: Option[WorkflowCall] = None,
    condition: Option[JobCondition] = None,
    /** When set, overrides [[PlanConfig.matrixCollapse]] for this capability (including explicit
      * [[MatrixCollapse.Off]]). `None` inherits from the plan allowlist, else Off.
      */
    matrixCollapse: Option[MatrixCollapse] = None,
    /** Build-wide command appended once after joined module commands. See [[thenOnce]]. */
    sessionTail: Option[SbtCommand] = None,
):
  def withCondition(condition: JobCondition): Capability =
    copy(condition = Some(condition))

  def withCondition(condition: Option[JobCondition]): Capability =
    copy(condition = condition)

  /** ANDs `extra` onto any existing [[condition]]. Use this rather than [[withCondition]] when layering a filter onto a
    * pack that already ships one, such as `ZipxDocs.pages`.
    */
  def andCondition(extra: JobCondition): Capability =
    copy(condition = Some(condition.fold(extra)(_ && extra)))

  /** Opt into (or veto) matrix-collapse for this capability; see [[MatrixCollapse]]. */
  def withMatrixCollapse(mode: MatrixCollapse): Capability =
    copy(matrixCollapse = Some(mode))

  /** Destinations that share **one** job: [[TargetFanOut.SharedJob]] plus the targets, set together because setting
    * either alone is the mistake. The shape for registries; see [[TargetFanOut]].
    */
  def withSharedTargets(targets: ModuleNode => List[Target]): Capability =
    copy(targets = targets, targetFanOut = TargetFanOut.SharedJob)

  /** The same for a target list that does not vary by module, which is the usual case for registries. */
  def withSharedTargets(targets: List[Target]): Capability =
    withSharedTargets(_ => targets)

  /** Destinations that each get their own job, the default. The shape for deploy environments. */
  def withTargets(targets: ModuleNode => List[Target]): Capability =
    copy(targets = targets, targetFanOut = TargetFanOut.JobPerTarget)

  /** Adds one sidecar container, keeping any already declared. `id` is the hostname a step reaches it at.
    *
    * {{{
    * Capability.testGraph.withService("postgres", JobService("postgres:17", ports = List("5432:5432")))
    * }}}
    */
  def withService(id: String, service: JobService): Capability =
    copy(services = services + (id -> service))

  /** Replaces the whole sidecar set. */
  def withServices(services: Map[String, JobService]): Capability =
    copy(services = services)

  /** Runs every step of this capability's jobs in `image`; see [[Capability.container]] for what the runner stops
    * providing when you do.
    */
  def inContainer(image: String): Capability =
    copy(container = Some(image))

  /** Pins Node for this capability's jobs with `actions/setup-node`; see [[Capability.nodeVersion]] for when it is
    * needed, which is less often than a Scala.js build suggests.
    *
    * {{{
    * Capability.testGraph.withNodeVersion(NodeVersion("22"))
    * }}}
    */
  def withNodeVersion(version: NodeVersion): Capability =
    copy(nodeVersion = Some(version))

  /** Fixed build-wide command ([[CommandSource.Fixed]]). */
  def running(command: SbtCommand): Capability =
    copy(command = CommandSource.Fixed(command))

  /** Per participating module; zipx applies [[SbtCommand.module]]. */
  def runningEach(task: SbtCommand): Capability =
    copy(command = CommandSource.PerModule(n => SbtCommand.module(n, task)))

  /** Per participating module; zipx applies [[SbtCommand.crossModule]]. */
  def runningEachCross(task: SbtCommand): Capability =
    copy(command = CommandSource.PerModule(n => SbtCommand.crossModule(n, task)))

  /** Per-module command with custom logic (rare). Prefer [[runningEach]] / [[runningEachCross]]. */
  def runningPerModule(build: ModuleNode => SbtCommand): Capability =
    copy(command = CommandSource.PerModule(build))

  /** No sbt command ([[CommandSource.ActionsOnly]]). */
  def runningNothing: Capability =
    copy(command = CommandSource.ActionsOnly)

  /** Appends `tail` after joined module commands. Accumulates when called twice. */
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

  /** Declared command names from the command source and the session tail. */
  def declaredNames: List[SbtCommandName] =
    command.declaredNames ++ sessionTail.toList.flatMap(_.declaredNames)

  /** The command a job runs: `base` with [[sessionTail]] appended. */
  def sessionCommand(base: Option[SbtCommand]): Option[SbtCommand] =
    (base, sessionTail) match
      case (Some(b), Some(t)) => Some(b.andThen(t))
      case (Some(b), None)    => Some(b)
      case (None, Some(t))    => Some(t)
      case (None, None)       => None

end Capability

object Capability:

  /** Wire form for native-packager's `Docker / publish` until a build passes the real key via zipxTasks. */
  private val dockerPublish: SbtCommand = SbtCommand.unsafeTask("Docker/publish")

  /** The names of the built-ins, and the default name of a [[deploy]]. Named because they are also what a build writes
    * in `needsCapabilities` to depend on one, and a default argument cannot be a bare literal now that the parameter is
    * a [[CapabilityName]].
    */
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

  private def testBody(scope: CapabilityScope, matrixed: Boolean): Capability = Capability(
    name = TestName,
    phase = Phase.Verify,
    ordering = Ordering.ParallelWithUpstream,
    gate = Gate.Always,
    participates = _.ciRelevant,
    command = CommandSource.PerModule(n => SbtCommand.module(n, n.testTask)),
    matrixed = matrixed,
    scope = scope,
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

  /** PR advisory merge gate. Once, Verify, `pull_request` only. The plugin injects this when `zipxPinFeeds` warrants
    * it; scheduled apply and snapshot stay companion workflows.
    */
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
    Capability.once(name = TestName, command = ModuleNode.DefaultTestTask, phase = Phase.Verify, gate = Gate.Always)

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
      extraSteps: StepContext => List[Step] = _ => Nil,
      postSteps: StepContext => List[Step] = _ => Nil,
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
      extraSteps: StepContext => List[Step] = _ => Nil,
      postSteps: StepContext => List[Step] = _ => Nil,
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
    * toolchain. Same topology knobs as [[once]].
    */
  def steps(
      name: CapabilityName,
      steps: StepContext => List[Step],
      phase: Phase = Phase.Verify,
      gate: Gate = Gate.Always,
      runsOn: Option[List[String]] = None,
      postSteps: StepContext => List[Step] = _ => Nil,
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
