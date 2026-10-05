package zipx.core

import zio.test.*

import RevisionGens.{commit as commitGen, line as lineGen, parsed, sha as shaGen, stamp as stampGen}

object DepRevisionSpec extends ZIOSpecDefault:

  def spec = suite("DepRevision")(
    test("a stored commit reads back as that commit, and its bare id as the same commit unstored") {
      check(commitGen) { commit =>
        assertTrue(
          DepRevision.of(commit.storedId) == DepRevision.Commit(parsed(commit)),
          DepRevision.of(commit.id) == DepRevision.UnstoredCommit(parsed(commit)),
        )
      }
    },
    test("<line>-SNAPSHOT is the pointer and a bare line is a release, never a commit") {
      check(lineGen) { line =>
        assertTrue(
          DepRevision.of(SnapshotPointer.pointerVersion(line)) == DepRevision.Pointer(line),
          DepRevision.of(line) == DepRevision.Release(line),
        )
      }
    },
    test("dirty and git-less ids are local builds") {
      check(lineGen, shaGen, stampGen) { (line, sha, stamp) =>
        val dirty = SnapshotRevision.dirty(line, sha, stamp)
        val noGit = SnapshotRevision.noGit(stamp)
        assertTrue(
          DepRevision.of(dirty.id) == DepRevision.Local(dirty),
          DepRevision.of(noGit.id) == DepRevision.Local(noGit),
        )
      }
    },
    test("a qualifier snapshot is changing, and a milestone is neither a snapshot nor a release number") {
      assertTrue(
        DepRevision.of("1.0.0-RC1-SNAPSHOT") == DepRevision.Changing("1.0.0-RC1-SNAPSHOT"),
        DepRevision.of("2.1.25-M26") == DepRevision.Other("2.1.25-M26"),
      )
    },
    test("a revision renders as it was written, and reads back to itself") {
      check(RevisionGens.revision)(revision => assertTrue(DepRevision.of(revision.render) == revision))
    },
    test("only a release number or another published version may appear in a release POM") {
      check(commitGen, lineGen) { (commit, line) =>
        assertTrue(
          !DepRevision.Release(line).blocksRelease,
          !DepRevision.Other("2.1.25-M26").blocksRelease,
          DepRevision.Commit(commit).blocksRelease,
          DepRevision.UnstoredCommit(commit).blocksRelease,
          DepRevision.Pointer(line).blocksRelease,
          DepRevision.Changing("1.0.0-RC1-SNAPSHOT").blocksRelease,
        )
      }
    },
    test("the snapshot repository serves stored commits, the pointer, and changing snapshots") {
      check(commitGen, lineGen) { (commit, line) =>
        assertTrue(
          DepRevision.of(commit.storedId).resolvesFromSnapshots,
          DepRevision.Pointer(line).resolvesFromSnapshots,
          DepRevision.of("1.0.0-RC1-SNAPSHOT").resolvesFromSnapshots,
          !DepRevision.of(commit.id).resolvesFromSnapshots,
          !DepRevision.Release(line).resolvesFromSnapshots,
        )
      }
    },
    suite("PinRefusal")(
      test("update refuses the pointer and a bare commit id, naming what to do instead") {
        assertTrue(
          PinRefusal.of(Seq("1.4.2-SNAPSHOT")).map(_.message) ==
            List("1.4.2-SNAPSHOT is the snapshot pointer, not a build. Run sbt zipxSnapshotStatus"),
          PinRefusal.of(Seq("1.4.2-SNAPSHOT", "0.3.0-SNAPSHOT", "1.4.2-SNAPSHOT")).map(_.message) ==
            List("1.4.2-SNAPSHOT, 0.3.0-SNAPSHOT are snapshot pointers, not builds. Run sbt zipxSnapshotStatus"),
          PinRefusal.of(Seq("0.9.0-87b1886fa165")).map(_.message) ==
            List("0.9.0-87b1886fa165 is not stored anywhere. Pin 0.9.0-87b1886fa165-SNAPSHOT"),
        )
      },
      test("a release, a stored commit, and a changing snapshot pass") {
        check(commitGen, lineGen) { (commit, line) =>
          assertTrue(PinRefusal.of(Seq(line, commit.storedId, "1.0.0-RC1-SNAPSHOT")).isEmpty)
        }
      },
    ),
  )
end DepRevisionSpec
