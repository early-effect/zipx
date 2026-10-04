package zipx.core

/** Rewriting one `Lib` version in the catalog source. Advance changes the sha. Pin-release drops it. */
object PinRewrite:

  def replace(source: String, group: String, artifact: String, from: String, to: String): Either[String, String] =
    val current = ZipxCatalog.constructorCall("Lib", group, artifact, from)
    if source.contains(current) then
      Right(source.replace(current, ZipxCatalog.constructorCall("Lib", group, artifact, to)))
    else Left(s"catalog has no $current")

  /** The commit id the pointer names, on the pin's own line. A dirty pin is refused. The same sha is not a rewrite. */
  def advance(current: String, pointerSha: GitSha): Either[String, Option[String]] =
    val bare =
      if SnapshotPublishRevision.isImmutablePin(current) && current.endsWith(Modver.UnreleasedSuffix) then
        current.stripSuffix(Modver.UnreleasedSuffix)
      else current
    SnapshotRevision.parse(bare) match
      case Right(pin: SnapshotRevision.Commit) =>
        val next = s"${pin.line}-${AbbrevSha.fromFull(pointerSha)}"
        if next == pin.id then Right(None) else Right(Some(next))
      case Right(other) =>
        Left(SnapshotRevisionError.Unstable(other.id).message)
      case Left(_) if SnapshotPublishRevision.isPointer(current) =>
        Left(s"$current is the snapshot pointer, not a pin. Run sbt zipxSnapshotStatus.")
      case Left(err) =>
        Left(err.message)
    end match
  end advance

  /** The same line, with the sha removed. Refuses a dirty pin, a pin that is already a release, and a missing release.
    * It does not look up a newer line.
    */
  def pinRelease(current: String, lineReleased: Boolean): Either[String, String] =
    SnapshotRevision.parse(bareCommit(current)) match
      case Right(pin: SnapshotRevision.Commit) =>
        released(pin.line, lineReleased)
      case Right(other) =>
        Left(SnapshotRevisionError.Unstable(other.id).message)
      case Left(_) if SnapshotPublishRevision.isPointer(current) =>
        ReleaseVersion.make(current.stripSuffix(Modver.UnreleasedSuffix)) match
          case Right(line) => released(line, lineReleased)
          case Left(err)   => Left(err)
      case Left(_) if ReleaseVersion.make(current).isRight =>
        Left(s"$current is already a release. zipxPinRelease rewrites a snapshot pin, not a release.")
      case Left(err) =>
        Left(err.message)

  private def released(line: ReleaseVersion, lineReleased: Boolean): Either[String, String] =
    if lineReleased then Right(line: String)
    else
      Left(
        s"$line is not on the release repository. Release it first, one deployment, then sbt 'zipxPinRelease'."
      )

  private def bareCommit(current: String): String =
    if SnapshotPublishRevision.isImmutablePin(current) && current.endsWith(Modver.UnreleasedSuffix) then
      current.stripSuffix(Modver.UnreleasedSuffix)
    else current
end PinRewrite
