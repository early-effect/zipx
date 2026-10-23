package zipx.agent

import zipx.core.{
  HttpLookup,
  Pin,
  PinAction,
  PinCandidate,
  PinFeed,
  SbtImage,
  SbtImageLayer,
  VersionStrategy,
  WolfiToolchain,
}

/** Lookup for the `sbt-image` pins. The plugin always registers it, so a catalog override of a default still moves.
  */
object SbtImageFeeds:

  val TokenUrl: String =
    "https://cgr.dev/token?service=cgr.dev&scope=repository:chainguard/wolfi-base:pull"

  val ManifestUrl: String = "https://cgr.dev/v2/chainguard/wolfi-base/manifests/latest"

  val NodeIndex: String = "https://nodejs.org/dist/index.json"

  val CoursierReleases: String = "https://api.github.com/repos/coursier/coursier/releases?per_page=100"

  val GhReleases: String = "https://api.github.com/repos/cli/cli/releases?per_page=100"

  private val Token = """"token"\s*:\s*"([^"]+)"""".r

  def withUser(
      user: Seq[PinFeed],
      pins: Seq[Pin],
      enabled: Boolean,
      layers: Seq[SbtImageLayer] = Nil,
  ): Seq[PinFeed] =
    val wanted = enabled || layers.nonEmpty || pins.exists(_.feed == SbtImage.Feed)
    if !wanted then user
    else user.filterNot(_.name == SbtImage.Feed) :+ feed(layers)

  def feed(layers: Seq[SbtImageLayer]): PinFeed = PinFeed(
    name = SbtImage.Feed,
    classify = VersionStrategy.exact,
    lookup = pin =>
      pin.id match
        case SbtImage.BaseId                              => baseCandidate(pin.current).map(_.map(PinCandidate(_)))
        case id if WolfiToolchain.Jdks.exists(_.id == id) =>
          jdkCandidate(pin.current, id).map(_.map(PinCandidate(_)))
        case SbtImage.BashId      => apkCandidate(pin.current, SbtImage.BashId)
        case SbtImage.GitId       => apkCandidate(pin.current, SbtImage.GitId)
        case SbtImage.CurlId      => apkCandidate(pin.current, SbtImage.CurlId)
        case SbtImage.NodeId      => numbered(pin.current, NodeIndex, SbtImage.nodeVersions, "node")
        case SbtImage.CsId        => numbered(pin.current, CoursierReleases, SbtImage.releases, "cs")
        case SbtImage.DockerCliId => dockerCli(pin.current)
        case SbtImage.GhId        => numbered(pin.current, GhReleases, SbtImage.releases, "gh")
        case other                => layerLookup(layers, pin, other)
    ,
    outdated = PinAction.Update,
    advisory = PinAction.Ignore,
  )

  private def layerLookup(layers: Seq[SbtImageLayer], pin: Pin, id: String): Either[String, Option[PinCandidate]] =
    layers.find(_.id == id) match
      case None        => Right(None)
      case Some(layer) =>
        layer.lookup(pin).flatMap {
          case None       => Right(None)
          case Some(cand) =>
            SbtImage.layerCandidate(id, pin.current, cand.version) match
              case Left(err)   => Left(err.message)
              case Right(next) => Right(next.map(version => cand.copy(version = version)))
        }

  private def baseCandidate(current: String): Either[String, Option[String]] =
    for
      tokenRes <- HttpLookup.get(TokenUrl, followRedirect = true)
      token    <-
        if tokenRes.status == 200 then
          Token.findFirstMatchIn(tokenRes.body).map(_.group(1)).toRight("zipx: cgr.dev token response has no token")
        else Left(s"zipx: cgr.dev auth HTTP ${tokenRes.status}")
      manifest <- HttpLookup.get(
        ManifestUrl,
        headers = Map(
          "Authorization" -> s"Bearer $token",
          "Accept"        -> "application/vnd.oci.image.index.v1+json",
        ),
      )
      digest <- manifest
        .header("docker-content-digest")
        .map(_.trim)
        .filter(raw => WolfiToolchain.parseDigest(raw).isDefined)
        .toRight(s"zipx: wolfi-base manifest has no index digest (HTTP ${manifest.status})")
    yield Option.when(digest != current)(digest)

  private def jdkCandidate(current: String, id: String): Either[String, Option[String]] =
    WolfiToolchain.Jdks.find(_.id == id) match
      case None      => Right(None)
      case Some(jdk) => ApkIndex.openJdkCandidate(current, jdk.major)

  private def apkCandidate(current: String, packageName: String): Either[String, Option[PinCandidate]] =
    ApkIndex.candidate(current, packageName).map(_.map(PinCandidate(_)))

  private def numbered(
      current: String,
      url: String,
      read: String => List[String],
      what: String,
  ): Either[String, Option[PinCandidate]] =
    HttpLookup.get(url, headers = githubHeaders, followRedirect = true).flatMap { res =>
      if res.status != 200 then Left(s"zipx: $what lookup HTTP ${res.status}")
      else SbtImage.numberedCandidate(current, read(res.body)).map(_.map(PinCandidate(_)))
    }

  private def dockerCli(current: String): Either[String, Option[PinCandidate]] =
    for
      amd64 <- packages("amd64")
      arm64 <- packages("arm64")
      next  <- DockerCe.newestCli(amd64, arm64)
    yield next.filter(_ != current).map(PinCandidate(_))

  private def packages(arch: String): Either[String, String] =
    HttpLookup.get(AgentFeeds.dockerPackages(arch), followRedirect = true).flatMap { res =>
      if res.status == 200 then Right(res.body)
      else Left(s"zipx: docker-ce-cli $arch lookup HTTP ${res.status}")
    }

  private def githubHeaders: Map[String, String] =
    val accept = Map(
      "Accept"               -> "application/vnd.github+json",
      "X-GitHub-Api-Version" -> "2022-11-28",
    )
    sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty).fold(accept)(token => accept + ("Authorization" -> s"Bearer $token"))
end SbtImageFeeds
