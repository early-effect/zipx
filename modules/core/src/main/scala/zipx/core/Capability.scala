package zipx.core

import neotype.Subtype
import zipx.workflow.JobId
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
      * Total, and `unsafeMake` only because neotype cannot see it: [[JobId]] validates the same two things this type
      * does, [[Names.ActionsId]] and non-empty. That is not a coincidence, it is *why* a capability name is
      * constrained.
      */
    def asJobId: JobId = JobId.unsafeMake(name)

    /** This name joined with the segments that distinguish one of its jobs from another, `-` between each.
      *
      * Also total: `-` is in [[Names.ActionsId]]'s trailing character set, and every caller passes segments drawn from
      * it, a [[ModuleId]], a [[TargetName]] or `L<index>`. Restricted to this module so that stays true by inspection.
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

/** What a capability's `extraSteps` / `postSteps` see. `target` is populated only when the capability fans out. */
final case class StepContext(
    node: ModuleNode,
    target: Option[Target],
    matrixed: Boolean,
    actions: ActionPins = ActionPins.Defaults,
)

/** Pipeline position, in run order. Only [[Phase.Verify]] jobs are affected-gated; the rest are release-gated. Also
  * fixes top-to-bottom job order in the generated YAML.
  */
enum Phase:
  case Verify, Publish, Deploy

/** How a capability's per-module ([[CapabilityScope.Graph]]) jobs are wired to each other.
  *
  *   - [[ParallelWithUpstream]] needs the same-capability jobs of a module's *direct* upstreams, so everything runs as
  *     parallel as the dependency graph allows.
  *   - [[DependencyOrdered]] needs the nearest *participating* ancestors, contracting away non-participating
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
  * [[Gate.AffectedOnly]] is a design seam, not a shipped feature: affected-gating is derived from [[Phase.Verify]] plus
  * [[PlanConfig.affected]], never from `Gate`, so the planner rejects it with an explaining error rather than degrading
  * silently to [[Gate.Always]]. A green, untested pipeline is the failure mode zipx exists to prevent. See ROADMAP
  * M3/M6: the Deploy case is resolved (never affected-gated), Publish is still open.
  */
enum Gate:
  case Always, OnReleaseTag, AffectedOnly

/** How a capability turns participating modules into jobs, the main CI-cost lever.
  *
  *   - [[Aggregate]] joins module commands with `;` into one sbt session, so the fewest JVM starts. One job per stage,
  *     or one per [[Target]] for deploy.
  *   - [[Layer]] is one job per toposort wave, commands joined within a wave, waves chained by `needs`.
  *   - [[Graph]] is one job per participating module (times matrix and targets), and the only scope affected-only PRs
  *     can narrow.
  *   - [[Once]] is a single build-wide job running a fixed command, independent of module tasks. Its job id is the
  *     capability name.
  */
enum CapabilityScope:
  case Aggregate, Layer, Graph, Once

/** A destination a capability fans out over, fully resolved at generate time: one job per (module × target) under
  * [[CapabilityScope.Graph]], one job per distinct target name under an [[CapabilityScope.Aggregate]] deploy. Targets
  * never merge across names, so GitHub Environments and per-destination `env` stay independent.
  *
  * @param name
  *   the job-id suffix, unique within a capability.
  * @param environment
  *   the GitHub Environment to bind. GitHub enforces its own protection rules; zipx emits the binding and generates no
  *   approval steps of its own.
  * @param env
  *   merged *after* [[Capability.env]], so a target wins on a key clash.
  */
final case class Target(
    name: TargetName,
    environment: Option[String] = None,
    env: Map[String, EnvValue] = Map.empty,
    condition: Option[JobCondition] = None,
)

