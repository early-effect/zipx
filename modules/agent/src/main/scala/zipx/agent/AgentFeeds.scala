package zipx.agent

import zipx.core.{HttpLookup, Pin, PinAction, PinCandidate, PinFeed, VersionStrategy}

/** Lookups for the agent pins. Registered while `zipxAgentEnv` is set, or while the catalog already has one of these
  * pins, so `zipxPinUpdate` can rewrite them.
  */
object AgentFeeds:

  val UbuntuTags: String = s"https://hub.docker.com/v2/repositories/library/ubuntu/tags/${UbuntuBase.Tag}"

  def dockerPackages(arch: String): String =
    s"https://download.docker.com/linux/ubuntu/dists/noble/stable/binary-$arch/Packages"

  val SbtReleases: String = "https://api.github.com/repos/sbt/sbt/releases?per_page=100"

  def all: Seq[PinFeed] = Seq(base, docker, sbtRunner)

  def withUser(user: Seq[PinFeed], pins: Seq[Pin], enabled: Boolean): Seq[PinFeed] =
    val wanted = enabled || pins.exists(pin => AgentPins.feedNames.contains(pin.feed))
    if !wanted then user
    else
      val have = user.map(_.name).toSet
      user ++ all.filterNot(feed => have.contains(feed.name))

  val base: PinFeed = PinFeed(
    name = AgentPins.BaseFeed,
    classify = VersionStrategy.exact,
    lookup = pin =>
      if pin.id != AgentPins.UbuntuId then Right(None)
      else
        HttpLookup.get(UbuntuTags, followRedirect = true).flatMap { res =>
          if res.status == 200 then UbuntuBase.candidate(pin.current, res.body).map(_.map(PinCandidate(_)))
          else Left(s"zipx: ubuntu base lookup HTTP ${res.status}")
        }
    ,
    outdated = PinAction.Update,
    advisory = PinAction.Ignore,
  )

  val docker: PinFeed = PinFeed(
    name = AgentPins.DockerFeed,
    classify = VersionStrategy.exact,
    lookup = pin =>
      if pin.id != AgentPins.DockerId then Right(None)
      else
        for
          amd64 <- packages("amd64")
          arm64 <- packages("arm64")
          next  <- DockerCe.candidate(pin.current, amd64, arm64)
        yield next.map(PinCandidate(_))
    ,
    outdated = PinAction.Update,
    advisory = PinAction.Ignore,
  )

  val sbtRunner: PinFeed = PinFeed(
    name = AgentPins.SbtFeed,
    classify = VersionStrategy.npm,
    lookup = pin =>
      if pin.id != AgentPins.SbtId then Right(None)
      else
        HttpLookup.get(SbtReleases, headers = githubHeaders, followRedirect = true).flatMap { res =>
          if res.status == 200 then Right(SbtRunner.candidate(pin.current, res.body).map(PinCandidate(_)))
          else Left(s"zipx: sbt runner lookup HTTP ${res.status}")
        }
    ,
    outdated = PinAction.Update,
    advisory = PinAction.Ignore,
  )

  private def packages(arch: String): Either[String, String] =
    HttpLookup.get(dockerPackages(arch), followRedirect = true).flatMap { res =>
      if res.status == 200 then Right(res.body)
      else Left(s"zipx: docker-ce $arch lookup HTTP ${res.status}")
    }

  private def githubHeaders: Map[String, String] =
    val accept = Map(
      "Accept"               -> "application/vnd.github+json",
      "X-GitHub-Api-Version" -> "2022-11-28",
    )
    sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty).fold(accept)(token => accept + ("Authorization" -> s"Bearer $token"))
end AgentFeeds
