package zipx.core

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** The pinned `sbtscala/scala-sbt` light image, and the Dockerfile zipx bakes when the build needs a tool that image
  * does not ship.
  *
  * The catalog version is `eclipse-temurin-<jdk>_<build>_2.x@sha256:<manifest>`. [[VersionStrategy.exact]] treats any
  * other string as a bump, so a rebuilt tag is an update. A `sha256` field alone is not: [[PinEngine]] ignores a
  * checksum change when the version string is equal.
  *
  * Caches and tokens stay out of the image. Jobs restore `actions/cache` into [[CacheHome]], which is the image user's
  * home, and set `user.home` there. `~` is not that directory once Actions overrides `HOME`.
  */
object SbtImage:

  val Feed: PinFeedName = PinFeedName("sbt-image")

  val ImageId: String     = "sbtscala/scala-sbt"
  val NodeId: String      = "node"
  val CsId: String        = "cs"
  val DockerCliId: String = "docker-cli"
  val GhId: String        = "gh"

  val DockerfileRel: String = ".github/zipx-env/Dockerfile"
  val CacheHome: String     = "/home/sbtuser"
  val SocketOptions: String = "-v /var/run/docker.sock:/var/run/docker.sock"

  private val LightRaw = """eclipse-temurin-(\d+)\.(\d+)\.(\d+)_(\d+)_2\.x"""

  private val Pinned = s"^($LightRaw)@sha256:([a-f0-9]{64})$$".r
  private val Light  = s"^$LightRaw$$".r
  private val Triple = """(\d+)\.(\d+)\.(\d+)""".r

  final case class Ref(tag: String, major: Int, digest: String):
    def version: String   = s"$tag@sha256:$digest"
    def reference: String = s"$ImageId:$tag@sha256:$digest"

  final case class LightTag(major: Int, minor: Int, patch: Int, build: Int, name: String)

  private given scala.math.Ordering[LightTag] =
    scala.math.Ordering.by(tag => (tag.major, tag.minor, tag.patch, tag.build))

  final case class Release(major: Int, minor: Int, patch: Int):
    def render: String = s"$major.$minor.$patch"

  private given scala.math.Ordering[Release] =
    scala.math.Ordering.by(rel => (rel.major, rel.minor, rel.patch))

  def javaMajor(raw: String): Either[String, Int] =
    val digits = raw.dropWhile(!_.isDigit).takeWhile(_.isDigit)
    digits.toIntOption.filter(_ > 0).toRight(s"zipx: zipxJavaVersion '$raw' has no JDK major")

  def parse(raw: String): Either[String, Ref] = raw match
    case Pinned(tag, major, _, _, _, digest) =>
      major.toIntOption
        .map(parsed => Ref(tag, parsed, digest))
        .toRight(s"zipx: sbt image pin '$raw' has no JDK major")
    case _ =>
      Left(
        s"zipx: sbt image pin '$raw' must be eclipse-temurin-<jdk>_<build>_2.x@sha256:<digest>"
      )

  def lightTags(names: List[String]): List[LightTag] =
    names.flatMap { name =>
      name match
        case Light(major, minor, patch, build) =>
          for
            maj <- major.toIntOption
            min <- minor.toIntOption
            pat <- patch.toIntOption
            bld <- build.toIntOption
          yield LightTag(maj, min, pat, bld, name)
        case _ => None
    }

  /** Newest light tag for `major`. Fat tags and the floating `eclipse-temurin-<major>_2.x` name are ignored. */
  def select(major: Int, names: List[String]): Either[String, String] =
    lightTags(names)
      .filter(_.major == major)
      .maxOption
      .map(_.name)
      .toRight(
        s"zipx: no eclipse-temurin-$major.*_*_2.x tag on $ImageId"
      )

  def candidate(current: String, names: List[String], digest: String): Either[String, Option[String]] =
    for
      ref <- parse(current)
      tag <- select(ref.major, names)
    yield
      val hex  = digest.stripPrefix("sha256:")
      val next = s"$tag@sha256:$hex"
      Option.when(next != current)(next)

  def isConcrete(raw: String): Boolean = parseRelease(raw).isDefined

  def parseRelease(raw: String): Option[Release] = raw match
    case Triple(major, minor, patch) =>
      for
        maj <- major.toIntOption
        min <- minor.toIntOption
        pat <- patch.toIntOption
      yield Release(maj, min, pat)
    case _ => None

  def releases(body: String): List[String] =
    """"tag_name"\s*:\s*"v(\d+\.\d+\.\d+)"""".r.findAllMatchIn(body).map(_.group(1)).toList.distinct

  def nodeVersions(body: String): List[String] =
    """"version"\s*:\s*"v(\d+\.\d+\.\d+)"""".r.findAllMatchIn(body).map(_.group(1)).toList.distinct

  /** Newest x.y.z on the major of `current`. */
  def numberedCandidate(current: String, available: List[String]): Either[String, Option[String]] =
    parseRelease(current) match
      case None      => Left(s"zipx: pin '$current' is not a concrete x.y.z")
      case Some(cur) =>
        available.flatMap(parseRelease).filter(_.major == cur.major).maxOption match
          case None       => Left(s"zipx: no ${cur.major}.x.y release is published")
          case Some(best) => Right(Option.when(best.render != current)(best.render))

  def githubRepository(remote: String): Option[String] =
    val https = """https://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$""".r
    val ssh   = """git@github\.com:([^/]+)/([^/]+?)(?:\.git)?$""".r
    remote.trim match
      case https(owner, repo) => Some(s"$owner/$repo")
      case ssh(owner, repo)   => Some(s"$owner/$repo")
      case _                  => None

  def ghcrImage(repository: String, tag: String): String =
    s"ghcr.io/${repository.toLowerCase}/zipx-env:$tag"

  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  def contentTag(body: String): String = "df-" + sha256(body)

  /** One `ENV` line. The agent module is outside `zipx.core`, so this is the public wrapper. */
  def envLine(key: String, value: String): String = SbtImageLayer.envLine(key, value)

  /** Job env so the JVM and the cache step share [[CacheHome]]. Existing keys win; a flag is appended when the value
    * does not already set `user.home`.
    */
  def homeEnv(env: Map[String, String], home: String = CacheHome): Map[String, String] =
    val flag     = s"-Duser.home=$home"
    val withHome = if env.contains("HOME") then env else env + ("HOME" -> home)
    def add(key: String, current: Map[String, String]): Map[String, String] =
      current.get(key) match
        case None                                       => current + (key -> flag)
        case Some(value) if value.contains("user.home") => current
        case Some(value)                                => current + (key -> s"$value $flag")
    val opts = add("SBT_OPTS", add("JAVA_OPTS", withHome))
    if opts.contains("GIT_CONFIG_COUNT") then opts
    else
      opts ++ Map(
        "GIT_CONFIG_COUNT"   -> "1",
        "GIT_CONFIG_KEY_0"   -> "safe.directory",
        "GIT_CONFIG_VALUE_0" -> "*",
      )
  end homeEnv

  def envImageScript(tag: String): String =
    s"""repo=$$(printf '%s' "$$GITHUB_REPOSITORY" | tr '[:upper:]' '[:lower:]')
       |image="ghcr.io/$${repo}/zipx-env:$tag"
       |echo "image=$${image}" >> "$$GITHUB_OUTPUT"
       |if docker manifest inspect "$$image" >/dev/null 2>&1; then
       |  echo "zipx-env $$image is already published"
       |  exit 0
       |fi
       |printf '%s' "$$GITHUB_TOKEN" | docker login ghcr.io -u "$$GITHUB_ACTOR" --password-stdin
       |docker build -f $DockerfileRel -t "$$image" .
       |docker push "$$image"
       |""".stripMargin

  def resolve(
      javaVersion: JdkVersion,
      pins: Seq[Pin],
      nodeVersions: List[NodeVersion],
      coursier: Boolean,
      docker: Boolean,
      layers: Seq[SbtImageLayer] = Nil,
  ): Either[SbtImageError, SbtImagePlan] =
    for
      major <- javaMajor(javaVersion).left.map(_ => SbtImageError.BadJavaVersion(javaVersion))
      raw   <- imagePin(pins)
      ref   <- parse(raw).left.map(_ => SbtImageError.BadImagePin(raw))
      _     <- Either.cond(ref.major == major, (), SbtImageError.JdkMismatch(ref.major, javaVersion))
      node  <- oneNode(nodeVersions.map(version => version: String), pinVersion(pins, NodeId))
      cs    <- toolPin(coursier, pinVersion(pins, CsId), CsId, "version updates")
      cli   <- toolPin(docker, pinVersion(pins, DockerCliId), DockerCliId, "image publishes")
      gh    <- optionalTool(pinVersion(pins, GhId), GhId)
      baked <- SbtImageLayer.assemble(pins, layers)
    yield planOf(ref, node, cs, cli, gh, baked)

  /** Layers are the derived image. An empty list while the setting is off is the default. */
  def layersRequireImage(enabled: Boolean, layers: Seq[SbtImageLayer]): Option[SbtImageError] =
    Option.when(!enabled && layers.nonEmpty)(SbtImageError.LayersWhileImageOff)

  /** zipx owns the `sbt-image` feed. A caller-supplied feed of that name would drop layer lookup. */
  def shadowedFeed(feeds: Seq[PinFeed], pins: Seq[Pin], enabled: Boolean): Option[SbtImageError] =
    val active = enabled || pins.exists(_.feed == Feed)
    Option.when(active && feeds.exists(_.name == Feed))(SbtImageError.FeedShadowed)

  /** Same-major concrete `x.y.z`, or a refusal. `None` means the candidate is not newer. */
  def layerCandidate(id: String, current: String, proposed: String): Either[SbtImageError, Option[String]] =
    (parseRelease(current), parseRelease(proposed)) match
      case (None, _) =>
        Left(SbtImageError.LayerPinNotConcrete(id, current))
      case (_, None) =>
        Left(SbtImageError.BadCandidate(id, proposed))
      case (Some(cur), Some(next)) if next.major != cur.major =>
        Left(SbtImageError.CandidateMajor(id, proposed, cur.major))
      case _ =>
        numberedCandidate(current, List(current, proposed)) match
          case Right(next) => Right(next)
          case Left(_)     => Left(SbtImageError.BadCandidate(id, proposed))

  /** Layer env fills keys the job does not already set. A different value is a generate error. */
  def overlayEnv(job: Map[String, String], layer: Map[String, String]): Either[SbtImageError, Map[String, String]] =
    layer.foldLeft[Either[SbtImageError, Map[String, String]]](Right(job)) { case (acc, (key, value)) =>
      acc.flatMap { env =>
        env.get(key) match
          case Some(existing) if existing != value => Left(SbtImageError.EnvClash(key, existing, value))
          case Some(_)                             => Right(env)
          case None                                => Right(env + (key -> value))
      }
    }

  private def planOf(
      ref: Ref,
      node: Option[String],
      cs: Option[String],
      cli: Option[String],
      gh: Option[String],
      baked: SbtImageLayer.Rendered,
  ): SbtImagePlan =
    val body = renderDockerfile(ref.reference, node, cs, cli, gh.toList.map(ghInstall) ++ baked.runs, baked.env)
    SbtImagePlan(
      upstream = ref.reference,
      derivedTag = body.map(contentTag),
      dockerfile = body,
      dockerSocket = cli.isDefined,
      cacheHome = CacheHome,
      layerEnv = baked.env,
    )
  end planOf

  private def imagePin(pins: Seq[Pin]): Either[SbtImageError, String] =
    pinVersion(pins, ImageId).toRight(SbtImageError.MissingImagePin)

  private def pinVersion(pins: Seq[Pin], id: String): Option[String] =
    pins.find(pin => pin.feed == Feed && pin.id == id).map(_.current)

  private def oneNode(asked: List[String], pinned: Option[String]): Either[SbtImageError, Option[String]] =
    asked.distinct match
      case Nil        => Right(None)
      case one :: Nil =>
        if isConcrete(one) then
          pinned match
            case Some(pin) if pin != one => Left(SbtImageError.NodeMismatch(pin, one))
            case _                       => Right(Some(one))
        else
          pinned match
            case Some(pin) if isConcrete(pin) => Right(Some(pin))
            case Some(pin)                    => Left(SbtImageError.NodePinNotConcrete(pin))
            case None                         => Left(SbtImageError.NodeAliasUnpinned(one))
      case many =>
        Left(SbtImageError.ManyNodeVersions(many))

  private def toolPin(
      wanted: Boolean,
      pinned: Option[String],
      id: String,
      why: String,
  ): Either[SbtImageError, Option[String]] =
    if !wanted then Right(None)
    else
      pinned match
        case Some(pin) if isConcrete(pin) => Right(Some(pin))
        case Some(pin)                    => Left(SbtImageError.ToolPinNotConcrete(id, pin))
        case None                         => Left(SbtImageError.MissingToolPin(id, why))

  /** A present pin is baked. Absence is not an error: [[missingPushClient]] requires the pin when a job calls `gh`. */
  private def optionalTool(pinned: Option[String], id: String): Either[SbtImageError, Option[String]] =
    pinned match
      case None                         => Right(None)
      case Some(pin) if isConcrete(pin) => Right(Some(pin))
      case Some(pin)                    => Left(SbtImageError.ToolPinNotConcrete(id, pin))

  /** The push affected script calls `gh`. The runner image has that client. This image does not, unless the pin bakes
    * it. Absence is fine when that push branch is off.
    */
  def missingPushClient(onPush: Boolean, pins: Seq[Pin]): Option[SbtImageError] =
    val pinned = pins.exists(pin => pin.feed == Feed && pin.id == GhId)
    Option.when(onPush && !pinned)(SbtImageError.MissingToolPin(GhId, "testAffected(onPush = true)"))

  /** None when the upstream image already has every tool. The hash of this text is the published tag. */
  def renderDockerfile(
      base: String,
      node: Option[String],
      cs: Option[String],
      dockerCli: Option[String],
      layerRuns: List[String] = Nil,
      layerEnv: Map[String, String] = Map.empty,
  ): Option[String] =
    if node.isEmpty && cs.isEmpty && dockerCli.isEmpty && layerRuns.isEmpty && layerEnv.isEmpty then None
    else
      val installs =
        node.toList.map(nodeInstall) ++ cs.toList.map(csInstall) ++ dockerCli.toList.map(dockerInstall) ++ layerRuns
      val envLines = layerEnv.toList.sortBy(_._1).map((key, value) => SbtImageLayer.envLine(key, value))
      Some(
        (List(ZipxCatalog.GeneratedHeader, s"FROM $base", "USER root") ++ installs ++ envLines ++ List("WORKDIR /root"))
          .mkString("\n") + "\n"
      )

  private def nodeInstall(version: String): String =
    s"""RUN set -eux; \\
       |    arch="$$(dpkg --print-architecture)"; \\
       |    case "$$arch" in \\
       |      amd64) node_arch=x64 ;; \\
       |      arm64) node_arch=arm64 ;; \\
       |      *) echo "zipx: unsupported architecture $$arch" >&2; exit 1 ;; \\
       |    esac; \\
       |    curl -fsSL -o /tmp/node.tar.gz "https://nodejs.org/dist/v$version/node-v$version-linux-$${node_arch}.tar.gz"; \\
       |    tar -xzf /tmp/node.tar.gz -C /usr/local --strip-components=1; \\
       |    rm /tmp/node.tar.gz""".stripMargin

  private def csInstall(version: String): String =
    s"""RUN set -eux; \\
       |    case "$$(uname -m)" in \\
       |      x86_64) cs_arch=x86_64-pc-linux ;; \\
       |      aarch64) cs_arch=aarch64-pc-linux ;; \\
       |      *) echo "zipx: unsupported architecture $$(uname -m)" >&2; exit 1 ;; \\
       |    esac; \\
       |    curl -fL "https://github.com/coursier/coursier/releases/download/v$version/cs-$${cs_arch}.gz" | gzip -d > /usr/local/bin/cs; \\
       |    chmod 0755 /usr/local/bin/cs""".stripMargin

  private def dockerInstall(version: String): String =
    s"""RUN set -eux; \\
       |    case "$$(uname -m)" in \\
       |      x86_64) docker_arch=x86_64 ;; \\
       |      aarch64) docker_arch=aarch64 ;; \\
       |      *) echo "zipx: unsupported architecture $$(uname -m)" >&2; exit 1 ;; \\
       |    esac; \\
       |    curl -fsSL -o /tmp/docker.tgz "https://download.docker.com/linux/static/stable/$${docker_arch}/docker-$version.tgz"; \\
       |    tar -xzf /tmp/docker.tgz -C /tmp; \\
       |    mv /tmp/docker/docker /usr/local/bin/docker; \\
       |    rm -rf /tmp/docker /tmp/docker.tgz""".stripMargin

  /** Official `cli/cli` tarball. Asset names use dpkg's `amd64` and `arm64`, not `uname -m`. */
  private def ghInstall(version: String): String =
    s"""RUN set -eux; \\
       |    arch="$$(dpkg --print-architecture)"; \\
       |    case "$$arch" in \\
       |      amd64|arm64) gh_arch="$$arch" ;; \\
       |      *) echo "zipx: unsupported architecture $$arch" >&2; exit 1 ;; \\
       |    esac; \\
       |    curl -fsSL -o /tmp/gh.tgz "https://github.com/cli/cli/releases/download/v$version/gh_${version}_linux_$${gh_arch}.tar.gz"; \\
       |    tar -xzf /tmp/gh.tgz -C /tmp; \\
       |    mv "/tmp/gh_${version}_linux_$${gh_arch}/bin/gh" /usr/local/bin/gh; \\
       |    rm -rf /tmp/gh.tgz "/tmp/gh_${version}_linux_$${gh_arch}"""".stripMargin

end SbtImage

/** What jobs run in once `zipxSbtImage` is on. The derived tag is the Dockerfile hash, not a registry digest: the
  * digest does not exist until the push.
  */
final case class SbtImagePlan(
    upstream: String,
    derivedTag: Option[String],
    dockerfile: Option[String],
    dockerSocket: Boolean,
    cacheHome: String,
    layerEnv: Map[String, String] = Map.empty,
)