/** A pipeline stage that runs one or more sbt invocations, shaped by [[CapabilityScope]].
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
  *   companion build the `<module>/<task>` and `+<module>/<task>` shapes, and `SbtCommand.unchecked` takes command text
  *   zipx did not build. Aggregate and Layer join these with `;`.
  * @param matrixed
  *   expands a Graph job over Scala versions. Aggregate and Layer are never matrixed.
  * @param targets
  *   empty means no target fan-out.
  * @param needsCapabilities
  *   other capabilities whose jobs this one must also `needs`.
  * @param extraSteps
  *   steps injected before the command step. Prefer a [[Steps]] bundle over a bare lambda: it composes with `++`, gates
  *   with `when`, carries a name into diagnostics, and can be published for reuse across repos.
  * @param postSteps
  *   steps injected after the command step.
  * @param workflowCall
  *   when set (typically on [[CapabilityScope.Once]]), emits a reusable-workflow job instead of sbt steps.
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
    command: ModuleNode => SbtCommand,
    matrixed: Boolean,
    targets: ModuleNode => List[Target] = _ => Nil,
    needsCapabilities: List[CapabilityName] = Nil,
    permissions: Map[String, String] = Map.empty,
    runsOn: Option[List[String]] = None,
    extraSteps: StepContext => List[Step] = _ => Nil,
    postSteps: StepContext => List[Step] = _ => Nil,
    scope: CapabilityScope = CapabilityScope.Aggregate,
    env: Map[String, EnvValue] = Map.empty,
    workflowCall: Option[WorkflowCall] = None,
    condition: Option[JobCondition] = None,
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
end Capability

object Capability:

  /** sbt-native-packager's `Docker / publish`, in the config-axis form the sbt CLI takes. */
  private val dockerPublish: SbtCommand = SbtCommand("Docker/publish")

  /** The names of the built-ins, and the default name of a [[deploy]]. Named because they are also what a build writes
    * in `needsCapabilities` to depend on one, and a default argument cannot be a bare literal now that the parameter is
    * a [[CapabilityName]].
    */
  val TestName: CapabilityName    = CapabilityName("test")
  val PublishName: CapabilityName = CapabilityName("publish")
  val DockerName: CapabilityName  = CapabilityName("docker")
  val DeployName: CapabilityName  = CapabilityName("deploy")

  private def testBody(scope: CapabilityScope, matrixed: Boolean): Capability = Capability(
    name = TestName,
    phase = Phase.Verify,
    ordering = Ordering.ParallelWithUpstream,
    gate = Gate.Always,
    participates = _.ciRelevant,
    command = n => SbtCommand.module(n, n.testTask),
    matrixed = matrixed,
    scope = scope,
  )

  private def publishBody(scope: CapabilityScope): Capability = Capability(
    name = PublishName,
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.publishes,
    command = n => SbtCommand.crossModule(n, n.publishTask),
    matrixed = false,
    scope = scope,
  )

  private def dockerBody(scope: CapabilityScope): Capability = Capability(
    name = DockerName,
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.docker,
    command = n => SbtCommand.module(n, dockerPublish),
    matrixed = false,
    scope = scope,
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
      command = command,
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
      needsCapabilities: List[CapabilityName] = Nil,
      permissions: Map[String, String] = Map.empty,
      runsOn: Option[List[String]] = None,
      extraSteps: StepContext => List[Step] = _ => Nil,
      postSteps: StepContext => List[Step] = _ => Nil,
      env: Map[String, EnvValue] = Map.empty,
      scope: CapabilityScope = CapabilityScope.Graph,
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability(
      name,
      phase,
      ordering,
      gate,
      participates,
      command,
      matrixed,
      targets,
      needsCapabilities,
      permissions,
      runsOn,
      extraSteps,
      postSteps,
      scope = scope,
      env = env,
      condition = condition,
    )

  /** A single build-wide job running one fixed command, such as `scalafmtCheckAll` or a post-publish `sonaRelease`.
    * `needsCapabilities` works in both directions: others name this capability to depend on it, and naming them here
    * makes this job wait on every one of their jobs.
    *
    * For a reusable-workflow call with no local steps, `.copy` in a [[Capability.workflowCall]]; see `ZipxDocs`.
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
      condition: Option[JobCondition] = None,
  ): Capability =
    Capability(
      name = name,
      phase = phase,
      ordering = Ordering.ParallelWithUpstream,
      gate = gate,
      participates = _ => true,
      command = _ => command,
      matrixed = false,
      needsCapabilities = needsCapabilities,
      permissions = permissions,
      runsOn = runsOn,
      extraSteps = extraSteps,
      postSteps = postSteps,
      scope = CapabilityScope.Once,
      env = env,
      condition = condition,
    )
end Capability
