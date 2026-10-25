package zipx.agent

import zipx.core.{Pin, PinFeedName}

/** The three catalog pins a cloud agent environment renders. Versions stay in `Pin` vals so `zipxPinUpdate` can move
  * them. The image does not copy `sbt.version` or the JDK.
  */
final case class AgentPins(baseImage: String, dockerCe: String, sbtRunner: String)

object AgentPins:

  val BaseFeed: PinFeedName   = PinFeedName("agent-base")
  val DockerFeed: PinFeedName = PinFeedName("agent-docker")
  val SbtFeed: PinFeedName    = PinFeedName("agent-sbt")

  val UbuntuId: String = "ubuntu"
  val DockerId: String = "docker-ce"
  val SbtId: String    = "sbt-runner"

  /** Ubuntu 24.04 (noble), the distro the Docker CE apt pin is built for. Digest of the library tag, not a newer LTS.
    */
  val DefaultBase: String = "sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac"

  /** Newest Docker CE 28.x published for both amd64 and arm64 on noble. Docker 29 stays out. */
  val DefaultDocker: String = "5:28.5.2-1~ubuntu.24.04~noble"

  /** The `sbt-runner-version` default of `sbt/setup-sbt` v1.5.8. The runner reads `project/build.properties`. */
  val DefaultSbtRunner: String = "2.0.7"

  val feedNames: Set[PinFeedName] = Set(BaseFeed, DockerFeed, SbtFeed)

  /** Rows the sbt-image agent does not read. Generate warns and still succeeds. */
  def unusedWhileSbtImage(pins: Seq[Pin]): List[String] =
    pins.iterator
      .filter(pin => feedNames.contains(pin.feed))
      .map(pin => s"""Pin("${pin.feed}", "${pin.id}", "${pin.current}")""")
      .toList
      .sorted

  def resolve(pins: Seq[Pin]): Either[String, AgentPins] =
    val wanted = List(
      (BaseFeed, UbuntuId, DefaultBase),
      (DockerFeed, DockerId, DefaultDocker),
      (SbtFeed, SbtId, DefaultSbtRunner),
    )
    val missing = wanted.collect {
      case (feed, id, version) if !pins.exists(p => p.feed == feed && p.id == id) =>
        s"""Pin("$feed", "$id", "$version")"""
    }
    if missing.nonEmpty then Left(s"zipx: zipxAgentEnv needs catalog pins: ${missing.mkString(", ")}")
    else
      for
        base   <- one(pins, BaseFeed, UbuntuId).flatMap(validateBase)
        docker <- one(pins, DockerFeed, DockerId).flatMap(validateDocker)
        runner <- one(pins, SbtFeed, SbtId).flatMap(validateRunner)
      yield AgentPins(base, docker, runner)

  private def one(pins: Seq[Pin], feed: PinFeedName, id: String): Either[String, String] =
    pins.collectFirst { case pin if pin.feed == feed && pin.id == id => pin.current } match
      case Some(version) => Right(version)
      case None          => Left(s"""zipx: zipxAgentEnv needs Pin("$feed", "$id", "<version>")""")

  def validateBase(raw: String): Either[String, String] =
    if raw.matches("sha256:[a-f0-9]{64}") then Right(raw)
    else Left(s"zipx: agent base pin must be a sha256 digest, got '$raw'")

  def validateDocker(raw: String): Either[String, String] =
    DockerCe.parse(raw) match
      case Some(apt) if apt.major == DockerCe.HeldMajor => Right(raw)
      case Some(_) => Left(s"zipx: agent docker pin must stay on Docker ${DockerCe.HeldMajor}.x, got '$raw'")
      case None    => Left(s"zipx: agent docker pin is not an apt version, got '$raw'")

  def validateRunner(raw: String): Either[String, String] =
    if raw.matches("""2\.\d+\.\d+""") then Right(raw)
    else Left(s"zipx: agent sbt runner pin must be a 2.x release, got '$raw'")
end AgentPins
