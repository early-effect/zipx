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
    DepRevision.of(revision) match
      case DepRevision.Commit(pin) =>
        commitReport(name, pin, pointerSha, artifactPresent)
      case DepRevision.UnstoredCommit(pin) =>
        commitReport(name, pin, pointerSha, artifactPresent)
      case DepRevision.Local(local) =>
        Right(Report.Local(name, local.id))
      case DepRevision.Pointer(_) =>
        Left(s"$revision is the snapshot pointer, not a pin. Run sbt zipxSnapshotStatus on a commit pin.")
      case DepRevision.Release(_) | DepRevision.Changing(_) | DepRevision.Other(_) =>
        Left(SnapshotRevisionError.NotCommitPin(revision).message)

  def render(report: Report): String = report match
    case Report.Latest(name, pin) =>
      s"""$name ${pin.storedId}
         |  commit ${pin.abbrev}
         |  this is the latest snapshot of ${pin.line}""".stripMargin
    case Report.Newer(name, pin, latest) =>
      s"""$name ${pin.storedId}
         |  commit ${pin.abbrev}
         |  latest snapshot of ${pin.line} is ${SnapshotRevision.Commit(pin.line, latest, None).storedId}
         |  run: sbt 'zipxSnapshotAdvance $name'""".stripMargin
    case Report.Missing(name, pin) =>
      s"""$name ${pin.storedId}
         |  commit ${pin.abbrev}
         |  the snapshot repository no longer has ${pin.storedId} (snapshots are kept 90 days)
         |  run: sbt 'zipxSnapshotAdvance $name'""".stripMargin
    case Report.Local(name, id) =>
      s"""$name $id
         |  local build
         |  it will not resolve on another machine""".stripMargin

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
          s"the pointer for ${pin.line} has no ${SnapshotPointer.ShaElement}, and ${pin.storedId} is still in the snapshot repository"
        )
end SnapshotStatus
