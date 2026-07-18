package zipx.core

import zipx.workflow.Step

/** Context passed to a capability's `extraSteps` / `postSteps` so injected steps can reference the module, the current
  * deploy target (when fanned out), matrix state, and the build's [[ActionPins]].
  */
final case class StepContext(
    node: ModuleNode,
    target: Option[Target],
    matrixed: Boolean,
    actions: ActionPins = ActionPins.Defaults,
)

/** Where a capability sits in the pipeline, in run order: Verify (test/build) → Publish (artifacts/images) → Deploy
  * (release to environments). Only Verify jobs are affected-gated; Publish and Deploy are release-gated. The phase also
  * fixes top-to-bottom job order in the generated YAML.
  */
enum Phase:
  case Verify, Publish, Deploy

/** How a capability's per-module (Graph) jobs are wired to each other.
  *
  *   - `ParallelWithUpstream`: a module's job needs the same-capability jobs of its direct upstream modules. Test/build
  *     use this — everything runs as parallel as the dependency graph allows.
  *   - `DependencyOrdered`: a module's job needs the nearest *participating* ancestors of the same capability (empty
  *     intermediates contracted away). Publishing uses this so artifacts publish in true dependency order.
  */
enum Ordering:
  case ParallelWithUpstream, DependencyOrdered

/** When a capability's jobs are allowed to run. Composable in the planner (e.g. affected-gated publishing on a tag). */
enum Gate:
  case Always, OnReleaseTag, AffectedOnly

/** How a capability turns participating modules into GitHub Actions jobs. This is the main CI-cost lever.
  *
  *   - `Aggregate`: one job per stage (or one per [[Target]] for deploy). Module commands are joined with `;` into a
  *     single sbt session. Default for built-ins; fewest JVM starts.
  *   - `Layer`: one job per toposort wave (`subsetLayers`); commands joined within the wave; waves chained by `needs`.
  *   - `Graph`: one job per participating module (± matrix / targets). Today's full fan-out; enables affected-only PRs.
  *   - `Once`: a single build-wide job with a fixed command string (e.g. `scalafmtCheckAll`), independent of joining
  *     module tasks. Job id is the capability name.
  */
enum CapabilityScope:
  case Aggregate, Layer, Graph, Once

/** A destination a capability fans out over. Fully resolved at generate time.
  *
  * Under [[CapabilityScope.Graph]], one job per (module × target). Under [[CapabilityScope.Aggregate]] deploy, one job
  * per distinct target name (modules batched). Targets are never merged across names: GitHub Environments and
  * per-destination `env` stay independent.
  *
  * @param name
  *   the job-id suffix and display, e.g. "staging" / "prod" / "us-east". Must be unique within a capability.
  * @param environment
  *   the GitHub Environment to bind on the job. GitHub enforces its protection rules (e.g. required reviewers for
  *   production) — zipx only emits the binding; it generates no manual-approval steps.
  * @param env
  *   environment variables injected into the job's `env:` block, referenced by steps as `${{ env.KEY }}`. Use
  *   [[EnvValue.secret]] / `secret"NAME"` for secret refs; [[EnvValue.plain]] for literals. Merged after
  *   [[Capability.env]] (target wins on key clash).
  * @param condition
  *   an extra `if` clause ANDed into the job's condition (e.g. main-only for a target).
  */
final case class Target(
    name: String,
    environment: Option[String] = None,
    env: Map[String, EnvValue] = Map.empty,
    condition: Option[String] = None,
)

