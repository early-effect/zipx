package zipx.agent

import zipx.core.{HttpLookup, Pin, PinAction, PinCandidate, PinFeed, SbtImage, SbtImageLayer, VersionStrategy}

/** Lookup for the `sbt-image` pins. Registered while `zipxSbtImage` is on, or while the catalog already has one of
  * these pins, so `zipxPinUpdate` can rewrite them.
  */
object SbtImageFeeds:

  val Tags: String = "https://hub.docker.com/v2/repositories/sbtscala/scala-sbt/tags?page_size=100&name="

  val TokenUrl: String =
    "https://auth.docker.io/token?service=registry.docker.io&scope=repository:sbtscala/scala-sbt:pull"

  val NodeIndex: String = "https://nodejs.org/dist/index.json"

  val CoursierReleases: String = "https://api.github.com/repos/coursier/coursier/releases?per_page=100"

  val GhReleases: String = "https://api.github.com/repos/cli/cli/releases?per_page=100"

  private val TagName = """"name"\s*:\s*"(eclipse-temurin-[^"]+)"""".r
  private val Next    = """"next"\s*:\s*"([^"]+)"""".r
  private val Token   = """"token"\s*:\s*"([^"]+)"""".r

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
        case SbtImage.ImageId     => imageCandidate(pin.current)
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

  private def imageCandidate(current: String): Either[String, Option[PinCandidate]] =
    SbtImage.parse(current).flatMap { ref =>
      for
        names  <- tagNames(ref.major)
        tag    <- SbtImage.select(ref.major, names)
        digest <- manifestDigest(tag)
        next   <- SbtImage.candidate(current, names, digest)
      yield next.map(PinCandidate(_))
    }

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

  private def tagNames(major: Int): Either[String, List[String]] =
    def go(url: String, left: Int, seen: Set[String], acc: List[String]): Either[String, List[String]] =
      if left == 0 || seen.contains(url) then Right(acc)
      else
        HttpLookup.get(url, followRedirect = true).flatMap { res =>
          if res.status != 200 then Left(s"zipx: sbtscala/scala-sbt tags HTTP ${res.status}")
          else
            val names = TagName.findAllMatchIn(res.body).map(_.group(1)).toList
            Next.findFirstMatchIn(res.body) match
              case None       => Right(acc ++ names)
              case Some(more) => go(more.group(1), left - 1, seen + url, acc ++ names)
        }
    go(s"$Tags" + s"eclipse-temurin-$major", 10, Set.empty, Nil)

  private def manifestDigest(tag: String): Either[String, String] =
    for
      tokenRes <- HttpLookup.get(TokenUrl, followRedirect = true)
      token    <-
        if tokenRes.status == 200 then
          Token.findFirstMatchIn(tokenRes.body).map(_.group(1)).toRight("zipx: docker hub token response has no token")
        else Left(s"zipx: docker hub auth HTTP ${tokenRes.status}")
      manifest <- HttpLookup.get(
        s"https://registry-1.docker.io/v2/sbtscala/scala-sbt/manifests/$tag",
        headers = Map(
          "Authorization" -> s"Bearer $token",
          "Accept"        ->
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json",
        ),
      )
      digest <- manifest
        .header("Docker-Content-Digest")
        .map(_.trim)
        .filter(_.matches("sha256:[a-f0-9]{64}"))
        .toRight(s"zipx: $tag manifest has no index digest (HTTP ${manifest.status})")
    yield digest

  private def githubHeaders: Map[String, String] =
    val accept = Map(
      "Accept"               -> "application/vnd.github+json",
      "X-GitHub-Api-Version" -> "2022-11-28",
    )
    sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty).fold(accept)(token => accept + ("Authorization" -> s"Bearer $token"))
end SbtImageFeeds
