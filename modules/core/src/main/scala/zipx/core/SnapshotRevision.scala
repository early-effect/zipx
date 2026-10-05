package zipx.core

/** Twelve lowercase hex characters, the abbreviation of a commit stored in a snapshot id. */
final case class AbbrevSha private (value: String):
  override def toString: String = value

object AbbrevSha:
  private def lowerHex(c: Char): Boolean =
    c.isDigit || (c >= 'a' && c <= 'f')

  /** Total: a [[GitSha]] is 40 hex. */
  def fromFull(full: GitSha): AbbrevSha =
    AbbrevSha(full.take(12).toLowerCase)

  def from(raw: String): Either[SnapshotRevisionError, AbbrevSha] =
    if raw.length == 12 && raw.forall(lowerHex) then Right(AbbrevSha(raw))
    else if raw.nonEmpty && raw.length < 12 && raw.forall(lowerHex) then Left(SnapshotRevisionError.ShaTooShort(raw))
    else Left(SnapshotRevisionError.NotCommitPin(raw))
end AbbrevSha

/** Dynver's dirty mark: `YYYYMMDD-HHmm`. The minute the working tree was published, not a commit. */
final case class DirtyStamp private (value: String):
  override def toString: String = value

object DirtyStamp:
  private val Shape = """(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})""".r

  def from(raw: String): Either[SnapshotRevisionError, DirtyStamp] =
    raw match
      case Shape(_, month, day, hour, minute)
          if inRange(month, 1, 12) && inRange(day, 1, 31) && inRange(hour, 0, 23) && inRange(minute, 0, 59) =>
        Right(DirtyStamp(raw))
      case _ =>
        Left(SnapshotRevisionError.BadStamp(raw))

  private def inRange(raw: String, low: Int, high: Int): Boolean =
    raw.toIntOption.exists(n => n >= low && n <= high)
end DirtyStamp

/** Why a string is not a snapshot id, or why an id cannot be uploaded. */
enum SnapshotRevisionError:
  case NotCommitPin(raw: String)
  case ShaTooShort(raw: String)
  case DirtyMarkMissing(raw: String)
  case BadStamp(raw: String)
  case BadLine(raw: String)
  case Unstable(id: String)

  def message: String = this match
    case NotCommitPin(raw) =>
      s"'$raw' is not a snapshot id. A commit is <line>-<12 hex>. A dirty local publish appends +YYYYMMDD-HHmm. No git is HEAD+YYYYMMDD-HHmm."
    case ShaTooShort(raw) =>
      s"'$raw' is shorter than 12 hex characters. A snapshot sha is the 12-character abbreviation of the commit."
    case DirtyMarkMissing(raw) =>
      s"'$raw' has a timestamp but no + before it. A dirty local id is <line>-<sha>+YYYYMMDD-HHmm."
    case BadStamp(raw) =>
      s"'$raw' is not a dirty timestamp. The mark is +YYYYMMDD-HHmm."
    case BadLine(raw) =>
      s"'$raw' is not a release line. A line is major.minor.patch."
    case Unstable(id) =>
      s"$id is a local build. Commit the tree, or publish it with zipxSnapshotPublish local."
end SnapshotRevisionError

/** The identity of an unreleased build: the next release line plus the commit. Dirty and git-less trees are local
  * publishes with dynver's `+YYYYMMDD-HHmm` mark, never uploaded.
  *
  * `-SNAPSHOT` is not part of the id. Registries store a clean commit as id plus `-SNAPSHOT` (snapshot-policy
  * repositories refuse anything else), and that stored revision is what a downstream catalog pins.
  */
enum SnapshotRevision:
  case Commit(line: ReleaseVersion, abbrev: AbbrevSha, full: Option[GitSha])
  case Dirty(line: ReleaseVersion, abbrev: AbbrevSha, at: DirtyStamp)
  case NoGit(at: DirtyStamp)

  def id: String = this match
    case Commit(line, abbrev, _) => s"$line-$abbrev"
    case Dirty(line, abbrev, at) => s"$line-$abbrev+$at"
    case NoGit(at)               => s"HEAD+$at"

  def stable: Boolean = this match
    case _: Commit           => true
    case _: Dirty | _: NoGit => false

  def stored: Either[SnapshotRevisionError, String] = this match
    case commit: Commit      => Right(commit.storedId)
    case _: Dirty | _: NoGit => Left(SnapshotRevisionError.Unstable(id))
end SnapshotRevision

object SnapshotRevision:
  private val NoGitId     = """HEAD\+([0-9]{8}-[0-9]{4})""".r
  private val DirtyId     = """(\d+\.\d+\.\d+)-([0-9a-f]{12})\+([0-9]{8}-[0-9]{4})""".r
  private val MissingPlus = """(\d+\.\d+\.\d+)-([0-9a-f]{12})-(\d{8}-\d{4})""".r
  private val CommitId    = """(\d+\.\d+\.\d+)-([0-9a-f]{12})""".r
  private val ShortSha    = """(\d+\.\d+\.\d+)-([0-9a-f]{1,11})""".r

  def commit(line: ReleaseVersion, full: GitSha): Commit =
    Commit(line, AbbrevSha.fromFull(full), Some(full))

  extension (commit: Commit)
    /** `<line>-<sha>-SNAPSHOT`: the coordinate a registry holds and a catalog pins. */
    def storedId: String = s"${commit.id}${Modver.UnreleasedSuffix}"

  def dirty(line: ReleaseVersion, full: GitSha, at: DirtyStamp): Dirty =
    Dirty(line, AbbrevSha.fromFull(full), at)

  def noGit(at: DirtyStamp): NoGit =
    NoGit(at)

  def parse(raw: String): Either[SnapshotRevisionError, SnapshotRevision] =
    raw match
      case NoGitId(stamp) =>
        DirtyStamp.from(stamp).map(NoGit(_))
      case DirtyId(line, sha, stamp) =>
        for
          parsedLine  <- releaseLine(line)
          parsedSha   <- AbbrevSha.from(sha)
          parsedStamp <- DirtyStamp.from(stamp)
        yield Dirty(parsedLine, parsedSha, parsedStamp)
      case MissingPlus(_, _, _) =>
        Left(SnapshotRevisionError.DirtyMarkMissing(raw))
      case CommitId(line, sha) =>
        for
          parsedLine <- releaseLine(line)
          parsedSha  <- AbbrevSha.from(sha)
        yield Commit(parsedLine, parsedSha, None)
      case ShortSha(_, sha) =>
        Left(SnapshotRevisionError.ShaTooShort(sha))
      case _ =>
        Left(SnapshotRevisionError.NotCommitPin(raw))

  private def releaseLine(raw: String): Either[SnapshotRevisionError, ReleaseVersion] =
    ReleaseVersion.make(raw).left.map(_ => SnapshotRevisionError.BadLine(raw))
end SnapshotRevision
