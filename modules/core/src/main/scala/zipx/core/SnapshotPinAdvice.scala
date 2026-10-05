package zipx.core

/** `zipxDepUpdate` never rewrites one of these to a newer release. A commit pin moves to its own line with
  * `zipxPinRelease`, and only after that line is on the release repository.
  */
enum SnapshotHold:
  case Commit(line: ReleaseVersion)
  case Local(id: String)
  case Pointer(line: ReleaseVersion)

object SnapshotPinAdvice:

  /** `declared` is either a revision or the `group:artifact:revision` a release check is handed. */
  def blocksRelease(declared: String): Boolean =
    val revision = declared.split(':').toList.reverse match
      case head :: _ => head
      case Nil       => declared
    DepRevision.of(revision).blocksRelease

  def hold(version: String): Option[SnapshotHold] =
    DepRevision.of(version) match
      case DepRevision.Commit(pin)         => Some(SnapshotHold.Commit(pin.line))
      case DepRevision.UnstoredCommit(pin) => Some(SnapshotHold.Commit(pin.line))
      case DepRevision.Local(local)        => Some(SnapshotHold.Local(local.id))
      case DepRevision.Pointer(line)       => Some(SnapshotHold.Pointer(line))
      case DepRevision.Release(_) | DepRevision.Changing(_) | DepRevision.Other(_) => None

  /** `latest` is the registry's newest release, if found. A newer release is not a reason to leave the pin's line. */
  def message(artifact: String, version: String, latest: Option[String]): Option[String] =
    hold(version).map {
      case SnapshotHold.Local(id) =>
        s"$artifact $id is a local build. It will not resolve on another machine. Commit and publish the sha, or drop the pin."
      case SnapshotHold.Commit(line) =>
        if released(line, latest) then s"$artifact $version is a snapshot of $line. Run sbt 'zipxPinRelease $artifact'."
        else s"$artifact $version is a snapshot of $line. It stays until $line is on the release repository."
      case SnapshotHold.Pointer(line) =>
        if released(line, latest) then
          s"$artifact $version is the snapshot pointer, not a build. $line is released. Run sbt 'zipxPinRelease $artifact'."
        else s"$artifact $version is the snapshot pointer, not a build. Run sbt zipxSnapshotStatus."
    }

  private def released(line: ReleaseVersion, latest: Option[String]): Boolean =
    latest.flatMap(raw => ReleaseVersion.make(raw).toOption).exists(rel => ReleaseVersion.ordering.gteq(rel, line))
end SnapshotPinAdvice
