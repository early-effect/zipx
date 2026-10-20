package zipx.agent

import zipx.core.{CloudAgent, EnvValue, Pin, PinFeed, SbtCommand, SbtImagePlan}

/** `zipxAgentEnv := ZipxAgentEnv.cursor(docker = true)`. Cursor is the first renderer. The data it draws from is the
  * build's JDK file, rehydrate command, secret env names, and the agent catalog pins.
  */
object ZipxAgentEnv:

  def cursor(docker: Boolean = false): CloudAgent = CloudAgent.On(docker)

  val EnvironmentRel: String = CursorEnv.EnvironmentRel
  val DockerfileRel: String  = CursorEnv.DockerfileRel
  val InstallRel: String     = CursorEnv.InstallRel

  val paths: List[String] = List(EnvironmentRel, DockerfileRel, InstallRel)

  def withFeeds(user: Seq[PinFeed], pins: Seq[Pin], enabled: Boolean): Seq[PinFeed] =
    AgentFeeds.withUser(user, pins, enabled)

  def files(
      agent: CloudAgent.On,
      rehydrate: SbtCommand,
      env: Map[String, EnvValue],
      pins: Seq[Pin],
      image: Option[SbtImagePlan] = None,
      repository: Option[String] = None,
  ): Either[String, AgentFiles] =
    for
      resolved  <- AgentPins.resolve(pins)
      secrets   <- secretNames(env)
      toolchain <- image.fold[Either[String, Option[String]]](Right(None))(_.agentImage(repository).map(Some(_)))
      rendered  <- CursorEnv.render(AgentEnv(agent.docker, rehydrate, secrets, resolved, toolchain))
    yield rendered

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
  end secretNames
end ZipxAgentEnv
