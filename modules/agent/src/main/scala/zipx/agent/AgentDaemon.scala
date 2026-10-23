package zipx.agent

import zipx.core.{Pin, PinAction, PinCandidate, PinFeed, PinFeedName, SbtImage, VersionStrategy, WolfiToolchain}

/** Apk pins for the daemon the toolchain-image agent installs. An absent pin uses [[zipx.core.WolfiToolchain]].
  * `ubuntu-suite` is a retired id: the lookup ignores it.
  */
object AgentDaemon:

  val Feed: PinFeedName = PinFeedName("agent-daemon")

  val SuiteId: String    = "ubuntu-suite"
  val FuseId: String     = "fuse-overlayfs"
  val IptablesId: String = "iptables"

  val DefaultFuse: String     = WolfiToolchain.FuseOverlayfs
  val DefaultIptables: String = WolfiToolchain.Iptables

  val feedNames: Set[PinFeedName] = Set(Feed)

  def feed: PinFeed = PinFeed(
    name = Feed,
    classify = VersionStrategy.exact,
    lookup = pin =>
      pin.id match
        case FuseId     => ApkIndex.candidate(pin.current, FuseId).map(_.map(PinCandidate(_)))
        case IptablesId => ApkIndex.candidate(pin.current, IptablesId).map(_.map(PinCandidate(_)))
        case _          => Right(None)
    ,
    outdated = PinAction.Update,
    advisory = PinAction.Ignore,
  )

  def resolve(pins: Seq[Pin]): Either[String, DaemonPins] =
    for
      cli      <- pinOr(pins, SbtImage.Feed, SbtImage.DockerCliId, SbtImage.DefaultDockerCli).flatMap(validateCli)
      fuse     <- pinOr(pins, Feed, FuseId, DefaultFuse).flatMap(validateApk)
      iptables <- pinOr(pins, Feed, IptablesId, DefaultIptables).flatMap(validateApk)
    yield DaemonPins(cli, fuse, iptables)

  def validateCli(raw: String): Either[String, String] =
    if raw.matches("""\d+\.\d+\.\d+""") then Right(raw)
    else Left(s"zipx: docker-cli pin must be x.y.z, got '$raw'")

  def validateApk(raw: String): Either[String, String] =
    WolfiToolchain.parseApk(raw).map(_.raw).toRight(s"zipx: apk pin '$raw' must be version-rN")

  private def pinOr(pins: Seq[Pin], feed: PinFeedName, id: String, default: String): Either[String, String] =
    pins.collectFirst { case pin if pin.feed == feed && pin.id == id => pin.current } match
      case Some(version) => Right(version)
      case None          => Right(default)
end AgentDaemon

final case class DaemonPins(dockerCli: String, fuseOverlayfs: String, iptables: String)
