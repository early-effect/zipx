package zipx.core

import neotype.unwrap
import zipx.shell.*
import zipx.workflow.*

import scala.collection.immutable.ListMap

/** Which commits may reach a [[Target]] from `zipx-deploy.yml`. */
enum DeployStage:

  /** Only a dispatch on the default branch, of a commit on that branch. Pair its Environment with a default-branch
    * deployment policy, which GitHub enforces even against a workflow edited on a branch.
    */
  case Production

  /** Also every merge under [[DeployTrigger.Staged]], and every push to a PR carrying its deploy label. */
  case PreProduction

/** Where image pushes and deploys run. */
enum DeployTrigger:

  /** In `ci.yml`, on whatever their gates and conditions select. */
  case OnMerge

  /** In `zipx-deploy.yml`, only when someone runs it, so a merge ships nothing. Image jobs bind `images`, so every push
    * is a recorded GitHub deployment.
    */
  case Manual(images: String = DeployWorkflow.ImagesEnvironment)

  /** In `zipx-deploy.yml`: every merge deploys its changed modules to the [[DeployStage.PreProduction]] targets unless
    * its PR carries `skipLabel`, a PR carrying `deployLabel` deploys its head there on each push, and a dispatch from
    * the default branch deploys anywhere. Nothing but that dispatch reaches a [[DeployStage.Production]] target, and a
    * branch reaches nothing without the label.
    */
  case Staged(deployLabel: ExprLiteral, skipLabel: ExprLiteral, images: String = DeployWorkflow.ImagesEnvironment)

  /** The images Environment `zipx-deploy.yml` records pushes in, or `None` when there is no deploy workflow. */
  def imagesEnvironment: Option[String] = this match
    case OnMerge              => None
    case Manual(images)       => Some(images)
    case Staged(_, _, images) => Some(images)
end DeployTrigger

object DeployTrigger:

  inline def staged(inline deployLabel: String, inline skipLabel: String): DeployTrigger =
    Staged(ExprLiteral(deployLabel), ExprLiteral(skipLabel))

  def stagedMake(deployLabel: String, skipLabel: String): Either[String, DeployTrigger] =
    for
      deploy <- ExprLiteral.make(deployLabel.trim)
      skip   <- ExprLiteral.make(skipLabel.trim)
      _      <- Either.cond(deploy != skip, (), s"zipx: the deploy and skip labels are both '$deploy'")
    yield Staged(deploy, skip)
end DeployTrigger

