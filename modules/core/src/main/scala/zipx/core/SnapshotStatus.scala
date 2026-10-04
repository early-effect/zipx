package zipx.core

/** What `zipxSnapshotStatus` prints. The pointer names the latest sha. The command does not rewrite the pin. */
object SnapshotStatus:

  enum Report:
    case Latest(name: String, pin: SnapshotRevision.Commit)
    case Newer(name: String, pin: SnapshotRevision.Commit, latest: AbbrevSha)
    case Missing(name: String, pin: SnapshotRevision.Commit)
    case Local(name: String, id: String)

  /** `pointerSha` is the full sha in the pointer POM. `artifactPresent` is whether the pinned build is still in the
    * snapshot repository.
    */
  def report(
      name: String,
      revision: String,
      pointerSha: Option[GitSha],
      artifactPresent: Boolean,
  ): Either[String, Report] =
    SnapshotRevision.parse(bare(revision)) match
      case Right(pin: SnapshotRevision.Commit) =>
        commitReport(name, pin, pointerSha, artifactPresent)
      case Right(other) =>
        Right(Report.Local(name, other.id))
      case Left(_) if SnapshotPublishRevision.isPointer(revision) =>
        Left(s"$revision is the snapshot pointer, not a pin. Run sbt zipxSnapshotStatus on a commit pin.")
      case Left(err) =>
        Left(err.message)

  def render(report: Report): String = report match
    case Report.Latest(name, pin) =>
      s"""$name ${pin.id}
         |  commit ${pin.abbrev}
         |  this is the latest snapshot of ${pin.line}""".stripMargin
    case Report.Newer(name, pin, latest) =>
      s"""$name ${pin.id}
         |  commit ${pin.abbrev}
         |  latest snapshot of ${pin.line} is ${pin.line}-$latest
         |  run: sbt 'zipxSnapshotAdvance $name'""".stripMargin
    case Report.Missing(name, pin) =>
      s"""$name ${pin.id}
         |  commit ${pin.abbrev}
         |  the snapshot repository no longer has ${pin.id} (snapshots are kept 90 days)
         |  run: sbt 'zipxSnapshotAdvance $name'""".stripMargin
    case Report.Local(name, id) =>
      s"""$name $id
         |  local build
         |  it will not resolve on another machine""".stripMargin

  private def bare(revision: String): String =
    if SnapshotPublishRevision.isImmutablePin(revision) && revision.endsWith(Modver.UnreleasedSuffix) then
      revision.stripSuffix(Modver.UnreleasedSuffix)
    else revision

  private def commitReport(
      name: String,
      pin: SnapshotRevision.Commit,
      pointerSha: Option[GitSha],
      artifactPresent: Boolean,
  ): Either[String, Report] =
    pointerSha.map(AbbrevSha.fromFull) match
      case Some(latest) if latest != pin.abbrev => Right(Report.Newer(name, pin, latest))
      case _ if !artifactPresent                => Right(Report.Missing(name, pin))
      case Some(_)                              => Right(Report.Latest(name, pin))
      case None                                 =>
        Left(
          s"the pointer for ${pin.line} has no ${SnapshotPointer.ShaElement}, and ${pin.id} is still in the snapshot repository"
        )
end SnapshotStatus
