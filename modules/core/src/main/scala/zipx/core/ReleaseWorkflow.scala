package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

import ReleaseRequest.All

/** `zipx-release.yml`, the only place a ship's catalog number is published. A tag releases that ship. A dispatch
  * releases the ships named in `inputs.ships` (default `all`). Either way one sbt session and one deployment, plus
  * unreleased in-repo upstreams.
  */
final case class ReleaseWorkflow(
    registry: ArtifactRegistry,
    env: Map[String, EnvValue] = Map.empty,
    steps: Steps = Steps.empty,
    environment: Option[String] = Some(ReleaseWorkflow.DefaultEnvironment),
    credentials: RegistryCredentials = RegistryCredentials.Anonymous,
)

object ReleaseWorkflow:

  val DefaultPath: String        = ".github/workflows/zipx-release.yml"
  val DefaultEnvironment: String = "zipx-release"

  val TagsFile: String = "target/zipx-release-tags.txt"

  /** The Run workflow field's description. The input id is the literal `ships`: `InputName` and `Expr.input` need a
    * compile-time string, and a reference to this val does not fold into one.
    */
  val ShipsDescription: String = "all, or comma-separated ship names (client, libs)"

  private val jobId: JobId  = JobId("release")
  private val tagPushed     = Expr.github("event_name") === Expr.quoted("push")
  private val dispatched    = Expr.github("event_name") === Expr.quoted("workflow_dispatch")
  private val defaultBranch = Expr.github("event.repository.default_branch")
  private val onDefaultRef  = Expr.github("ref_name") === defaultBranch
  private val buildContext  = StepContext(ModuleNode(id = ModuleId("_build")), target = None, matrixed = false)

  /** `docs` deploys after a release `ci.yml` never sees: a dispatch (its tags are pushed with `GITHUB_TOKEN`, which
    * starts no other workflow) or a `<row>/v*` tag.
    */
  def plan(release: ReleaseWorkflow, config: PlanConfig, tags: TagScheme, docs: Option[Capability] = None): Workflow =
    val cacheMode = if config.cache == CacheBackend.LocalDir then LocalCacheMode.Restore else LocalCacheMode.Off
    val job       = Job(
      name = Some("release"),
      runsOn = List(config.runnerOs),
      `if` = Some((tagPushed || onDefaultRef).unwrapped),
      environment = release.environment.map(JobEnvironment(_)),
      env = EnvValue.renderAll(config.env ++ release.env ++ release.credentials.env),
      steps = Planner.checkoutThenSbtSetup(config, jobId, nodeVersion = None, cacheMode) ++
        List(onDefaultBranchStep) ++
        release.steps(buildContext.copy(actions = config.actions)) ++
        List(bindRefStep, releaseStep, githubReleasesStep),
    )
    val packages = if release.registry.usesGithubToken then ListMap("packages" -> "write") else ListMap.empty
    Workflow(
      name = "zipx release",
      on = Triggers(
        push = Some(BranchFilter(tags = List(tags.pattern))),
        workflowDispatch = Some(shipsDispatch),
      ),
      permissions = ListMap("contents" -> "write") ++ packages,
      concurrency = Some(Concurrency(group = "zipx-release", cancelInProgress = CancelInProgress.Never)),
      jobs = ListMap[String, Job](jobId -> job) ++ docs.flatMap(docsJob(_, tags)),
    )
  end plan

  def render(
      release: ReleaseWorkflow,
      config: PlanConfig,
      tags: TagScheme,
      docs: Option[Capability] = None,
  ): Either[String, String] =
    Render.render(plan(release, config, tags, docs)).map(ActionPinFile.annotateUses(_, config.actions))

  private val onDefaultBranchStep: Step =
    val reached = Exec(
      "git",
      Word.lit("merge-base"),
      Word.lit("--is-ancestor"),
      Word.vq("GITHUB_SHA"),
      Word.dquote(Word.lit("origin/"), Word.v("ZIPX_DEFAULT_BRANCH")),
    )
    val refuse = Block(
      Exec(
        "echo",
        Word.dquote(
          Word.lit("::error title=zipx release::zipx: tag "),
          Word.v("GITHUB_REF_NAME"),
          Word.lit(" is not on "),
          Word.v("ZIPX_DEFAULT_BRANCH"),
        ),
      ),
      Exit(ExitCode.Failure),
    )
    Step
      .run(Script.strict(If(!ShTest.Cmd(reached), refuse)))
      .named("Tag is on the default branch")
      .when(tagPushed)
      .withEnv("ZIPX_DEFAULT_BRANCH", defaultBranch)
      .build
  end onDefaultBranchStep

  /** A dispatch passes `inputs.ships` through unchanged, including the empty string, which `zipxRelease` refuses. A tag
    * push passes `github.ref`. An expression cannot do this: an empty string is falsy, so `inputs.ships || github.ref`
    * would turn a cleared field into the branch ref and release every unreleased ship.
    */
  private val bindRefStep: Step =
    val write = (value: Word.Quotable) =>
      Exec("echo", Word.dquote(Word.lit("ZIPX_RELEASE_REF="), value)).appendTo(Word.vq("GITHUB_ENV"))
    Step
      .run(
        Script.strict(
          If(
            ShTest.varEquals("GITHUB_EVENT_NAME", "workflow_dispatch"),
            Block(write(Word.v("ZIPX_SHIPS"))),
            elifs = Nil,
            elseDo = Some(Block(write(Word.v("GITHUB_REF")))),
          )
        )
      )
      .named("Release request")
      .withEnv("ZIPX_SHIPS", Expr.input("ships"))
      .build
  end bindRefStep

  private val shipsDispatch: WorkflowDispatch =
    WorkflowDispatch(
      ListMap(
        InputName("ships") -> DispatchInput.Text(
          description = ShipsDescription,
          default = Some(All),
          required = true,
        )
      )
    )

  private val releaseStep: Step =
    Step
      .run(Script.strict(Exec("sbt", Word.dquote(Word.lit("zipxRelease "), Word.v("ZIPX_RELEASE_REF")))))
      .named("Release")
      .build

  private val githubReleasesStep: Step =
    Step
      .run(
        Script.strict(
          ForIn(
            VarName("tag"),
            List(Word.subst(Exec("cat", Word.quoted("target/zipx-release-tags.txt")))),
            Block(
              Exec(
                "gh",
                Word.lit("release"),
                Word.lit("create"),
                Word.vq("tag"),
                Word.lit("--target"),
                Word.vq("GITHUB_SHA"),
                Word.lit("--generate-notes"),
              )
            ),
          )
        )
      )
      .named("Tag and publish GitHub Releases")
      .when(dispatched)
      .withEnv("GH_TOKEN", Expr.github("token"))
      .build

  private def docsJob(docs: Capability, tags: TagScheme): Option[(String, Job)] =
    val unseenByCi = tags match
      case TagScheme.Bare   => Some(dispatched.unwrapped)
      case TagScheme.PerRow => None
    docs.workflowCall.map { call =>
      (docs.name.asJobId: String) -> Job(
        name = Some(docs.name),
        runsOn = Nil,
        needs = List(jobId),
        `if` = unseenByCi,
        permissions = ListMap.from(docs.permissions),
        uses = Some(call.uses),
        `with` = ListMap.from(call.withInputs),
      )
    }
  end docsJob
end ReleaseWorkflow
