package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** `zipx-release.yml`, the only place a Ship row's catalog number is published: a GitHub Release's tag releases its
  * row, and a dispatch on the default branch releases every unreleased row, in one sbt session either way.
  */
final case class ReleaseWorkflow(
    registry: ArtifactRegistry,
    env: Map[String, EnvValue] = Map.empty,
    steps: Steps = Steps.empty,
    environment: Option[String] = Some(ReleaseWorkflow.DefaultEnvironment),
)

object ReleaseWorkflow:

  val DefaultPath: String        = ".github/workflows/zipx-release.yml"
  val DefaultEnvironment: String = "zipx-release"
  val TagPatterns: List[String]  = List("v*", "*/v*")

  val TagsFile: String = "target/zipx-release-tags.txt"

  private val jobId: JobId  = JobId("release")
  private val refVar        = "ZIPX_RELEASE_REF"
  private val tagPushed     = Expr.github("event_name") === Expr.quoted("push")
  private val dispatched    = Expr.github("event_name") === Expr.quoted("workflow_dispatch")
  private val defaultBranch = Expr.github("event.repository.default_branch")
  private val onDefaultRef  = Expr.github("ref_name") === defaultBranch
  private val buildContext  = StepContext(ModuleNode(id = ModuleId("_build")), target = None, matrixed = false)

  /** `docs` deploys after a dispatch only: its tags are pushed with `GITHUB_TOKEN`, which starts no other workflow. */
  def plan(release: ReleaseWorkflow, config: PlanConfig, docs: Option[Capability] = None): Workflow =
    val cacheMode = if config.cache == CacheBackend.LocalDir then LocalCacheMode.Restore else LocalCacheMode.Off
    val job       = Job(
      name = Some("release"),
      runsOn = List(config.runnerOs),
      `if` = Some((tagPushed || onDefaultRef).unwrapped),
      environment = release.environment.map(JobEnvironment(_)),
      env = EnvValue.renderAll(config.env ++ release.env) ++ ListMap(refVar -> Expr.github("ref").render),
      steps = Planner.checkoutThenSbtSetup(config, jobId, nodeVersion = None, cacheMode) ++
        release.steps(buildContext.copy(actions = config.actions)) ++
        List(onDefaultBranchStep, releaseStep, githubReleasesStep),
    )
    Workflow(
      name = "zipx release",
      on = Triggers(push = Some(BranchFilter(tags = TagPatterns)), workflowDispatch = Some(WorkflowDispatch())),
      permissions = ListMap("contents" -> "write"),
      concurrency = Some(Concurrency(group = "zipx-release", cancelInProgress = CancelInProgress.Never)),
      jobs = ListMap[String, Job](jobId -> job) ++ docs.flatMap(docsJob),
    )
  end plan

  def render(release: ReleaseWorkflow, config: PlanConfig, docs: Option[Capability] = None): Either[String, String] =
    Render.render(plan(release, config, docs)).map(ActionPinFile.annotateUses(_, config.actions))

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

  private def docsJob(docs: Capability): Option[(String, Job)] =
    docs.workflowCall.map { call =>
      (docs.name.asJobId: String) -> Job(
        name = Some(docs.name),
        runsOn = Nil,
        needs = List(jobId),
        `if` = Some(dispatched.unwrapped),
        permissions = ListMap.from(docs.permissions),
        uses = Some(call.uses),
        `with` = ListMap.from(call.withInputs),
      )
    }
end ReleaseWorkflow
