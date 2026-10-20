package zipx.agent

/** Docker CE apt versions. The agent image stays on 28 while the containerd image store (Docker 29's default) is
  * unproven under nested `fuse-overlayfs`. A version is eligible only when both amd64 and arm64 publish it.
  */
object DockerCe:

  val HeldMajor: Int = 28

  final case class Apt(epoch: Int, major: Int, minor: Int, patch: Int, rest: String, raw: String)

  given Ordering[Apt] = Ordering.by(apt => (apt.epoch, apt.major, apt.minor, apt.patch, apt.rest))

  def parse(raw: String): Option[Apt] =
    raw.split(":", 2) match
      case Array(epochRaw, rest) =>
        val upstream = rest.takeWhile(c => c.isDigit || c == '.')
        val parts    = upstream.split("\\.", -1)
        if parts.length != 3 then None
        else
          for
            epoch <- epochRaw.toIntOption
            major <- parts(0).toIntOption
            minor <- parts(1).toIntOption
            patch <- parts(2).toIntOption
          yield Apt(epoch, major, minor, patch, rest.drop(upstream.length), raw)
      case _ => None

  /** Versions of `packageName` in a Debian `Packages` file. */
  def packageVersions(body: String, packageName: String): List[String] =
    body
      .split("\n", -1)
      .foldLeft((Option.empty[String], List.empty[String])) { case ((pkg, found), line) =>
        if line.startsWith("Package: ") then (Some(line.stripPrefix("Package: ")), found)
        else if pkg.contains(packageName) && line.startsWith("Version: ") then
          (pkg, found :+ line.stripPrefix("Version: "))
        else (pkg, found)
      }
      ._2

  /** Newest 28.x `docker-ce` present in both architectures. 29 and newer are dropped. */
  def newest(amd64: String, arm64: String): Either[String, Option[String]] =
    val both = packageVersions(amd64, "docker-ce").toSet.intersect(packageVersions(arm64, "docker-ce").toSet)
    val held = both.flatMap(parse).filter(_.major == HeldMajor)
    if held.isEmpty then Left(s"zipx: no docker-ce ${HeldMajor}.x is published for both amd64 and arm64")
    else Right(held.maxOption.map(_.raw))

  def candidate(current: String, amd64: String, arm64: String): Either[String, Option[String]] =
    newest(amd64, arm64).map(_.filter(_ != current))

  /** Newest 28.x `docker-ce-cli` present on both architectures, as `major.minor.patch` for the static client. */
  def newestCli(amd64: String, arm64: String): Either[String, Option[String]] =
    val both = packageVersions(amd64, "docker-ce-cli").toSet.intersect(packageVersions(arm64, "docker-ce-cli").toSet)
    val held = both.flatMap(parse).filter(_.major == HeldMajor)
    if held.isEmpty then Left(s"zipx: no docker-ce-cli ${HeldMajor}.x is published for both amd64 and arm64")
    else Right(held.maxOption.map(apt => s"${apt.major}.${apt.minor}.${apt.patch}"))
end DockerCe
