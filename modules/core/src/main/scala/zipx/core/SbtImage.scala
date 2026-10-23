package zipx.core

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** The toolchain image every sbt job runs in: public Wolfi, a pinned OpenJDK, and the build's sbt launcher.
  *
  * The published tag is the Dockerfile hash. Caches and tokens stay out of the image. Jobs restore `actions/cache` into
  * [[CacheHome]], which is the image user's home, and set `user.home` there. `~` is not that directory once Actions
  * overrides `HOME`.
  */
object SbtImage:

  val Feed: PinFeedName = PinFeedName("sbt-image")

  val BaseId: String      = "wolfi-base"
  val ImageId: String     = BaseId
  val BashId: String      = "bash"
  val GitId: String       = "git"
  val CurlId: String      = "curl"
  val NodeId: String      = "node"
  val CsId: String        = "cs"
  val DockerCliId: String = "docker-cli"
  val GhId: String        = "gh"

  val DockerfileRel: String     = ".github/zipx-env/Dockerfile"
  val CacheHome: String         = "/home/sbtuser"
  val SocketOptions: String     = "-v /var/run/docker.sock:/var/run/docker.sock"
  val DefaultSbtVersion: String = "2.1.0"

  /** Static docker client baked when a job publishes images and the catalog has no `docker-cli` pin. */
  val DefaultDockerCli: String = "28.5.2"

  private val SbtVersion = """[0-9]+\.[0-9]+\.[0-9]+(?:[.-][A-Za-z0-9]+)*""".r
  private val Triple     = """(\d+)\.(\d+)\.(\d+)""".r

  def builtinIds: Set[String] =
    Set(BaseId, BashId, GitId, CurlId, NodeId, CsId, DockerCliId, GhId) ++ WolfiToolchain.Jdks.map(_.id)

  final case class Release(major: Int, minor: Int, patch: Int):
    def render: String = s"$major.$minor.$patch"

  private given scala.math.Ordering[Release] =
    scala.math.Ordering.by(rel => (rel.major, rel.minor, rel.patch))

  def javaMajor(raw: String): Either[String, Int] =
    val digits = raw.dropWhile(!_.isDigit).takeWhile(_.isDigit)
    digits.toIntOption.filter(_ > 0).toRight(s"zipx: zipxJavaVersion '$raw' has no JDK major")

  /** A Linux runner label that cannot run a container job. Other labels are left alone. */
  def hostedRunner(label: String): Boolean =
    val name = label.toLowerCase
    name.startsWith("macos") || name.startsWith("windows")

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
      case None =>
        Left(s"zipx: pin '$current' is not a concrete x.y.z")
      case Some(cur) =>
        available.flatMap(parseRelease).filter(_.major == cur.major).maxOption match
          case None =>
            Left(s"zipx: no ${cur.major}.x.y release is published")
          case Some(best) =>
            Right(Option.when(best.render != current)(best.render))

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
      sbtVersion: String,
      pins: Seq[Pin],
      nodeVersions: List[NodeVersion],
      coursier: Boolean,
      docker: Boolean,
      layers: Seq[SbtImageLayer] = Nil,
  ): Either[SbtImageError, SbtImagePlan] =
    for
      _      <- refuseRetired(pins)
      major  <- javaMajor(javaVersion).left.map(_ => SbtImageError.BadJavaVersion(javaVersion))
      _      <- Either.cond(major >= JdkFloor.MinimumFeature, (), SbtImageError.JdkBelowMinimum(major))
      jdk    <- WolfiToolchain.jdk(major).toRight(SbtImageError.JdkUnsupported(major))
      digest <- baseDigest(pins)
      jdkVer <- jdkPin(pins, jdk)
      bash   <- apkPin(pins, BashId, WolfiToolchain.Bash)
      git    <- apkPin(pins, GitId, WolfiToolchain.Git)
      curl   <- apkPin(pins, CurlId, WolfiToolchain.Curl)
      sbt    <- checkSbt(sbtVersion)
      node   <- oneNode(nodeVersions.map(version => version: String), pinVersion(pins, NodeId))
      cs     <- toolPin(coursier, pinVersion(pins, CsId), CsId, "version updates")
      cli    <- dockerPin(docker, pinVersion(pins, DockerCliId))
      gh     <- optionalTool(pinVersion(pins, GhId), GhId)
      baked  <- SbtImageLayer.assemble(pins, layers)
    yield planOf(digest, jdk, jdkVer, bash, git, curl, sbt, node, cs, cli, gh, baked)

  /** zipx owns the `sbt-image` feed. A caller-supplied feed of that name would drop layer lookup. */
  def shadowedFeed(feeds: Seq[PinFeed]): Option[SbtImageError] =
    Option.when(feeds.exists(_.name == Feed))(SbtImageError.FeedShadowed)

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

  /** The push affected script calls `gh`. The image does not have that client unless the pin bakes it. */
  def missingPushClient(onPush: Boolean, pins: Seq[Pin]): Option[SbtImageError] =
    val pinned = pins.exists(pin => pin.feed == Feed && pin.id == GhId)
    Option.when(onPush && !pinned)(SbtImageError.MissingToolPin(GhId, "testAffected(onPush = true)"))

  private def planOf(
      digest: String,
      jdk: WolfiToolchain.Jdk,
      jdkVersion: String,
      bash: String,
      git: String,
      curl: String,
      sbtVersion: String,
      node: Option[String],
      cs: Option[String],
      cli: Option[String],
      gh: Option[String],
      baked: SbtImageLayer.Rendered,
  ): SbtImagePlan =
    val reference = WolfiToolchain.reference(digest)
    val body      = renderDockerfile(
      digest,
      jdk,
      jdkVersion,
      bash,
      git,
      curl,
      sbtVersion,
      node,
      cs,
      cli,
      gh.toList.map(ghInstall) ++ baked.runs,
      baked.env,
    )
    SbtImagePlan(
      upstream = reference,
      derivedTag = contentTag(body),
      dockerfile = body,
      dockerSocket = cli.isDefined,
      cacheHome = CacheHome,
      layerEnv = baked.env,
    )
  end planOf

  private def renderDockerfile(
      digest: String,
      jdk: WolfiToolchain.Jdk,
      jdkVersion: String,
      bash: String,
      git: String,
      curl: String,
      sbtVersion: String,
      node: Option[String],
      cs: Option[String],
      dockerCli: Option[String],
      layerRuns: List[String],
      layerEnv: Map[String, String],
  ): String =
    val packages = List(
      s"${jdk.runtime}=$jdkVersion",
      s"${jdk.defaultJdk}=$jdkVersion",
      s"bash=$bash",
      s"git=$git",
      s"curl=$curl",
    )
    val installs =
      node.toList.map(nodeInstall) ++ cs.toList.map(csInstall) ++ dockerCli.toList.map(dockerInstall) ++ layerRuns
    val envLines = layerEnv.toList.sortBy(_._1).map((key, value) => SbtImageLayer.envLine(key, value))
    (List(
      ZipxCatalog.GeneratedHeader,
      s"FROM ${WolfiToolchain.reference(digest)}",
      "RUN apk add --no-cache " + packages.mkString(" "),
      SbtImageLayer.envLine("JAVA_HOME", WolfiToolchain.JavaHome),
      sbtInstall(sbtVersion),
    ) ++ installs ++ envLines ++ List(
      "RUN adduser -D -h /home/sbtuser -s /bin/bash sbtuser",
      "USER sbtuser",
      s"WORKDIR $CacheHome",
    )).mkString("\n") + "\n"
  end renderDockerfile

  private def refuseRetired(pins: Seq[Pin]): Either[SbtImageError, Unit] =
    pins.find(pin => pin.feed == Feed && retired(pin)) match
      case Some(pin) => Left(SbtImageError.RetiredTemurinPin(s"${pin.id}=${pin.current}"))
      case None      => Right(())

  private def retired(pin: Pin): Boolean =
    pin.id == "sbtscala/scala-sbt" || pin.current.contains("eclipse-temurin") || pin.current.contains(
      "sbtscala/scala-sbt"
    )

  private def baseDigest(pins: Seq[Pin]): Either[SbtImageError, String] =
    pinVersion(pins, BaseId) match
      case None =>
        Right(WolfiToolchain.BaseDigest)
      case Some(raw) =>
        WolfiToolchain.parseDigest(raw).toRight(SbtImageError.BadBasePin(raw))

  private def jdkPin(pins: Seq[Pin], jdk: WolfiToolchain.Jdk): Either[SbtImageError, String] =
    val raw = pinVersion(pins, jdk.id).getOrElse(jdk.version)
    for
      apk      <- WolfiToolchain.parseApk(raw).toRight(SbtImageError.BadApkPin(jdk.id, raw))
      upstream <- WolfiToolchain.jdkUpstream(raw).toRight(SbtImageError.BadApkPin(jdk.id, raw))
      release  <- JdkFloor.parse(upstream).left.map(_ => SbtImageError.BadApkPin(jdk.id, raw))
      feature  <- release.feature.toRight(SbtImageError.BadApkPin(jdk.id, raw))
      _        <- Either.cond(feature == jdk.major, (), SbtImageError.JdkMismatch(feature, jdk.major.toString))
      _        <- JdkFloor.assess(JdkFloor.Vendor.OpenJdk, release) match
        case JdkFloor.Verdict.Safe =>
          Right(())
        case JdkFloor.Verdict.BelowMinimum(got) =>
          Left(SbtImageError.JdkBelowMinimum(got))
        case _ =>
          val required = JdkFloor.fixedOpenJdk(jdk.major).getOrElse(jdk.version)
          Left(SbtImageError.JdkVulnerable(raw, required))
    yield apk.raw
    end for
  end jdkPin

  private def apkPin(pins: Seq[Pin], id: String, default: String): Either[SbtImageError, String] =
    val raw = pinVersion(pins, id).getOrElse(default)
    WolfiToolchain.parseApk(raw).map(_.raw).toRight(SbtImageError.BadApkPin(id, raw))

  private def checkSbt(raw: String): Either[SbtImageError, String] =
    if SbtVersion.matches(raw) then Right(raw) else Left(SbtImageError.BadSbtVersion(raw))

  private def pinVersion(pins: Seq[Pin], id: String): Option[String] =
    pins.find(pin => pin.feed == Feed && pin.id == id).map(_.current)

  private def oneNode(asked: List[String], pinned: Option[String]): Either[SbtImageError, Option[String]] =
    asked.distinct match
      case Nil =>
        Right(None)
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

  private def dockerPin(wanted: Boolean, pinned: Option[String]): Either[SbtImageError, Option[String]] =
    if !wanted then Right(None)
    else
      val raw = pinned.getOrElse(DefaultDockerCli)
      if isConcrete(raw) then Right(Some(raw))
      else Left(SbtImageError.ToolPinNotConcrete(DockerCliId, raw))

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

  private def sbtInstall(version: String): String =
    s"""RUN set -eux; \\
       |    curl -fsSL -o /tmp/sbt.tgz "https://github.com/sbt/sbt/releases/download/v$version/sbt-$version.tgz"; \\
       |    tar -xzf /tmp/sbt.tgz -C /usr/local; \\
       |    ln -sfn /usr/local/sbt/bin/sbt /usr/local/bin/sbt; \\
       |    rm /tmp/sbt.tgz""".stripMargin

  private def nodeInstall(version: String): String =
    s"""RUN set -eux; \\
       |    case "$$(uname -m)" in \\
       |      x86_64) node_arch=x64 ;; \\
       |      aarch64) node_arch=arm64 ;; \\
       |      *) echo "zipx: unsupported architecture $$(uname -m)" >&2; exit 1 ;; \\
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

  private def ghInstall(version: String): String =
    s"""RUN set -eux; \\
       |    case "$$(uname -m)" in \\
       |      x86_64) gh_arch=amd64 ;; \\
       |      aarch64) gh_arch=arm64 ;; \\
       |      *) echo "zipx: unsupported architecture $$(uname -m)" >&2; exit 1 ;; \\
       |    esac; \\
       |    curl -fsSL -o /tmp/gh.tgz "https://github.com/cli/cli/releases/download/v$version/gh_${version}_linux_$${gh_arch}.tar.gz"; \\
       |    tar -xzf /tmp/gh.tgz -C /tmp; \\
       |    mv "/tmp/gh_${version}_linux_$${gh_arch}/bin/gh" /usr/local/bin/gh; \\
       |    rm -rf /tmp/gh.tgz "/tmp/gh_${version}_linux_$${gh_arch}"""".stripMargin

end SbtImage

/** What sbt jobs run in. The derived tag is the Dockerfile hash, not a registry digest: the digest does not exist until
  * the push.
  */
final case class SbtImagePlan(
    upstream: String,
    derivedTag: String,
    dockerfile: String,
    dockerSocket: Boolean,
    cacheHome: String,
    layerEnv: Map[String, String] = Map.empty,
)