/** `zipx-deploy.yml`: images and deploys, run from a plan.
  *
  * It takes every image capability ([[Capability.DockerName]]), everything that needs one, and every [[Phase.Deploy]]
  * capability out of `ci.yml`. A `resolve` job turns the event into a [[DeployPlan]] (see [[DeployRequest.select]] for
  * what each event may reach): `changed` compares each Environment's last successful deploy of each module with the
  * commit being deployed. Image jobs push a tag only when a registry lacks it, and every job checks out and records
  * that commit. Each job that binds an Environment is its own concurrency group, so two runs never interleave on it.
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

  /** Every reason `zipx-deploy.yml` could not run `split.deploy` as declared under `trigger`. */
  def problems(split: Split, graph: ModuleGraph, trigger: DeployTrigger): List[String] =
    val deployNames   = split.deploy.map(_.name).toSet
    val declared      = split.deploy.flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets))
    val targets       = declared.distinctBy(_.name)
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
          s"'${c.name}' has a condition requiring event '$event', which a dispatch of zipx-deploy.yml never " +
            "satisfies; drop it, since the deploy plan decides when this runs"
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
    val reserved = Option.when(targets.exists(t => (t.name: String) == DeployRequest.ChooseWire))(
      s"a target is named '${DeployRequest.ChooseWire}', the dispatch form's deploy-nothing default"
    )
    val mixedStages = declared.groupBy(_.name).toList.sortBy((t, _) => t: String).collect {
      case (t, ts) if ts.map(_.stage).distinct.sizeIs > 1 =>
        s"target '$t' is Production in one capability and PreProduction in another; a target has one stage"
    }
    val staged = trigger match
      case DeployTrigger.Staged(deploy, skip, _) =>
        List(
          Option.when(!targets.exists(_.stage == DeployStage.PreProduction))(
            "no target is DeployStage.PreProduction, so a merge or labeled PR would deploy nothing; mark the " +
              "staging targets PreProduction, or use DeployTrigger.Manual()"
          ),
          Option.when(deploy == skip)(s"the deploy and skip labels are both '$deploy'"),
        ).flatten
      case _ => Nil
    (perCapability ++ unrecorded ++ clashes ++ reserved ++ mixedStages ++ staged)
      .map(s"zipx: ${triggerName(trigger)}: " + _)
  end problems

  private def triggerName(trigger: DeployTrigger): String = trigger match
    case DeployTrigger.OnMerge         => "DeployTrigger.OnMerge"
    case DeployTrigger.Manual(_)       => "DeployTrigger.Manual"
    case DeployTrigger.Staged(_, _, _) => "DeployTrigger.Staged"

  /** Each deploy target's [[DeployStage]]. [[problems]] refuses a target declared with two. */
  def stages(graph: ModuleGraph, deploy: List[Capability]): Map[TargetName, DeployStage] =
    deploy.flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets)).map(t => t.name -> t.stage).toMap

  /** An event a condition requires at its top level, other than `workflow_dispatch`. */
  private def pushOnly(condition: JobCondition): Option[String] =
    def required(c: JobCondition): List[String] = c match
      case JobCondition.EventIs(name)    => List(name.unwrap)
      case JobCondition.All(first, rest) => (first :: rest).flatMap(required)
      case _                             => Nil
    required(condition).find(_ != "workflow_dispatch")

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

  def targetChoices(graph: ModuleGraph, deploy: List[Capability]): List[String] =
    val targets = deploy.flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets))
    targets.map(t => t.name: String).distinct.sorted ++ targets.flatMap(_.group).map(g => g: String).distinct.sorted

  def selectedTargets(graph: ModuleGraph, deploy: List[Capability], choice: String): Either[String, Set[TargetName]] =
    val targets  = deploy.flatMap(c => graph.nodes.filter(c.participates).flatMap(c.targets))
    val selected = targets.filter(t => (t.name: String) == choice || t.group.exists(g => (g: String) == choice))
    if targets.isEmpty then Right(Set.empty)
    else if selected.isEmpty then Left(s"zipx: target '$choice' is not a target or group of this deploy")
    else Right(selected.map(_.name).toSet)

  /** One job per module and target, never a collapsed matrix: a matrix binds its Environment on every leg, so a leg the
    * plan skips would still wait for that Environment's approval and record a deploy that never happened.
    */
  def plan(graph: ModuleGraph, deploy: List[Capability], config: PlanConfig, trigger: DeployTrigger): Workflow =
    val imagesEnvironment = trigger.imagesEnvironment.getOrElse(ImagesEnvironment)
    val deployConfig      = config.copy(affectedPublish = true, affectedDeploy = true)
    val perModule         = deploy.map(_.withMatrixCollapse(MatrixCollapse.Off))
    val byName            = perModule.map(c => c.name -> c).toMap
    val gated             = perModule.map(_.name).toSet
    val ordered           = perModule.zipWithIndex.sortBy((c, i) => (c.phase.ordinal, i)).map(_._1)
    val jobs              = ordered.flatMap(c =>
      Planner.graphCapabilityJobs(
        c,
        graph,
        deployConfig,
        usesAffected = true,
        byName,
        usesVerifyGate = false,
        usesVerifyRollup = false,
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
    ) ++ (if choices.isEmpty then ListMap.empty
          else
            ListMap(
              TargetInput -> DispatchInput.Choice(
                s"Target or group to deploy to (${DeployRequest.ChooseWire} deploys nothing)",
                ::(DeployRequest.ChooseWire, choices),
              )
            )) ++
      ListMap(ShaInput -> DispatchInput.Text("Commit to deploy (40 hex). Empty deploys the dispatched branch head."))
    val dispatch    = WorkflowDispatch(inputs)
    val (on, group) = trigger match
      case DeployTrigger.Staged(_, _, _) =>
        // One group per event source: a PR's runs replace each other, and never a pending merge or dispatch.
        val bySource = Expr.lit("zipx-deploy-") ++ Expr.github("event_name") ++ Expr.lit("-") ++
          Expr.github("event.pull_request.number") ++ Expr.Input(TargetInput)
        val staged = Triggers(
          push = Some(BranchFilter(branches = config.pushBranches)),
          pullRequest = Some(PullRequestTrigger(types = PrActivities)),
          workflowDispatch = Some(dispatch),
        )
        (staged, bySource)
      case _ =>
        val byTarget =
          if choices.isEmpty then Expr.lit("zipx-deploy") else Expr.lit("zipx-deploy-") ++ Expr.Input(TargetInput)
        (Triggers(workflowDispatch = Some(dispatch)), byTarget)
    Workflow(
      name = "zipx deploy",
      on = on,
      concurrency = Some(Concurrency(group.render, CancelInProgress.Never)),
      jobs = ListMap.from[String, Job]((ResolveJobId -> resolveJob(config, choices.nonEmpty, trigger)) :: jobs),
    )
  end plan

  def render(
      graph: ModuleGraph,
      deploy: List[Capability],
      config: PlanConfig,
      trigger: DeployTrigger,
  ): Either[String, String] =
    Render.render(plan(graph, deploy, config, trigger)).map(ActionPinFile.annotateUses(_, config.actions))

  /** `labeled` starts a PR deploy when the label goes on; the rest keep it current while the label stays. */
  private val PrActivities: List[PullRequestActivity] =
    List(
      PullRequestActivity.Opened,
      PullRequestActivity.Synchronize,
      PullRequestActivity.Reopened,
      PullRequestActivity.Labeled,
    )

  /** Its own token variable, because a build's `zipxEnv` may point `GITHUB_TOKEN` at a packages token that cannot read
    * deployments.
    */
  val TokenEnv: String = "ZIPX_GITHUB_TOKEN"

  val ModulesEnv: String       = "ZIPX_DEPLOY_MODULES"
  val TargetEnv: String        = "ZIPX_DEPLOY_TARGET"
  val RequestedShaEnv: String  = "ZIPX_DEPLOY_REQUESTED_SHA"
  val DefaultBranchEnv: String = "ZIPX_DEFAULT_BRANCH"

  /** The PR's labels as a JSON array, on a `pull_request` run. */
  val PrLabelsEnv: String = "ZIPX_DEPLOY_PR_LABELS"

  private val PlanStep: StepId = StepId("plan")

  private val prLabels: Expr = Expr.github("event.pull_request.labels.*.name")

  private def resolveJob(config: PlanConfig, hasTargets: Boolean, trigger: DeployTrigger): Job =
    def output(assign: Word.Lit, file: Word): Word = Word.dquote(assign, Word.subst(Exec("cat", file)))
    // A PR run deploys its head; a merge or an empty dispatch input deploys the checked-out commit.
    val requested = trigger match
      case DeployTrigger.Staged(_, _, _) => Expr.Input(ShaInput) || Expr.github("event.pull_request.head.sha")
      case _                             => Expr.Input(ShaInput)
    val stepEnv =
      ListMap(
        ModulesEnv       -> Expr.Input(ModulesInput).render,
        RequestedShaEnv  -> requested.render,
        TokenEnv         -> Expr.githubToken.render,
        DefaultBranchEnv -> Expr.github("event.repository.default_branch").render,
      ) ++ (if hasTargets then ListMap(TargetEnv -> Expr.Input(TargetInput).render) else ListMap.empty) ++
        (trigger match
          case DeployTrigger.Staged(_, _, _) => ListMap(PrLabelsEnv -> Expr.call("toJSON", prLabels).render)
          case _                             => ListMap.empty)
    // Unlabeled PR runs stop here, before a runner boots sbt; every other job needs this one to succeed.
    val labelGate = trigger match
      case DeployTrigger.Staged(label, _, _) =>
        Some(
          ((Expr.github("event_name") !== Expr.quoted("pull_request")) || Expr
            .contains(prLabels, Expr.Quoted(label))).unwrapped
        )
      case _ => None
    val readsPrs = trigger match
      case DeployTrigger.Staged(_, _, _) => ListMap("pull-requests" -> "read")
      case _                             => ListMap.empty
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
      `if` = labelGate,
      permissions = ListMap("contents" -> "read", "deployments" -> "read") ++ readsPrs,
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
