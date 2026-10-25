package zipx.core

import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Puts sbt jobs into [[SbtImagePlan]] and, when tools were baked, publishes that image once per workflow. */
object SbtImageWorkflow:

  val EnvImageJob: String  = "zipx-env-image"
  val EnvImageStep: String = "zipx-env"

  def use(workflow: Workflow, plan: Option[SbtImagePlan], checkout: ActionRef, runnerOs: String): Workflow =
    plan match
      case None        => workflow
      case Some(image) =>
        val decorated = workflow.jobs.map { (id, job) =>
          if id == EnvImageJob || !runsSbt(job) then id -> job
          else id                                       -> decorate(job, image)
        }
        val jobs = image.derivedTag match
          case Some(tag)
              if decorated.values.exists(_.needs.contains(EnvImageJob)) && !decorated.contains(EnvImageJob) =>
            ListMap(EnvImageJob -> envImageJob(tag, checkout, runnerOs)) ++ decorated
          case _ => decorated
        workflow.copy(jobs = jobs)

  /** Host setup, or the local composite with the toolchain steps skipped. */
  def toolchain(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      suffix: String,
      plan: Option[SbtImagePlan],
  ): List[Step] =
    plan match
      case None =>
        List(
          Step(
            name = Some("Setup JDK"),
            uses = Some(pins.setupJava),
            `with` = ListMap("distribution" -> ZipxComposites.JdkDistribution, "java-version" -> javaVersion),
          ),
          Step(uses = Some(pins.setupSbt)),
        )
      case Some(_) => List(bakedSetup(pins, javaVersion, runnerOs, suffix, plan))

  private def bakedSetup(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      suffix: String,
      plan: Option[SbtImagePlan],
  ): Step =
    val jdk = JdkVersion.make(javaVersion).fold(err => sys.error(s"zipx: $err"), identity)
    val os  = RunnerOs.make(runnerOs).fold(err => sys.error(s"zipx: $err"), identity)
    val id  = JobId.make(suffix).fold(err => sys.error(s"zipx: $err"), identity)
    ZipxComposites.sbtSetupStep(
      PlanConfig(javaVersion = jdk, runnerOs = os, actions = pins, sbtImage = plan),
      id,
      None,
      LocalCacheMode.Restore,
    )

  private def runsSbt(job: Job): Boolean =
    job.uses.isEmpty && job.steps.exists { step =>
      step.uses.contains(ZipxComposites.SbtSetupRef) || step.run.exists(isSbt)
    }

  private def isSbt(script: String): Boolean =
    script.contains("sbt ") || script.contains("\nsbt ") || script.endsWith("sbt")

  private def decorate(job: Job, plan: SbtImagePlan): Job =
    val image =
      if plan.dockerfile.isDefined then s"$${{ needs.$EnvImageJob.outputs.image }}"
      else plan.upstream
    val socket = plan.dockerSocket && publishesImages(job)
    val needs  =
      if plan.dockerfile.isDefined && !job.needs.contains(EnvImageJob) then EnvImageJob :: job.needs
      else job.needs
    job.copy(
      container = job.container.orElse(Some(image)),
      containerOptions = job.containerOptions.orElse(Option.when(socket)(SbtImage.SocketOptions)),
      needs = needs,
      permissions = pullPermissions(job.permissions, plan.dockerfile.isDefined),
      env = SbtImage.homeEnv(
        SbtImage.overlayEnv(job.env, plan.layerEnv).fold(err => sys.error(err.message), identity),
        plan.cacheHome,
      ),
    )

  /** A job that already sets permissions does not inherit the repo token, so a private env image is denied. A job with
    * no permissions block keeps inheriting that token.
    */
  private def pullPermissions(permissions: Map[String, String], derived: Boolean): Map[String, String] =
    if !derived || permissions.isEmpty || permissions.contains("packages") then permissions
    else permissions + ("packages" -> "read")

  private def publishesImages(job: Job): Boolean =
    val name = job.name.getOrElse("")
    name == "docker" || name.startsWith("docker-") || job.steps.exists(_.run.exists(runsDocker))

  /** An sbt `Docker/` task builds inside the job, so the runner socket has to be there. A shell `docker ` does too. */
  private def runsDocker(script: String): Boolean =
    script.contains("docker ") || script.contains("Docker/")

  private def envImageJob(tag: String, checkout: ActionRef, runnerOs: String): Job =
    Job(
      name = Some("Publish zipx env image"),
      runsOn = List(runnerOs),
      permissions = ListMap("contents" -> "read", "packages" -> "write"),
      env = ListMap("GITHUB_TOKEN" -> "${{ github.token }}"),
      outputs = ListMap("image" -> s"$${{ steps.$EnvImageStep.outputs.image }}"),
      steps = List(
        Step(uses = Some(checkout)),
        Step(
          id = Some(EnvImageStep),
          name = Some("Publish zipx env image"),
          run = Some(SbtImage.envImageScript(tag)),
        ),
      ),
    )
end SbtImageWorkflow
