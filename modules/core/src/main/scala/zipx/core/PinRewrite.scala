package zipx.core

/** Rewriting one row's version in the catalog source. Advance changes the sha. Pin-release drops it. */
object PinRewrite:

  def replace(source: String, row: ZipxCoord, to: String): Either[String, String] =
    val current = ZipxCatalog.constructorCall(row, row.version)
    if source.contains(current) then Right(source.replace(current, ZipxCatalog.constructorCall(row, to)))
    else Left(s"catalog has no $current")

  /** The stored commit the pointer names, on the pin's own line. A bare pin is rewritten to the stored form even at the
    * same sha; an already-stored pin at that sha is not a rewrite.
    */
  def advance(current: String, pointerSha: GitSha): Either[String, Option[String]] =
    def to(pin: SnapshotRevision.Commit): Option[String] =
      val next = SnapshotRevision.commit(pin.line, pointerSha).storedId
      Option.when(next != current)(next)
    DepRevision.of(current) match
      case DepRevision.Commit(pin)         => Right(to(pin))
      case DepRevision.UnstoredCommit(pin) => Right(to(pin))
      case DepRevision.Local(local)        => Left(SnapshotRevisionError.Unstable(local.id).message)
      case DepRevision.Pointer(_)          =>
        Left(s"$current is the snapshot pointer, not a pin. Run sbt zipxSnapshotStatus.")
      case DepRevision.Release(_) | DepRevision.Changing(_) | DepRevision.Other(_) =>
        Left(SnapshotRevisionError.NotCommitPin(current).message)
  end advance

  /** The only way a pin changes lines ([[advance]] stays on its own). A release row becomes a commit pin of `line`. */
  def moveTo(current: String, line: ReleaseVersion, pointerSha: GitSha): Either[String, Option[String]] =
    val next = SnapshotRevision.commit(line, pointerSha).storedId
    DepRevision.of(current) match
      case DepRevision.Local(local) => Left(SnapshotRevisionError.Unstable(local.id).message)
      case DepRevision.Pointer(_)   =>
        Left(s"$current is the snapshot pointer, not a pin. Run sbt zipxSnapshotStatus.")
      case _ => Right(Option.when(next != current)(next))

  /** The same line with the sha removed. It does not look up a newer line. */
  def pinRelease(current: String, lineReleased: Boolean): Either[String, String] =
    DepRevision.of(current) match
      case DepRevision.Commit(pin)         => released(pin.line, lineReleased)
      case DepRevision.UnstoredCommit(pin) => released(pin.line, lineReleased)
      case DepRevision.Pointer(line)       => released(line, lineReleased)
      case DepRevision.Local(local)        => Left(SnapshotRevisionError.Unstable(local.id).message)
      case DepRevision.Release(_)          =>
        Left(s"$current is already a release. zipxPinRelease rewrites a snapshot pin, not a release.")
      case DepRevision.Changing(_) | DepRevision.Other(_) =>
        Left(SnapshotRevisionError.NotCommitPin(current).message)

  private def released(line: ReleaseVersion, lineReleased: Boolean): Either[String, String] =
    if lineReleased then Right(line: String)
    else
      Left(
        s"$line is not on the release repository. Release it first, one deployment, then sbt 'zipxPinRelease'."
      )
end PinRewrite
