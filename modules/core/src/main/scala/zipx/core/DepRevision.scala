package zipx.core

/** A dependency revision, read once. A catalog row, a declared `libraryDependency`, and a POM edge all go through
  * [[DepRevision.of]], so no caller tests suffixes.
  */
enum DepRevision:
  /** `major.minor.patch`. */
  case Release(version: ReleaseVersion)

  /** `<line>-<12 hex>-SNAPSHOT`: one clean commit, as every registry stores it. It never changes. */
  case Commit(pin: SnapshotRevision.Commit)

  /** `<line>-<12 hex>` with no suffix. No registry stores that coordinate; the pin is [[Commit]]. */
  case UnstoredCommit(pin: SnapshotRevision.Commit)

  /** `<line>-SNAPSHOT`: the pointer whose POM names the latest sha. It is not a build. */
  case Pointer(line: ReleaseVersion)

  /** A dirty tree or a tree with no git. Published to ivy-local only. */
  case Local(revision: SnapshotRevision)

  /** Any other `-SNAPSHOT`, such as `1.0.0-RC1-SNAPSHOT`. Republished in place. */
  case Changing(raw: String)

  /** Everything else: milestones, release candidates, four-part numbers. */
  case Other(raw: String)

  /** A release POM may not name it. */
  def blocksRelease: Boolean = this match
    case Release(_) | Other(_) => false
    case _                     => true

  /** Resolving it reads the snapshot repository. */
  def resolvesFromSnapshots: Boolean = this match
    case Commit(_) | Pointer(_) | Changing(_) => true
    case _                                    => false
end DepRevision

object DepRevision:
  def of(raw: String): DepRevision =
    ReleaseVersion.make(raw) match
      case Right(release) => Release(release)
      case Left(_)        =>
        raw match
          case s"$body-SNAPSHOT" => snapshot(raw, body)
          case _                 =>
            SnapshotRevision.parse(raw) match
              case Right(pin: SnapshotRevision.Commit) => UnstoredCommit(pin)
              case Right(local)                        => Local(local)
              case Left(_)                             => Other(raw)

  private def snapshot(raw: String, body: String): DepRevision =
    ReleaseVersion.make(body) match
      case Right(line) => Pointer(line)
      case Left(_)     =>
        SnapshotRevision.parse(body) match
          case Right(pin: SnapshotRevision.Commit) => Commit(pin)
          case _                                   => Changing(raw)
end DepRevision

/** Why `update` stops before it resolves anything. */
enum PinRefusal:
  case Pointers(revisions: ::[String])
  case Unstored(pins: ::[SnapshotRevision.Commit])

  def message: String = this match
    case Pointers(revisions) =>
      val kind =
        if revisions.sizeIs == 1 then "is the snapshot pointer, not a build"
        else "are snapshot pointers, not builds"
      s"${revisions.mkString(", ")} $kind. Run sbt zipxSnapshotStatus"
    case Unstored(pins) =>
      val lines = pins.map(pin => s"${pin.id} is not stored anywhere. Pin ${pin.storedId}")
      lines.mkString("\n")
end PinRefusal

object PinRefusal:
  def of(revisions: Seq[String]): List[PinRefusal] =
    val parsed   = revisions.distinct.toList.map(raw => raw -> DepRevision.of(raw))
    val pointers = parsed.collect { case (raw, DepRevision.Pointer(_)) => raw }
    val unstored = parsed.collect { case (_, DepRevision.UnstoredCommit(pin)) => pin }
    nonEmpty(pointers).map(Pointers(_)).toList ++ nonEmpty(unstored).map(Unstored(_)).toList

  private def nonEmpty[A](items: List[A]): Option[::[A]] = items match
    case head :: tail => Some(::(head, tail))
    case Nil          => None
end PinRefusal
