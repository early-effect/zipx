package zipx.core

import zipx.shell.{Exec, Script, Word}
import zipx.workflow.Step

/** Catalog rows that name an unreleased upstream at `-SNAPSHOT`. While any is pinned, the build resolves Central
  * snapshots and re-resolves them on every session.
  */
object SnapshotPins:
  val CentralSnapshots: String = ArtifactRegistry.MavenCentral.snapshotRepository

  val ResolverName: String = "central-snapshots"

  /** Coursier keeps a changing artifact for 24 hours by default; CI must see each republish. */
  val CoursierTtl: (String, EnvValue) = "COURSIER_TTL" -> EnvValue.plain("0s")

  def of(coords: Seq[ZipxCoord]): List[ZipxCoord] =
    coords.filter(c => isSnapshot(c.version)).toList

  def isSnapshot(version: String): Boolean = version.endsWith(Modver.UnreleasedSuffix)

  def describe(coord: ZipxCoord): String = s"${coord.group}:${coord.artifact}:${coord.version}"

  def resolverLine: String = s"""resolvers += "$ResolverName" at "$CentralSnapshots""""

  def annotation(pins: ::[ZipxCoord]): Either[String, Steps] =
    Word
      .quotedMake(s"::warning title=zipx snapshots::pinned snapshots (${pins.map(describe).mkString(", ")})")
      .map(text => Steps.built("snapshot-pins")(Step.run(Script(Exec("echo", text))).named("Pinned snapshots")))
end SnapshotPins
