package zipx.core

import scala.annotation.tailrec

/** What a line's pointer POM says about the commit it stands for. */
enum PointerRead:
  case Names(sha: GitSha)

  /** The snapshot repository has no pointer for the line. */
  case Absent

  /** A pointer POM that names no commit: it was published without `zipx.snapshot.sha`, by a plain publish. */
  case Unnamed

object PointerRead:
  def of(pom: Option[String]): PointerRead =
    pom.fold(Absent)(body => SnapshotPointer.shaFromPom(body).fold(_ => Unnamed, Names(_)))

  /** The first read that names a commit, without reading past it. Failing that, [[Unnamed]] when any pointer exists. */
  def first(reads: Iterator[PointerRead]): PointerRead =
    @tailrec
    def loop(seen: PointerRead): PointerRead =
      if !reads.hasNext then seen
      else
        reads.next() match
          case found @ Names(_) => found
          case Unnamed          => loop(Unnamed)
          case Absent           => loop(seen)
    loop(Absent)
  end first
end PointerRead

/** What `zipxSnapshotStatus` prints. The pointer names the latest sha. The command does not rewrite the pin. */
object SnapshotStatus:

  enum Report:
    case Latest(name: String, pin: SnapshotRevision.Commit)
    case Newer(name: String, pin: SnapshotRevision.Commit, latest: AbbrevSha)
    case Missing(name: String, pin: SnapshotRevision.Commit)
    case NoPointer(name: String, pin: SnapshotRevision.Commit)
    case Unnamed(name: String, pin: SnapshotRevision.Commit)
    case Local(name: String, id: String)

  /** `artifactPresent` is whether the pinned build is still in the snapshot repository. */
  def report(
      name: String,
      revision: String,
      pointer: PointerRead,
      artifactPresent: Boolean,
  ): Either[String, Report] =
    DepRevision.of(revision) match
      case DepRevision.Commit(pin) =>
        Right(commitReport(name, pin, pointer, artifactPresent))
      case DepRevision.UnstoredCommit(pin) =>
        Right(commitReport(name, pin, pointer, artifactPresent))
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
    case Report.NoPointer(name, pin) =>
      s"""$name ${pin.storedId}
         |  commit ${pin.abbrev}
         |  the snapshot repository has no ${SnapshotPointer.pointerVersion(
          pin.line
        )} pointer, so the latest is unknown""".stripMargin
    case Report.Unnamed(name, pin) =>
      s"""$name ${pin.storedId}
         |  commit ${pin.abbrev}
         |  the ${SnapshotPointer.pointerVersion(
          pin.line
        )} pointer names no commit (no ${SnapshotPointer.ShaElement}), so the latest is unknown
         |  publish the line with sbt zipxSnapshotPublish to give it one""".stripMargin
    case Report.Local(name, id) =>
      s"""$name $id
         |  local build
         |  it will not resolve on another machine""".stripMargin

  private def commitReport(
      name: String,
      pin: SnapshotRevision.Commit,
      pointer: PointerRead,
      artifactPresent: Boolean,
  ): Report =
    pointer match
      case PointerRead.Names(sha) if AbbrevSha.fromFull(sha) != pin.abbrev =>
        Report.Newer(name, pin, AbbrevSha.fromFull(sha))
      case _ if !artifactPresent => Report.Missing(name, pin)
      case PointerRead.Names(_)  => Report.Latest(name, pin)
      case PointerRead.Absent    => Report.NoPointer(name, pin)
      case PointerRead.Unnamed   => Report.Unnamed(name, pin)
end SnapshotStatus
