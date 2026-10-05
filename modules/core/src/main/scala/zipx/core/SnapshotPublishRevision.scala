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

  /** The coordinate this publish stores. A pointer is `<line>-SNAPSHOT` and names the sha in its POM. A clean commit is
    * stored the same way on every registry, ivy-local included, so one pin works from either. Only a local publish
    * takes a dirty or git-less id.
    */
  def revision(row: PublishedRow, props: collection.Map[String, String]): Either[SnapshotRevisionError, String] =
    if props.get(PointerProperty).contains("true") then Right(SnapshotPointer.pointerVersion(row.version))
    else
      identity(row, props).flatMap { rev =>
        if props.get(LocalProperty).contains("true") then rev.stored.orElse(Right(rev.id))
        else rev.stored
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
end SnapshotPublishRevision
