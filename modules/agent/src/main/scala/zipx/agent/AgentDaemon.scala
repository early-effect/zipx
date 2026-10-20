package zipx.agent

import java.io.{ByteArrayInputStream, IOException}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration
import java.util.zip.GZIPInputStream
import java.nio.charset.StandardCharsets

import zipx.core.{Pin, PinAction, PinCandidate, PinFeed, PinFeedName, SbtImage, VersionStrategy}

import scala.util.Using

/** Apt pins for the daemon the sbt-image agent installs. Versions are Debian revisions (`1.14-1build2`), which the
  * `sbt-image` feed cannot carry. `ubuntu-suite` names the release those versions belong to. The Dockerfile installs
  * from the image's own archives and does not write the suite.
  */
object AgentDaemon:

  val Feed: PinFeedName = PinFeedName("agent-daemon")

  val SuiteId: String    = "ubuntu-suite"
  val FuseId: String     = "fuse-overlayfs"
  val IptablesId: String = "iptables"

  /** Ubuntu suite of the Temurin 25 sbt image. The feed does not invent a different suite. */
  val DefaultSuite: String = "resolute"

  val DefaultFuse: String     = "1.14-1build2"
  val DefaultIptables: String = "1.8.11-2ubuntu3"

  val feedNames: Set[PinFeedName] = Set(Feed)

  def packagesUrl(suite: String, component: String, arch: String): String =
    s"https://archive.ubuntu.com/ubuntu/dists/$suite/$component/binary-$arch/Packages.gz"

  def feed(pins: Seq[Pin]): PinFeed = PinFeed(
    name = Feed,
    classify = VersionStrategy.exact,
    lookup = pin =>
      pin.id match
        case SuiteId    => Right(None)
        case FuseId     => candidate(pins, FuseId, "universe", pin.current)
        case IptablesId => candidate(pins, IptablesId, "main", pin.current)
        case _          => Right(None)
    ,
    outdated = PinAction.Update,
    advisory = PinAction.Ignore,
  )

  def resolve(pins: Seq[Pin]): Either[String, DaemonPins] =
    val wanted = List(
      (SbtImage.Feed, SbtImage.DockerCliId, "28.5.2"),
      (Feed, FuseId, DefaultFuse),
      (Feed, IptablesId, DefaultIptables),
      (Feed, SuiteId, DefaultSuite),
    )
    val missing = wanted.collect {
      case (feed, id, version) if !pins.exists(pin => pin.feed == feed && pin.id == id) =>
        s"""Pin("$feed", "$id", "$version")"""
    }
    if missing.nonEmpty then Left(s"zipx: zipxAgentEnv needs catalog pins: ${missing.mkString(", ")}")
    else
      for
        cli      <- one(pins, SbtImage.Feed, SbtImage.DockerCliId).flatMap(validateCli)
        fuse     <- one(pins, Feed, FuseId).flatMap(validateApt)
        iptables <- one(pins, Feed, IptablesId).flatMap(validateApt)
        suite    <- one(pins, Feed, SuiteId).flatMap(validateSuite)
      yield DaemonPins(cli, fuse, iptables, suite)
  end resolve

  def versions(body: String, packageName: String): List[String] =
    body.split("\n\n", -1).toList.flatMap { stanza =>
      val lines = stanza.split("\n", -1).toList
      val name  = lines.collectFirst { case line if line.startsWith("Package: ") => line.stripPrefix("Package: ") }
      val ver   = lines.collectFirst { case line if line.startsWith("Version: ") => line.stripPrefix("Version: ") }
      (name, ver) match
        case (Some(found), Some(version)) if found == packageName => Some(version)
        case _                                                    => None
    }

  /** Newest version present on both architectures, when it is newer than `current`. */
  def newest(current: String, amd64: String, arm64: String, packageName: String): Either[String, Option[String]] =
    val both = versions(amd64, packageName).toSet.intersect(versions(arm64, packageName).toSet)
    both.toList.maxOption(using DebianVersion.ordering) match
      case None => Left(s"zipx: no $packageName is published for both amd64 and arm64")
      case Some(best) if DebianVersion.compare(best, current) > 0 => Right(Some(best))
      case Some(_)                                                => Right(None)

  def validateCli(raw: String): Either[String, String] =
    if raw.matches("""\d+\.\d+\.\d+""") then Right(raw)
    else Left(s"zipx: docker-cli pin must be x.y.z, got '$raw'")

  def validateApt(raw: String): Either[String, String] =
    if raw.matches("[A-Za-z0-9.+:~-]+") then Right(raw)
    else Left(s"zipx: apt pin is not a Debian version, got '$raw'")

  def validateSuite(raw: String): Either[String, String] =
    if raw.matches("[a-z]+") then Right(raw)
    else Left(s"zipx: ubuntu suite pin must be a codename, got '$raw'")

  private def candidate(
      pins: Seq[Pin],
      id: String,
      component: String,
      current: String,
  ): Either[String, Option[PinCandidate]] =
    for
      suite <- suiteOf(pins)
      amd64 <- packages(suite, component, "amd64")
      arm64 <- packages(suite, component, "arm64")
      next  <- newest(current, amd64, arm64, id)
    yield next.map(PinCandidate(_))

  private def suiteOf(pins: Seq[Pin]): Either[String, String] =
    one(pins, Feed, SuiteId).flatMap(validateSuite)

  private def one(pins: Seq[Pin], feed: PinFeedName, id: String): Either[String, String] =
    pins.collectFirst { case pin if pin.feed == feed && pin.id == id => pin.current } match
      case Some(version) => Right(version)
      case None          => Left(s"""zipx: zipxAgentEnv needs Pin("$feed", "$id", "<version>")""")

  private def packages(suite: String, component: String, arch: String): Either[String, String] =
    val url = packagesUrl(suite, component, arch)
    gzipGet(url).flatMap { (status, body) =>
      if status == 200 then Right(body)
      else Left(s"zipx: $component $arch lookup HTTP $status")
    }

  /** `Packages.gz` is gzip. [[zipx.core.HttpLookup]] reads a string body and would corrupt it. */
  private def gzipGet(url: String): Either[String, (Int, String)] =
    try
      val client = HttpClient
        .newBuilder()
        .connectTimeout(JDuration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
      val req  = HttpRequest.newBuilder(URI.create(url)).timeout(JDuration.ofSeconds(30)).GET().build()
      val res  = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
      val text =
        if res.statusCode == 200 then
          Using.resource(new GZIPInputStream(new ByteArrayInputStream(res.body()))) { in =>
            new String(in.readAllBytes(), StandardCharsets.UTF_8)
          }
        else ""
      Right((res.statusCode, text))
    catch
      case err: IOException =>
        Left(s"zipx: package index lookup failed (${err.getClass.getSimpleName})")
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("zipx: package index lookup interrupted")
end AgentDaemon

final case class DaemonPins(dockerCli: String, fuseOverlayfs: String, iptables: String, suite: String)
