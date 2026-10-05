package zipx.core

import zio.test.*

object SnapshotRevisionSpec extends ZIOSpecDefault:

  private val fullRaw  = "1234abcd56780123456789abcdef0123456789ab"
  private val lineRaw  = "1.4.2"
  private val stampRaw = "20140707-1030"

  private val hexChar: Gen[Any, Char] =
    Gen.elements("0123456789abcdef".toList*)

  private val lineGen: Gen[Any, ReleaseVersion] =
    (for
      major <- Gen.int(0, 5)
      minor <- Gen.int(0, 9)
      patch <- Gen.int(0, 9)
    yield ReleaseVersion.make(s"$major.$minor.$patch")).collect { case Right(line) => line }

  private val stampGen: Gen[Any, DirtyStamp] =
    (for
      month  <- Gen.int(1, 12)
      day    <- Gen.int(1, 28)
      hour   <- Gen.int(0, 23)
      minute <- Gen.int(0, 59)
    yield DirtyStamp.from(f"2026$month%02d$day%02d-$hour%02d$minute%02d")).collect { case Right(stamp) => stamp }

  def spec = suite("SnapshotRevision")(
    test("a clean commit renders the 12-character id, keeps the full sha, and is stored as the id plus -SNAPSHOT") {
      built match
        case Some(line, full) =>
          val rev = SnapshotRevision.commit(line, full)
          assertTrue(
            rev.id == "1.4.2-1234abcd5678",
            rev.full.contains(full),
            rev.stable,
            SnapshotRevision.parse(rev.id) == Right(SnapshotRevision.Commit(line, rev.abbrev, None)),
            rev.storedId == "1.4.2-1234abcd5678-SNAPSHOT",
            rev.stored == Right(rev.storedId),
          )
        case None =>
          assertTrue(false)
    },
    test("a dirty tree renders dynver's timestamp mark and is not a registry revision") {
      (built, DirtyStamp.from(stampRaw)) match
        case (Some(line, full), Right(stamp)) =>
          val rev = SnapshotRevision.dirty(line, full, stamp)
          assertTrue(
            rev.id == "1.4.2-1234abcd5678+20140707-1030",
            !rev.stable,
            SnapshotRevision.parse(rev.id) == Right(rev),
            rev.stored == Left(SnapshotRevisionError.Unstable(rev.id)),
          )
        case _ =>
          assertTrue(false)
    },
    test("a tree with no git renders HEAD plus the dirty timestamp") {
      DirtyStamp.from(stampRaw) match
        case Right(stamp) =>
          val rev = SnapshotRevision.noGit(stamp)
          assertTrue(
            rev.id == "HEAD+20140707-1030",
            !rev.stable,
            SnapshotRevision.parse(rev.id) == Right(rev),
            rev.stored == Left(SnapshotRevisionError.Unstable(rev.id)),
          )
        case Left(_) =>
          assertTrue(false)
    },
    test("a floating -SNAPSHOT, a short sha, and a timestamp without + are not commit pins") {
      assertTrue(
        SnapshotRevision.parse("1.4.2-SNAPSHOT") == Left(SnapshotRevisionError.NotCommitPin("1.4.2-SNAPSHOT")),
        SnapshotRevision.parse("1.4.2-1234567") == Left(SnapshotRevisionError.ShaTooShort("1234567")),
        SnapshotRevision.parse("1.4.2-1234abcd5678-20140707-1030") == Left(
          SnapshotRevisionError.DirtyMarkMissing("1.4.2-1234abcd5678-20140707-1030")
        ),
        SnapshotRevision.parse("1.4.2-1234abcd5678+20141307-9999") == Left(
          SnapshotRevisionError.BadStamp("20141307-9999")
        ),
      )
    },
    test("a rendered commit id parses back to the same line and abbreviation, and still holds its full sha") {
      check(lineGen, Gen.listOfN(40)(hexChar), stampGen) { (line, chars, stamp) =>
        GitSha.make(chars.mkString) match
          case Right(full) =>
            val commit = SnapshotRevision.commit(line, full)
            val dirty  = SnapshotRevision.dirty(line, full, stamp)
            val bare   = SnapshotRevision.noGit(stamp)
            assertTrue(
              SnapshotRevision.parse(commit.id) == Right(SnapshotRevision.Commit(line, commit.abbrev, None)),
              commit.full.contains(full),
              SnapshotRevision.parse(dirty.id) == Right(dirty),
              SnapshotRevision.parse(bare.id) == Right(bare),
              commit.stored == Right(s"${commit.id}-SNAPSHOT"),
              dirty.stored.isLeft,
              bare.stored.isLeft,
            )
          case Left(_) =>
            assertTrue(false)
      }
    },
  )

  private def built: Option[(ReleaseVersion, GitSha)] =
    (ReleaseVersion.make(lineRaw), GitSha.make(fullRaw)) match
      case (Right(line), Right(full)) => Some((line, full))
      case _                          => None
end SnapshotRevisionSpec
