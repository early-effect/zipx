package zipx.core

/** The revision a snapshot publish writes, from the JVM properties the command sets before `reload`.
  *
  * Compile stays on `<line>-ci`. These properties exist only for the publish session, because that session's `version`
  * is the coordinate the repository stores.
  */
object SnapshotPublishRevision:
  val ShaProperty: String     = "zipx.snapshot.sha"
  val DirtyProperty: String   = "zipx.snapshot.dirty"
  val PointerProperty: String = "zipx.snapshot.pointer"
  val LocalProperty: String   = "zipx.snapshot.local"

  /** The coordinate this publish stores. A pointer is `<line>-SNAPSHOT` and names the sha in its POM. */
  def revision(
      row: PublishedRow,
      registry: ArtifactRegistry,
      props: collection.Map[String, String],
  ): Either[SnapshotRevisionError, String] =
    if props.get(PointerProperty).contains("true") then Right(s"${row.version}${Modver.UnreleasedSuffix}")
    else
      identity(row, props).flatMap { rev =>
        if props.get(LocalProperty).contains("true") then Right(rev.id)
        else rev.mavenRevision(registry)
      }

  def identity(
      row: PublishedRow,
      props: collection.Map[String, String],
  ): Either[SnapshotRevisionError, SnapshotRevision] =
    props.get(ShaProperty) match
      case None =>
        props.get(DirtyProperty) match
          case Some(stamp) => DirtyStamp.from(stamp).map(SnapshotRevision.noGit)
          case None        => Left(SnapshotRevisionError.NotCommitPin(ShaProperty))
      case Some(raw) =>
        GitSha.make(raw).left.map(err => SnapshotRevisionError.NotCommitPin(err)).flatMap { full =>
          props.get(DirtyProperty) match
            case None =>
              Right(SnapshotRevision.commit(row.version, full))
            case Some(stamp) =>
              DirtyStamp.from(stamp).map(at => SnapshotRevision.dirty(row.version, full, at))
        }

  /** A commit pin, including the Central form that appends `-SNAPSHOT` to the id. The pointer is not one. */
  def isImmutablePin(revision: String): Boolean =
    val bare =
      if revision.endsWith(Modver.UnreleasedSuffix) then revision.stripSuffix(Modver.UnreleasedSuffix) else revision
    SnapshotRevision.parse(bare).exists(_.stable)

  /** `<line>-SNAPSHOT` with nothing between the line and the suffix. That coordinate is the latest-sha pointer.
    * `endsWith` rather than an interpolator pattern: a hole with no literal beside it does not split the string.
    */
  def isPointer(revision: String): Boolean =
    revision.endsWith(Modver.UnreleasedSuffix) &&
      ReleaseVersion.make(revision.stripSuffix(Modver.UnreleasedSuffix)).isRight

  /** `update` says this and stops. The pointer names a sha; it is not a build to compile against. */
  def pointerRefusal(revisions: Seq[String]): String =
    val kind =
      if revisions.sizeIs == 1 then "is the snapshot pointer, not a build"
      else "are snapshot pointers, not builds"
    s"${revisions.mkString(", ")} $kind. Run sbt zipxSnapshotStatus"
end SnapshotPublishRevision
