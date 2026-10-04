package zipx.core

/** A catalog version that is a commit pin, a dirty local id, or the `<line>-SNAPSHOT` pointer.
  *
  * `zipxDepUpdate` does not rewrite one of these to a newer release. A commit pin moves to its own line with
  * `zipxPinRelease`, and only after that line is on the release repository.
  */
enum SnapshotHold:
  case Commit(line: ReleaseVersion)
  case Local(id: String)
  case Pointer(line: ReleaseVersion)

object SnapshotPinAdvice:

  /** A release POM cannot depend on this revision. Commit pins do not end in `-SNAPSHOT`, so the suffix check is not
    * enough.
    */
  /** `declared` is either a revision or `group:artifact:revision`, which is what a release check is handed. */
  def blocksRelease(declared: String): Boolean =
    val revision = declared.split(':').toList.reverse match
      case head :: _ => head
      case Nil       => declared
    SnapshotPins.isSnapshot(revision) ||
    SnapshotPublishRevision.isImmutablePin(revision) ||
    SnapshotRevision.parse(revision).exists(!_.stable)

  def hold(version: String): Option[SnapshotHold] =
    SnapshotRevision.parse(version) match
      case Right(pin: SnapshotRevision.Commit) => Some(SnapshotHold.Commit(pin.line))
      case Right(other)                        => Some(SnapshotHold.Local(other.id))
      case Left(_)                             =>
        if SnapshotPublishRevision.isPointer(version) then
          ReleaseVersion.make(version.stripSuffix(Modver.UnreleasedSuffix)).toOption.map(SnapshotHold.Pointer(_))
        else
          val bare =
            if version.endsWith(Modver.UnreleasedSuffix) then version.stripSuffix(Modver.UnreleasedSuffix)
            else version
          SnapshotRevision.parse(bare) match
            case Right(pin: SnapshotRevision.Commit) => Some(SnapshotHold.Commit(pin.line))
            case _                                   => None

  /** `latest` is the registry's newest release, when the lookup found one. A newer release is not a reason to leave the
    * pin's line.
    */
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
