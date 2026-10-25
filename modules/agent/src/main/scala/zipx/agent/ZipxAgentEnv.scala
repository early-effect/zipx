package zipx.agent

import zipx.core.{CloudAgent, EnvValue, Pin, PinFeed, SbtCommand, SbtImage, SbtImagePlan}

/** `zipxAgentEnv := ZipxAgentEnv.cursor(docker = true)`. Cursor is the first renderer. The data it draws from is the
  * build's JDK file, rehydrate command, secret env names, and the agent catalog pins.
  */
object ZipxAgentEnv:

  def cursor(docker: Boolean = false): CloudAgent = CloudAgent.On(docker)

  val EnvironmentRel: String = CursorEnv.EnvironmentRel
  val DockerfileRel: String  = CursorEnv.DockerfileRel
  val InstallRel: String     = CursorEnv.InstallRel

  val paths: List[String] = List(EnvironmentRel, DockerfileRel, InstallRel)

  def withFeeds(user: Seq[PinFeed], pins: Seq[Pin], agent: CloudAgent, sbtImage: Boolean): Seq[PinFeed] =
    AgentFeeds.withUser(user, pins, agent, sbtImage)

  def files(
      agent: CloudAgent.On,
      rehydrate: SbtCommand,
      env: Map[String, EnvValue],
      pins: Seq[Pin],
      image: Option[SbtImagePlan] = None,
  ): Either[String, AgentFiles] =
    for
      secrets  <- secretNames(env)
      rendered <- image match
        case Some(plan) =>
          for
            toolchain <- agentImage(plan, env, pins, agent.docker)
            files     <- CursorEnv.render(AgentEnv(agent.docker, rehydrate, secrets, None, Some(toolchain)))
          yield files
        case None =>
          for
            resolved <- AgentPins.resolve(pins)
            files    <- CursorEnv.render(AgentEnv(agent.docker, rehydrate, secrets, Some(resolved), None))
          yield files
    yield rendered

  /** Plain job env, then layer env, then the home and git env CI jobs already set. Secrets stay out. */
  def agentImage(
      plan: SbtImagePlan,
      env: Map[String, EnvValue],
      pins: Seq[Pin],
      docker: Boolean,
  ): Either[String, AgentImage] =
    for
      daemon <- if docker then AgentDaemon.resolve(pins).map(Some(_)) else Right(None)
      merged <- SbtImage.overlayEnv(plainEnv(env), plan.layerEnv).left.map(_.message)
    yield AgentImage(
      upstream = plan.upstream,
      baked = plan.dockerfile,
      dockerCli = daemon.map(_.dockerCli),
      fuseOverlayfs = daemon.map(_.fuseOverlayfs),
      iptables = daemon.map(_.iptables),
      env = SbtImage.homeEnv(merged, plan.cacheHome),
    )

  private def plainEnv(env: Map[String, EnvValue]): Map[String, String] =
    env.collect { case (key, EnvValue.Plain(value)) => key -> value }

  /** Env names sbt reads from the process. Plain values such as `COURSIER_TTL` are not secrets. `GITHUB_TOKEN` is a
    * Cursor Runtime Secret: a Build Secret is mounted only for `docker build` and is gone before `sbt test`.
    */
  def secretNames(env: Map[String, EnvValue]): Either[String, List[String]] =
    val names = env
      .collect {
        case (name, EnvValue.FromSecret(_))                 => name
        case (name, value) if value == EnvValue.githubToken => name
      }
      .toList
      .distinct
      .sorted
    names.foldLeft[Either[String, List[String]]](Right(Nil)) { (acc, name) =>
      acc.flatMap { found =>
        if name.matches("[A-Za-z_][A-Za-z0-9_]*") then Right(found :+ name)
        else Left(s"zipx: secret-backed env name '$name' is not a shell identifier")
      }
    }
end ZipxAgentEnv
