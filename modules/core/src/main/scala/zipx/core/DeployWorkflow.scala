package zipx.core

import neotype.unwrap
import zipx.shell.*
import zipx.workflow.*

import scala.collection.immutable.ListMap

/** Where image pushes and deploys run. */
enum DeployTrigger:

  /** In `ci.yml`, on whatever their gates and conditions select. */
  case OnMerge

  /** In `zipx-deploy.yml`, only when someone runs it, so a merge ships nothing. Image jobs bind `images`, so every push
    * is a recorded GitHub deployment.
    */
  case Manual(images: String = DeployWorkflow.ImagesEnvironment)

/** `zipx-deploy.yml`: images and deploys, run by hand from a plan.
  *
  * It takes every image capability ([[Capability.DockerName]]), everything that needs one, and every [[Phase.Deploy]]
  * capability out of `ci.yml`. A `resolve` job turns the dispatch inputs into a [[DeployPlan]]: `changed` compares each
  * Environment's last successful deploy of each module with the commit being deployed. Image jobs push a tag only when
  * a registry lacks it, and every job checks out and records that commit.
  */
object DeployWorkflow:

  val DefaultPath: String       = ".github/workflows/zipx-deploy.yml"
  val ImagesEnvironment: String = "zipx-images"
  val ResolveJobId: JobId       = JobId("resolve")

  /** The commit a deploy ships, in every job's env. A build that tags images by commit reads this first. */
  val ShaEnv: String = "ZIPX_DEPLOY_SHA"

  val ModulesInput: InputName = InputName("modules")
  val TargetInput: InputName  = InputName("target")
  val ShaInput: InputName     = InputName("sha")

  /** Written by `zipxDeployPlan`; the resolve job copies them into its outputs. */
  val ShaFile: String     = "target/zipx-deploy/sha"
  val ImagesFile: String  = "target/zipx-deploy/images.json"
  val TargetsFile: String = "target/zipx-deploy/targets.json"

  /** Written by `<module>/zipxImageMissing`: `true` when some registry lacks the image's tag. */
  val ImageMissingFile: String = "target/zipx-image-missing"

  private val Sha: OutputName     = OutputName("sha")
  private val Images: OutputName  = OutputName("images")
  private val Targets: OutputName = OutputName("targets")

  val shaOutput: Expr     = Expr.JobOutput(ResolveJobId, Sha)
  val imagesOutput: Expr  = Expr.JobOutput(ResolveJobId, Images)
  val targetsOutput: Expr = Expr.JobOutput(ResolveJobId, Targets)

  def isImage(capability: Capability): Boolean = capability.name == Capability.DockerName

  /** `…/commit/<sha>#<module>`, which [[GitHubDeployments]] reads back. */
  def deployedUrl(module: Expr): Expr =
    Expr.github("server_url") ++ Expr.lit("/") ++ Expr.github("repository") ++ Expr.lit("/commit/") ++ shaOutput ++
      Expr.lit("#") ++ module

  private val ImageTagsStep: StepId = StepId("image-tags")

  def imageTagCheck(module: Expr): Step =
    Step
      .run(
        Script(
          Exec("sbt", Word.dquote(module.asWord, Word.lit("/zipxImageMissing"))),
          Exec("echo", Word.dquote(Word.lit("missing="), Word.subst(Exec("cat", path(ImageMissingFile)))))
            .appendTo(Word.vq("GITHUB_OUTPUT")),
        )
      )
      .named("Check image tags")
      .withStepId(ImageTagsStep)
      .build

  val imageMissing: String =
    (Expr.StepOutput(ImageTagsStep, OutputName("missing")) === Expr.quoted("true")).unwrapped

  final case class Split(ci: List[Capability], deploy: List[Capability])

  /** Image capabilities, every Deploy capability, and everything that needs one of them, transitively. */
  def split(capabilities: List[Capability]): Split =
    def closure(names: Set[CapabilityName]): Set[CapabilityName] =
      val next = names ++ capabilities.filter(_.needsCapabilities.exists(names.contains)).map(_.name)
      if next == names then names else closure(next)
    val moved        = closure(capabilities.filter(c => isImage(c) || c.phase == Phase.Deploy).map(_.name).toSet)
    val (deploy, ci) = capabilities.partition(c => moved.contains(c.name))
    Split(ci, deploy)

  /** Every reason `zipx-deploy.yml` could not run `split.deploy` as declared. */
  def problems(split: Split, graph: ModuleGraph): List[String] =
    val deployNames = split.deploy.map(_.name).toSet
    val targets = split.deploy.flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets)).distinctBy(_.name)
    val perCapability = split.deploy.flatMap { c =>
      List(
        Option.when(c.scope != CapabilityScope.Graph)(
          s"'${c.name}' is ${c.scope}-scoped; zipx-deploy.yml plans per module, so it needs CapabilityScope.Graph"
        ),
        Option.when(c.phase == Phase.Verify)(
          s"'${c.name}' is a Verify capability that needs an image; verify in ci.yml without it"
        ),
        c.needsCapabilities.filterNot(deployNames.contains).headOption.map { need =>
          s"'${c.name}' needs '$need', which stays in ci.yml; a job cannot need a job in another workflow"
        },
        c.condition.flatMap(pushOnly).map { event =>
          s"'${c.name}' has a condition requiring event '$event', which is never true in zipx-deploy.yml " +
            "(workflow_dispatch only); drop it, since the dispatch decides when this runs"
        },
      ).flatten
    }
    val unrecorded = split.deploy
      .filter(_.phase == Phase.Deploy)
      .flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets).filter(_.environment.isEmpty).map(c -> _))
      .distinctBy((c, t) => (c.name, t.name))
      .map { (c, t) =>
        s"'${c.name}' target '${t.name}' binds no Environment; zipx-deploy.yml reads each Environment's deployments " +
          "to know what shipped, so set Target.environment"
      }
    val clashes = targets.flatMap(_.group).distinct.filter(g => targets.exists(_.name == (g: String))).map { g =>
      s"target group '$g' is also a target name, so the dispatch choice '$g' would be ambiguous"
    }
    (perCapability ++ unrecorded ++ clashes).map("zipx: DeployTrigger.Manual: " + _)
  end problems

  /** An event a condition requires at its top level, other than `workflow_dispatch`. */
  private def pushOnly(condition: JobCondition): Option[String] =
    def required(c: JobCondition): List[String] = c match
      case JobCondition.EventIs(name)    => List(name.unwrap)
      case JobCondition.All(first, rest) => (first :: rest).flatMap(required)
      case _                             => Nil
    required(condition).find(_ != "workflow_dispatch")

  /** What a dispatch could ship, for [[DeployPlan.resolve]]. */
  def scope(graph: ModuleGraph, deploy: List[Capability], imagesEnvironment: String): DeployScope =
    val images   = deploy.filter(isImage).flatMap(c => graph.nodes.filter(c.participates).map(_.id)).distinct.sorted
    val byTarget =
      for
        c      <- deploy
        node   <- graph.nodes.filter(c.participates)
        target <- c.targets(node)
        env    <- target.environment.toList
      yield (target.name, env, node.id)
    val targets = byTarget.groupBy((t, env, _) => (t, env)).toList.sortBy((k, _) => k._1: String).map {
      case ((t, env), rows) => TargetModules(t, env, rows.map(_._3).distinct.sorted)
    }
    DeployScope(imagesEnvironment, images, targets)
  end scope

  /** Target names first, then groups, each sorted. */
  def targetChoices(graph: ModuleGraph, deploy: List[Capability]): List[String] =
    val targets = deploy.flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets))
    targets.map(t => t.name: String).distinct.sorted ++ targets.flatMap(_.group).map(g => g: String).distinct.sorted

  /** The targets a `target` input selects: that target, or every target in that group. */
  def selectedTargets(graph: ModuleGraph, deploy: List[Capability], choice: String): Either[String, Set[TargetName]] =
    val targets  = deploy.flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets))
    val selected = targets.filter(t => (t.name: String) == choice || t.group.exists(g => (g: String) == choice))
    if targets.isEmpty then Right(Set.empty)
    else if selected.isEmpty then Left(s"zipx: target '$choice' is not a target or group of this deploy")
    else Right(selected.map(_.name).toSet)

  /** One job per module and target, never a collapsed matrix: a matrix binds its Environment on every leg, so a leg the
    * plan skips would still wait for that Environment's approval and record a deploy that never happened.
    */
  def plan(graph: ModuleGraph, deploy: List[Capability], config: PlanConfig, imagesEnvironment: String): Workflow =
    val deployConfig = config.copy(affectedPublish = true, affectedDeploy = true, modverPublish = false)
    val perModule    = deploy.map(_.withMatrixCollapse(MatrixCollapse.Off))
    val byName       = perModule.map(c => c.name -> c).toMap
    val gated        = perModule.map(_.name).toSet
    val ordered      = perModule.zipWithIndex.sortBy((c, i) => (c.phase.ordinal, i)).map(_._1)
    val jobs         = ordered.flatMap(c =>
      Planner.graphCapabilityJobs(
        c,
        graph,
        deployConfig,
        usesAffected = true,
        byName,
        usesVerifyGate = false,
        gated,
        Planner.Pipeline.Deploy(imagesEnvironment),
      )
    )
    val choices  = targetChoices(graph, deploy)
    val shipping = scope(graph, deploy, imagesEnvironment)
    val modules  = (shipping.images ++ shipping.targets.flatMap(_.modules)).distinct.sorted.map(id => id: String)
    val inputs   = ListMap(
      ModulesInput -> DispatchInput.Choice(
        "changed: what differs from each Environment's last deploy; all; or one module",
        ::(DeployModules.ChangedWire, DeployModules.AllWire :: modules),
      )
    ) ++ (choices match
      case head :: tail => ListMap(TargetInput -> DispatchInput.Choice("Target or group to deploy to", ::(head, tail)))
      case Nil          => ListMap.empty) ++
      ListMap(ShaInput -> DispatchInput.Text("Commit to deploy (40 hex). Empty deploys the dispatched branch head."))
    val group =
      if choices.isEmpty then Expr.lit("zipx-deploy")
      else Expr.lit("zipx-deploy-") ++ Expr.Input(TargetInput)
    Workflow(
      name = "zipx deploy",
      on = Triggers(workflowDispatch = Some(WorkflowDispatch(inputs))),
      concurrency = Some(Concurrency(group.render, CancelInProgress.Never)),
      jobs = ListMap.from[String, Job]((ResolveJobId -> resolveJob(config, choices.nonEmpty)) :: jobs),
    )
  end plan

  def render(
      graph: ModuleGraph,
      deploy: List[Capability],
      config: PlanConfig,
      imagesEnvironment: String,
  ): Either[String, String] =
    Render.render(plan(graph, deploy, config, imagesEnvironment)).map(ActionPinFile.annotateUses(_, config.actions))

  /** Its own token variable, because a build's `zipxEnv` may point `GITHUB_TOKEN` at a packages token that cannot read
    * deployments.
    */
  val TokenEnv: String = "ZIPX_GITHUB_TOKEN"

  val ModulesEnv: String      = "ZIPX_DEPLOY_MODULES"
  val TargetEnv: String       = "ZIPX_DEPLOY_TARGET"
  val RequestedShaEnv: String = "ZIPX_DEPLOY_REQUESTED_SHA"

  private val PlanStep: StepId = StepId("plan")

  private def resolveJob(config: PlanConfig, hasTargets: Boolean): Job =
    def output(assign: Word.Lit, file: Word): Word = Word.dquote(assign, Word.subst(Exec("cat", file)))
    val stepEnv                                    =
      ListMap(
        ModulesEnv      -> Expr.Input(ModulesInput).render,
        RequestedShaEnv -> Expr.Input(ShaInput).render,
        TokenEnv        -> Expr.githubToken.render,
      ) ++ (if hasTargets then ListMap(TargetEnv -> Expr.Input(TargetInput).render) else ListMap.empty)
    val resolve = Step
      .run(
        Script(
          Exec("sbt", Word.lit("zipxDeployPlan")),
          Exec(
            "printf",
            Word.squote("%s\\n"),
            output(Word.lit("sha="), path(ShaFile)),
            output(Word.lit("images="), path(ImagesFile)),
            output(Word.lit("targets="), path(TargetsFile)),
          ).appendTo(Word.vq("GITHUB_OUTPUT")),
        )
      )
      .named("Resolve deploy plan")
      .withStepId(PlanStep)
      .withEnvs(stepEnv)
      .build
    def fromPlan(name: OutputName): (String, String) = name.unwrap -> Expr.StepOutput(PlanStep, name).render
    Job(
      name = Some("resolve"),
      runsOn = List(config.runnerOs),
      permissions = ListMap("contents" -> "read", "deployments" -> "read"),
      env = EnvValue.renderAll(config.env),
      outputs = ListMap(fromPlan(Sha), fromPlan(Images), fromPlan(Targets)),
      steps = Planner.checkoutThenSbtSetup(config, ResolveJobId, nodeVersion = None, cacheMode(config)) :+ resolve,
    )
  end resolveJob

  /** `unsafeMake` because every caller passes one of the relative-path constants above. */
  private def path(file: String): Word = Word.Lit(ShText.unsafeMake(file))

  private def cacheMode(config: PlanConfig): LocalCacheMode =
    if config.cache == CacheBackend.LocalDir then LocalCacheMode.Restore else LocalCacheMode.Off

end DeployWorkflow
