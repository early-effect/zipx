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

/** How a capability's per-module jobs are wired to each other.
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

/** Whether a capability fans out per module or runs exactly once for the whole build.
  *
  *   - `PerModule`: one job per participating module (test/publish/docker/deploy) — the default.
  *   - `Once`: a single build-wide job (e.g. `scalafmtCheckAll`, a lint gate). Its job id is just the capability name;
  *     it ignores the graph. When it's a Verify-phase gate, per-module Verify jobs can depend on it via
  *     `needsCapabilities`.
  */
enum CapabilityScope:
  case PerModule, Once

/** A destination a capability fans out over — one job per (module × target). Fully resolved at generate time, so the
  * planner emits explicit per-target jobs (not a runtime matrix): each is independently gated and approvable.
  *
  * @param name
  *   the job-id suffix and display, e.g. "staging" / "prod" / "us-east". Must be unique within a capability.
  * @param environment
  *   the GitHub Environment to bind on the job. GitHub enforces its protection rules (e.g. required reviewers for
  *   production) — zipx only emits the binding; it generates no manual-approval steps.
  * @param env
  *   environment variables injected into the job's `env:` block (account id, region, credentials/role, tier, …),
  *   referenced by steps as `${{ env.KEY }}`. Use [[EnvValue.secret]] / `secret"NAME"` for secret refs;
  *   [[EnvValue.plain]] for literals. Merged after [[Capability.env]] (target wins on key clash).
  * @param condition
  *   an extra `if` clause ANDed into the job's condition (e.g. main-only for a target).
  */
final case class Target(
    name: String,
    environment: Option[String] = None,
    env: Map[String, EnvValue] = Map.empty,
    condition: Option[String] = None,
)

/** A pipeline stage that runs one sbt invocation per participating module.
  *
  * This is the abstraction that keeps zipx registry- and tool-agnostic: test, library publish, and docker publish are
  * all `Capability` values, and a user can define custom ones — any sbt task becomes a stage. The planner turns each
  * capability into a set of per-module GitHub Actions jobs, deriving `needs`, matrix, and gating from the graph.
  *
  * @param name
  *   short capability name, used as the job-id prefix (e.g. "test" → job id "test-<module>").
  * @param phase
  *   pipeline phase; controls whether a publish-style job depends on the module's verify job.
  * @param ordering
  *   how per-module jobs of this capability are wired to one another.
  * @param gate
  *   when the jobs run.
  * @param participates
  *   predicate selecting which modules get a job for this capability.
  * @param command
  *   the sbt command run for a participating module (e.g. `_.id + "/" + _.testTask`).
  * @param matrixed
  *   whether a cross-built module's job expands into a per-Scala-version matrix. Test does (each version is a separate
  *   leg); publish does not (it uses a single `+publish` leg that crosses internally, keeping publish ordering clean).
  * @param targets
  *   destinations to fan out over — one job per (module × target). Empty (default) = a single job with no fan-out. Used
  *   by deploy/publish capabilities that target multiple environments (staging/prod, regions, registries).
  * @param needsCapabilities
  *   names of other capabilities whose same-module jobs this capability's job must also `needs` — e.g. deploy needs the
  *   module's docker job. Composes with the per-capability `ordering`.
  * @param permissions
  *   job-level GitHub token permissions, e.g. `"id-token" -> "write"` for cloud OIDC. Rendered on every job of this
  *   capability.
  * @param runsOn
  *   per-capability runner override. `None` (default) uses the build-level runner; a single label renders as a scalar,
  *   multiple as a sequence (e.g. `List("self-hosted", "linux")`).
  * @param extraSteps
  *   extra steps injected before the command step (the extension seam) — e.g. cloud credential setup that references a
  *   target's env. Receives a [[StepContext]] with the module, the current target (if fanned out), and matrix state.
  * @param postSteps
  *   extra steps injected after the command step — e.g. uploading `target/sona-staging` after `publishSigned`.
  * @param scope
  *   whether this capability fans out per module (default) or runs once for the whole build (a lint/format gate).
  * @param env
  *   capability-wide env injected into every job of this capability (e.g. PGP/Sonatype secrets for publish). Merged
  *   after cache env and before [[Target.env]] — target wins on key clash. See [[EnvValue]].
  * @param workflowCall
  *   when set (typically on a [[CapabilityScope.Once]] capability), emit a reusable-workflow job instead of sbt steps.
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
    scope: CapabilityScope = CapabilityScope.PerModule,
    env: Map[String, EnvValue] = Map.empty,
    workflowCall: Option[WorkflowCall] = None,
)

object Capability:

  /** Test every CI-relevant module, as parallel as the dependency graph allows; each Scala version is a matrix leg. */
  val test: Capability = Capability(
    name = "test",
    phase = Phase.Verify,
    ordering = Ordering.ParallelWithUpstream,
    gate = Gate.Always,
    participates = _.ciRelevant,
    command = n => s"${n.id}/${n.testTask}",
    matrixed = true,
  )

  /** Publish every publishing module in true dependency order, gated on a release tag. */
  val publish: Capability = Capability(
    name = "publish",
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.publishes,
    // Cross-version publish uses a single `+` leg so publish ordering stays clean (no per-scala matrix legs).
    command =
      n => if n.crossScalaVersions.sizeIs > 1 then s"+${n.id}/${n.publishTask}" else s"${n.id}/${n.publishTask}",
    matrixed = false,
  )

  /** Build & publish a docker image for each opted-in module via sbt-native-packager's `Docker / publish` — the "paved
    * path" where the build describes its own image (base, entrypoint, ports) instead of an external Dockerfile + a
    * hand-written `docker build` string. Release-gated; no matrix (a service targets a single Scala version).
    */
  val docker: Capability = Capability(
    name = "docker",
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.docker,
    command = n => s"${n.id}/Docker/publish",
    matrixed = false,
  )

  /** A deploy capability: release-gated, fanned out over `targets` (environments/regions), depending on the module's
    * docker job. `command` is the sbt task that performs the deploy/promote for a module; `participates` selects the
    * deployable modules. The caller supplies environment-specific config via the `targets` (env vars, GitHub
    * Environment for approval, per-target `if`).
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
    )

  /** A custom capability for a stage zipx doesn't model directly — the general extension point. All topology knobs are
    * exposed; sensible defaults mean the caller specifies only what differs. Use `extraSteps` to inject setup steps
    * (e.g. cloud auth) and `targets` to fan out over environments.
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
      env = env,
    )

  /** A run-once, build-wide gate — a single job (not per module) running one command, e.g. a formatting/lint check
    * (`scalafmtCheckAll`) up front in the Verify phase, or a post-publish Central release (`sonaRelease`). Other
    * capabilities can depend on it by name via `needsCapabilities`; conversely, set `needsCapabilities` here so this
    * once-job waits on every job of those capabilities (all modules × targets).
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
