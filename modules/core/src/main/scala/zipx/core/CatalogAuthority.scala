package zipx.core

/** The order of two revisions zipx has no line for, such as milestones. The plugin supplies Coursier's. */
trait RevisionOrder:
  def compare(a: String, b: String): Int

/** How the revision this build states compares with one a dependency asked for. */
enum Precedence:
  case Newer, Same, Older, Unordered

object Precedence:

  /** Lines decide first. A release is newer than every commit, pointer, or local build of its line. Two commits of one
    * line have no order, because a sha is not a sequence. Revisions with no zipx line use [[RevisionOrder]].
    */
  def of(stated: DepRevision, wanted: DepRevision)(using order: RevisionOrder): Precedence =
    if stated.render == wanted.render then Same
    else
      (lineOf(stated), lineOf(wanted)) match
        case (Some(a), Some(b)) if a != b =>
          if ReleaseVersion.ordering.gt(a, b) then Newer else Older
        case (Some(_), Some(_)) =>
          sameLine(stated, wanted)
        case _ =>
          val sign = order.compare(stated.render, wanted.render)
          if sign > 0 then Newer else if sign < 0 then Older else Same

  private def lineOf(revision: DepRevision): Option[ReleaseVersion] = revision match
    case DepRevision.Release(version)                          => Some(version)
    case DepRevision.Commit(pin)                               => Some(pin.line)
    case DepRevision.UnstoredCommit(pin)                       => Some(pin.line)
    case DepRevision.Pointer(line)                             => Some(line)
    case DepRevision.Local(SnapshotRevision.Dirty(line, _, _)) => Some(line)
    case DepRevision.Local(_)                                  => None
    case DepRevision.Changing(_) | DepRevision.Other(_)        => None

  private def sameLine(stated: DepRevision, wanted: DepRevision): Precedence =
    (stated, wanted) match
      case (DepRevision.Release(_), _) => Newer
      case (_, DepRevision.Release(_)) => Older
      case _                           =>
        (commitOf(stated), commitOf(wanted)) match
          case (Some(a), Some(b)) if a.abbrev == b.abbrev => Same
          case _                                          => Unordered

  private[core] def commitOf(revision: DepRevision): Option[SnapshotRevision.Commit] = revision match
    case DepRevision.Commit(pin)         => Some(pin)
    case DepRevision.UnstoredCommit(pin) => Some(pin)
    case _                               => None
end Precedence

/** A revision of a module that one library in the graph asked for. */
final case class Wanted(revision: DepRevision, by: ResolvedModule)

/** Why `update` stops after resolving. Each message names what fixes it. */
enum CatalogConflict:
  /** The catalog states an older revision than a dependency needs: the catalog is behind, not the dependency. */
  case Stale(module: ResolvedModule, row: Lib, wanted: Wanted)

  /** Two commits of one line meet, and nothing in this build states which one. */
  case Unpinned(module: ResolvedModule, commits: ::[SnapshotRevision.Commit])

  def message: String = this match
    case Stale(module, row, Wanted(wanted, by)) =>
      s"${module.render}: the catalog pins ${row.version}, and ${by.render} needs ${wanted.render}. ${CatalogConflict.fix(row, wanted)}"
    case Unpinned(module, commits @ first :: _) =>
      val ids = commits.map(_.storedId).mkString(" and ")
      s"${module.render} meets $ids, commits of ${first.line} with no order. Pin one in the catalog."
end CatalogConflict

object CatalogConflict:

  /** The newest revision a dependency needs above the catalog's, if any. Older and unordered wants are the catalog's to
    * overrule.
    */
  def stale(module: ResolvedModule, row: Lib, wanted: List[Wanted])(using RevisionOrder): Option[Stale] =
    val stated = DepRevision.of(row.version)
    wanted
      .filter(want => Precedence.of(stated, want.revision) == Precedence.Older)
      .sortWith((a, b) => Precedence.of(a.revision, b.revision) == Precedence.Newer)
      .headOption
      .map(Stale(module, row, _))

  /** Commits of one line that a module nothing in this build states is met at, when they are not one commit. */
  def unpinned(module: ResolvedModule, revisions: List[DepRevision]): Option[Unpinned] =
    revisions
      .flatMap(Precedence.commitOf)
      .distinctBy(_.abbrev)
      .groupBy(_.line)
      .toList
      .sortBy((line, _) => line)
      .collectFirst { case (_, first :: second :: rest) => Unpinned(module, ::(first, second :: rest)) }

  /** The command that moves the catalog to what the dependency needs. */
  private def fix(row: Lib, wanted: DepRevision): String =
    def advance(line: ReleaseVersion) = s"Move the pin to that line: sbt 'zipxSnapshotAdvance ${row.artifact} $line'"
    (DepRevision.of(row.version), wanted) match
      case (DepRevision.Commit(pin), DepRevision.Release(release)) if pin.line == release =>
        s"That line is released: sbt 'zipxPinRelease ${row.artifact}'"
      case (_, DepRevision.Commit(pin))                     => advance(pin.line)
      case (_, DepRevision.UnstoredCommit(pin))             => advance(pin.line)
      case (_, DepRevision.Pointer(line))                   => advance(line)
      case (DepRevision.Release(_), DepRevision.Release(_)) =>
        s"Update the catalog: sbt zipxDepUpdate, or pin ${wanted.render}"
      case _ =>
        s"Pin ${wanted.render} in the catalog."
    end match
  end fix
end CatalogConflict
