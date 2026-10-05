package zipx.core

/** Read from JVM properties the publish command sets before `reload`. Only the publish session reads them, since its
  * `version` is the stored coordinate; compile stays on `<line>-ci`.
  */
object SnapshotPublishRevision:
  val ShaProperty: String     = "zipx.snapshot.sha"
  val DirtyProperty: String   = "zipx.snapshot.dirty"
  val PointerProperty: String = "zipx.snapshot.pointer"
  val LocalProperty: String   = "zipx.snapshot.local"

  /** A clean commit is stored the same way on every registry, ivy-local included, so one pin works from either. Only a
    * local publish takes a dirty or git-less id.
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
