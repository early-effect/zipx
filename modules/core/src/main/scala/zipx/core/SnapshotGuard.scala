package zipx.core

/** What a build does when a released row has moved on since its tag. `Fail` is the default: a green compile or a green
  * nightly of a shadowed snapshot is the bug.
  */
enum DriftGate:
  case Warn
  case Fail

/** Git's view of one row against the tag of its current catalog number. The registry is a separate question. */
enum ReleasedDrift:
  case Unchanged(tag: String)
  case Changed(tag: String)
  case Untagged(tag: String)
  case Unreadable(detail: String)

/** What `zipxSnapshotPublish` should do after it knows which rows the release registry already has. */
final case class SnapshotVerdict(refusals: List[String], warnings: List[String], hints: List[String]):
  def refuses: Boolean = refusals.nonEmpty

object SnapshotGuard:

  val BumpCommand: String = "sbt zipxModverBump"

  def shadowed(row: PublishedRow, tag: String): String =
    s"${Modver.describe(row)} ${row.version} is released and has changes since $tag, so a snapshot of ${row.version} is shadowed by that release and these commits publish nothing. $BumpCommand"

  def untagged(row: PublishedRow, tag: String): String =
    s"${Modver.describe(row)} ${row.version} is released but tag $tag is not in this clone, so zipx cannot prove a snapshot of ${row.version} is not shadowed. Fetch tags, or $BumpCommand"

  def unreadable(row: PublishedRow, detail: String): String =
    s"${Modver.describe(row)} ${row.version} is released but zipx could not read changes since its release ($detail), so it will not publish a snapshot of ${row.version}. $BumpCommand"

  def hint(row: PublishedRow, tag: String): String =
    s"${Modver.describe(row)} ${row.version} is released and unchanged since $tag. Open the next snapshot with $BumpCommand"

  /** Sorted by row description so the message does not depend on map order. */
  def decide(rows: List[(PublishedRow, ReleasedDrift)], gate: DriftGate): SnapshotVerdict =
    val (refusals, warnings, hints) =
      rows
        .sortBy((row, _) => Modver.describe(row))
        .foldLeft((List.empty[String], List.empty[String], List.empty[String])) {
          case ((refusals, warnings, hints), (row, drift)) =>
            drift match
              case ReleasedDrift.Unchanged(tag)     => (refusals, warnings, hints :+ hint(row, tag))
              case ReleasedDrift.Changed(tag)       => gate.place(shadowed(row, tag), refusals, warnings, hints)
              case ReleasedDrift.Untagged(tag)      => gate.place(untagged(row, tag), refusals, warnings, hints)
              case ReleasedDrift.Unreadable(detail) => gate.place(unreadable(row, detail), refusals, warnings, hints)
        }
    SnapshotVerdict(refusals, warnings, hints)
  end decide

  extension (gate: DriftGate)
    private def place(
        sentence: String,
        refusals: List[String],
        warnings: List[String],
        hints: List[String],
    ): (List[String], List[String], List[String]) =
      gate match
        case DriftGate.Fail => (refusals :+ sentence, warnings, hints)
        case DriftGate.Warn => (refusals, warnings :+ sentence, hints)
  end extension
end SnapshotGuard
