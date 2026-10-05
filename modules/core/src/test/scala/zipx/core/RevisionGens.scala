package zipx.core

import zio.test.Gen

/** Release lines, shas, and the revisions built from them. */
object RevisionGens:

  val line: Gen[Any, ReleaseVersion] =
    (for
      major <- Gen.int(0, 5)
      minor <- Gen.int(0, 20)
      patch <- Gen.int(0, 9)
    yield ReleaseVersion.make(s"$major.$minor.$patch")).collect { case Right(line) => line }

  val sha: Gen[Any, GitSha] =
    Gen.listOfN(40)(Gen.elements("0123456789abcdef".toList*)).map(_.mkString).map(GitSha.make).collect {
      case Right(sha) => sha
    }

  val stamp: Gen[Any, DirtyStamp] =
    (for
      month  <- Gen.int(1, 12)
      day    <- Gen.int(1, 28)
      hour   <- Gen.int(0, 23)
      minute <- Gen.int(0, 59)
    yield DirtyStamp.from(f"2026$month%02d$day%02d-$hour%02d$minute%02d")).collect { case Right(stamp) => stamp }

  def commitOn(line: ReleaseVersion): Gen[Any, SnapshotRevision.Commit] =
    sha.map(SnapshotRevision.commit(line, _))

  val commit: Gen[Any, SnapshotRevision.Commit] = line.flatMap(commitOn)

  /** The same commit as a parse returns it: line and abbreviation, no full sha. */
  def parsed(commit: SnapshotRevision.Commit): SnapshotRevision.Commit =
    SnapshotRevision.Commit(commit.line, commit.abbrev, None)

  /** A revision that names `line`: its release, a stored commit, or its pointer. */
  def revisionOn(line: ReleaseVersion): Gen[Any, DepRevision] =
    Gen.oneOf(
      Gen.const(DepRevision.Release(line)),
      commitOn(line).map(pin => DepRevision.Commit(parsed(pin))),
      Gen.const(DepRevision.Pointer(line)),
    )

  /** Every kind [[DepRevision.of]] reads, as it reads them. */
  val revision: Gen[Any, DepRevision] =
    Gen.oneOf(
      line.map(DepRevision.Release(_)),
      commit.map(pin => DepRevision.Commit(parsed(pin))),
      commit.map(pin => DepRevision.UnstoredCommit(parsed(pin))),
      line.map(DepRevision.Pointer(_)),
      (line <*> sha <*> stamp).map((l, s, t) => DepRevision.Local(SnapshotRevision.dirty(l, s, t))),
      stamp.map(t => DepRevision.Local(SnapshotRevision.noGit(t))),
      Gen.elements("1.0.0-RC1-SNAPSHOT", "2.1.0-M3-SNAPSHOT").map(DepRevision.Changing(_)),
      Gen.elements("2.1.25-M26", "1.0.0-RC1", "1.2.3.4").map(DepRevision.Other(_)),
    )

  /** A catalog row for `com.example:widgets` at `version`. */
  def row(version: String): Gen[Any, Lib] =
    Gen.const(DepVersion.make(version)).collect { case Right(v) =>
      Lib(GroupId("com.example"), ArtifactId("widgets"), v, Cross.Binary, None, Nil, None, None)
    }
end RevisionGens