/** A pipeline stage that runs one or more sbt invocations, shaped by [[CapabilityScope]].
  *
  * This is the abstraction that keeps zipx registry- and tool-agnostic: test, library publish, and docker publish are
  * all `Capability` values, and a user can define custom ones — any sbt task becomes a stage. The planner turns each
  * capability into GitHub Actions jobs, deriving `needs`, matrix, and gating from the graph and scope.
  *
  * @param name
  *   short capability name, used as the job-id prefix (e.g. "test" → Aggregate job `test`, Graph job `test-<module>`).
  * @param phase
  *   pipeline phase; controls whether a publish-style job depends on the module's verify job.
  * @param ordering
  *   how Graph-scope jobs of this capability are wired to one another (ignored for Aggregate / Layer / Once).
  * @param gate
  *   when the jobs run.
  * @param participates
  *   predicate selecting which modules contribute commands / Graph jobs for this capability.
  * @param command
  *   the sbt command for a participating module (e.g. `_.id + "/" + _.testTask`). Aggregate/Layer join these with `;`.
  * @param matrixed
  *   whether a Graph job expands into a per-Scala-version matrix. Aggregate/Layer are never matrixed.
  * @param targets
  *   destinations to fan out over. Empty = no target fan-out. Deploy Aggregate: one job per distinct target name.
  * @param needsCapabilities
  *   names of other capabilities whose jobs this capability must also `needs`.
  * @param permissions
  *   job-level GitHub token permissions.
  * @param runsOn
  *   per-capability runner override.
  * @param extraSteps
  *   steps injected before the command step.
  * @param postSteps
  *   steps injected after the command step.
  * @param scope
  *   Aggregate (default for built-ins), Layer, Graph, or Once.
  * @param env
  *   capability-wide env injected into every job.
  * @param workflowCall
  *   when set (typically on [[CapabilityScope.Once]]), emit a reusable-workflow job instead of sbt steps.
  */
final case class Capability(
    name: String,
    phase: Phase,
    ordering: Ordering,
    gate: Gate,
    participates: ModuleNode => Boolean,
    command: ModuleNode => String,
    matrixed: Boolean,
    targets: ModuleNode => List[Target] = _ => Nil,
    needsCapabilities: List[String] = Nil,
    permissions: Map[String, String] = Map.empty,
    runsOn: Option[List[String]] = None,
    extraSteps: StepContext => List[Step] = _ => Nil,
    postSteps: StepContext => List[Step] = _ => Nil,
    scope: CapabilityScope = CapabilityScope.Aggregate,
    env: Map[String, EnvValue] = Map.empty,
    workflowCall: Option[WorkflowCall] = None,
)

