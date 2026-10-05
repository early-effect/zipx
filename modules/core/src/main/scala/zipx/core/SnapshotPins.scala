package zipx.core

import zipx.shell.{Exec, Script, Word}
import zipx.workflow.Step

/** While any snapshot-resolved row is pinned, the build resolves the publish registry's snapshot repository plus extras
  * (Central snapshots without one). A pin names no registry of its own.
  */
object SnapshotPins:
  val CentralSnapshots: String = ArtifactRegistry.MavenCentral.snapshotRepository

  val ResolverName: String = "central-snapshots"

  /** Coursier caches a changing artifact for 24 hours by default, and a snapshot is republished in place. Releases are
    * not changing, so they stay cached.
    */
  val CoursierTtl: (String, EnvValue) = "COURSIER_TTL" -> EnvValue.plain("0s")

  /** The zipx value wins, so `zipxEnv` cannot lengthen the TTL. */
  def ciEnv(env: Map[String, EnvValue]): Map[String, EnvValue] =
    env + CoursierTtl

  def of(coords: Seq[ZipxCoord]): List[ZipxCoord] =
    coords.filter(c => DepRevision.of(c.version).resolvesFromSnapshots).toList

  def describe(coord: ZipxCoord): String = s"${coord.group}:${coord.artifact}:${coord.version}"

  /** No publish registry means Central, which a build without a release workflow already resolves. */
  def registries(publish: Option[ArtifactRegistry], extra: Seq[ArtifactRegistry]): List[ArtifactRegistry] =
    val primary = publish.toList match
      case Nil  => List(ArtifactRegistry.MavenCentral)
      case some => some
    (primary ++ extra).distinct

  def resolverName(registry: ArtifactRegistry): String = registry match
    case ArtifactRegistry.MavenCentral => ResolverName
    case other                         => s"zipx-${slug(label(other))}"

  def resolverLine: String = resolverLine(ArtifactRegistry.MavenCentral)

  def resolverLine(registry: ArtifactRegistry): String =
    s"""resolvers += "${resolverName(registry)}" at "${registry.snapshotRepository}""""

  /** A pinned snapshot plugin re-resolves on every load: sbt otherwise replays the meta-build's cached `update`. */
  val forceUpdateLine: String = "forceUpdatePeriod := Some(scala.concurrent.duration.Duration.Zero)"

  def pluginsSbtLines(registries: List[ArtifactRegistry] = List(ArtifactRegistry.MavenCentral)): List[String] =
    registries.distinct.map(resolverLine) :+ forceUpdateLine

  /** A resolver line zipx itself wrote. Anything else in `plugins.sbt` stays a parse error. */
  def isOwnResolver(name: String): Boolean =
    name == ResolverName || name.startsWith("zipx-")

  private def label(registry: ArtifactRegistry): String = registry match
    case ArtifactRegistry.MavenCentral                => ResolverName
    case ArtifactRegistry.GitHubPackages(owner, repo) => s"github-packages-$owner-$repo"
    case ArtifactRegistry.Url(base)                   => base
    case ArtifactRegistry.Maven(snapshots, _)         => snapshots

  private def slug(raw: String): String =
    val chars = raw.map {
      case c if c.isLetterOrDigit => c
      case _                      => '-'
    }
    val collapsed = chars.foldLeft("") { (acc, c) =>
      if c == '-' && acc.endsWith("-") then acc else s"$acc$c"
    }
    val trimmed = collapsed.stripPrefix("-").stripSuffix("-")
    if trimmed.isEmpty then "repo" else trimmed.take(80)
  end slug

  def annotation(pins: ::[ZipxCoord]): Either[String, Steps] =
    Word
      .quotedMake(s"::warning title=zipx snapshots::pinned snapshots (${pins.map(describe).mkString(", ")})")
      .map(text => Steps.built("snapshot-pins")(Step.run(Script(Exec("echo", text))).named("Pinned snapshots")))
end SnapshotPins