object Capability:

  private def testBody(scope: CapabilityScope, matrixed: Boolean): Capability = Capability(
    name = "test",
    phase = Phase.Verify,
    ordering = Ordering.ParallelWithUpstream,
    gate = Gate.Always,
    participates = _.ciRelevant,
    command = n => s"${n.id}/${n.testTask}",
    matrixed = matrixed,
    scope = scope,
  )

  private def publishBody(scope: CapabilityScope): Capability = Capability(
    name = "publish",
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.publishes,
    command =
      n => if n.crossScalaVersions.sizeIs > 1 then s"+${n.id}/${n.publishTask}" else s"${n.id}/${n.publishTask}",
    matrixed = false,
    scope = scope,
  )

  private def dockerBody(scope: CapabilityScope): Capability = Capability(
    name = "docker",
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.docker,
    command = n => s"${n.id}/Docker/publish",
    matrixed = false,
    scope = scope,
  )

  /** Aggregate test: one job joining every CI-relevant module's test task (single sbt session). Default built-in. */
  val test: Capability = testBody(CapabilityScope.Aggregate, matrixed = false)

  /** Layer test: one job per toposort wave; waves chained by `needs`. */
  val testLayers: Capability = testBody(CapabilityScope.Layer, matrixed = false)

  /** Graph test: one job per module, matrixed over Scala versions, affected-gatable. */
  val testGraph: Capability = testBody(CapabilityScope.Graph, matrixed = true)

  /** Aggregate publish: one job joining every publishing module's publish task. Default built-in. */
  val publish: Capability = publishBody(CapabilityScope.Aggregate)

  /** Layer publish: one job per contracted publish wave. */
  val publishLayers: Capability = publishBody(CapabilityScope.Layer)

  /** Graph publish: one job per module in dependency order (today's fan-out). */
  val publishGraph: Capability = publishBody(CapabilityScope.Graph)

  /** Aggregate docker: one job joining every docker module's `Docker/publish`. Default built-in. */
  val docker: Capability = dockerBody(CapabilityScope.Aggregate)

  /** Layer docker: one job per docker toposort wave. */
  val dockerLayers: Capability = dockerBody(CapabilityScope.Layer)

  /** Graph docker: one job per docker module. */
  val dockerGraph: Capability = dockerBody(CapabilityScope.Graph)

  /** Aggregate deploy: one job per distinct [[Target]] name; participating modules' commands are joined. Targets keep
    * independent GitHub Environments / env (staging vs prod never merge). Default for [[deploy]].
    */
  def deploy(
      participates: ModuleNode => Boolean,
      command: ModuleNode => String,
      targets: ModuleNode => List[Target],
      name: String = "deploy",
      needsCapabilities: List[String] = List("docker"),
      permissions: Map[String, String] = Map.empty,
      env: Map[String, EnvValue] = Map.empty,
  ): Capability =
    deployBody(CapabilityScope.Aggregate, participates, command, targets, name, needsCapabilities, permissions, env)

  /** Graph deploy: one job per (module × target) — full fan-out. */
  def deployGraph(
      participates: ModuleNode => Boolean,
      command: ModuleNode => String,
      targets: ModuleNode => List[Target],
      name: String = "deploy",
      needsCapabilities: List[String] = List("docker"),
      permissions: Map[String, String] = Map.empty,
      env: Map[String, EnvValue] = Map.empty,
  ): Capability =
    deployBody(CapabilityScope.Graph, participates, command, targets, name, needsCapabilities, permissions, env)

  private def deployBody(
      scope: CapabilityScope,
      participates: ModuleNode => Boolean,
      command: ModuleNode => String,
      targets: ModuleNode => List[Target],
      name: String,
      needsCapabilities: List[String],
      permissions: Map[String, String],
      env: Map[String, EnvValue],
  ): Capability =
    Capability(
      name = name,
      phase = Phase.Deploy,
      ordering = Ordering.DependencyOrdered,
      gate = Gate.OnReleaseTag,
      participates = participates,
      command = command,
      matrixed = false,
      targets = targets,
      needsCapabilities = needsCapabilities,
      permissions = permissions,
      env = env,
      scope = scope,
    )

  /** A custom capability for a stage zipx doesn't model directly. Defaults to [[CapabilityScope.Graph]] so target
    * fan-out and per-module jobs match today's extension examples (multi-registry docker). Pass `scope` for Aggregate
    * or Layer.
    */
  def custom(
      name: String,
      command: ModuleNode => String,
      participates: ModuleNode => Boolean = _ => true,
      phase: Phase = Phase.Publish,
      ordering: Ordering = Ordering.DependencyOrdered,
      gate: Gate = Gate.OnReleaseTag,
      matrixed: Boolean = false,
      targets: ModuleNode => List[Target] = _ => Nil,
      needsCapabilities: List[String] = Nil,
      permissions: Map[String, String] = Map.empty,
      runsOn: Option[List[String]] = None,
      extraSteps: StepContext => List[Step] = _ => Nil,
      postSteps: StepContext => List[Step] = _ => Nil,
      env: Map[String, EnvValue] = Map.empty,
      scope: CapabilityScope = CapabilityScope.Graph,
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
    )

  /** A run-once, build-wide gate — a single job (not per module) running one fixed command, e.g. a formatting/lint check
    * (`scalafmtCheckAll`) or a post-publish Central release (`sonaRelease`). Other capabilities can depend on it by
    * name via `needsCapabilities`; conversely, set `needsCapabilities` here so this once-job waits on every job of
    * those capabilities.
    *
    * For a reusable-workflow call (no local steps), set [[Capability.workflowCall]] via `.copy` (see
    * [[zipx.specular.ZipxDocs]]).
    */
  def once(
      name: String,
      command: String,
      phase: Phase = Phase.Verify,
      gate: Gate = Gate.Always,
      runsOn: Option[List[String]] = None,
      extraSteps: StepContext => List[Step] = _ => Nil,
      postSteps: StepContext => List[Step] = _ => Nil,
      env: Map[String, EnvValue] = Map.empty,
      needsCapabilities: List[String] = Nil,
      permissions: Map[String, String] = Map.empty,
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
    )
end Capability
