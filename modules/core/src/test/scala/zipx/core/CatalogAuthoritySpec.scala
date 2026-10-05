package zipx.core

import zio.test.*

import RevisionGens.{commitOn, line as lineGen, parsed, revisionOn, row}

object CatalogAuthoritySpec extends ZIOSpecDefault:

  /** Stands in for the plugin's order, which only revisions with no zipx line consult. */
  private given RevisionOrder = (a, b) => a.compareTo(b)

  private val widgets = ResolvedModule("com.example", "widgets_3")
  private val library = ResolvedModule("com.example", "client_3")

  private def wanted(revision: DepRevision): Wanted = Wanted(revision, library)

  private val twoLines: Gen[Any, (ReleaseVersion, ReleaseVersion)] =
    (lineGen <*> lineGen).collect {
      case (a, b) if ReleaseVersion.ordering.lt(a, b) => (a, b)
      case (a, b) if ReleaseVersion.ordering.gt(a, b) => (b, a)
    }

  private def twoCommitsOn(line: ReleaseVersion): Gen[Any, (SnapshotRevision.Commit, SnapshotRevision.Commit)] =
    (commitOn(line) <*> commitOn(line)).collect { case (a, b) if a.abbrev != b.abbrev => (parsed(a), parsed(b)) }

  private val twoCommits = lineGen.flatMap(twoCommitsOn)

  private val precedence = suite("Precedence")(
    test("lines decide before anything else on them") {
      check(twoLines.flatMap((older, newer) => revisionOn(older) <*> revisionOn(newer))) { (older, newer) =>
        assertTrue(
          Precedence.of(newer, older) == Precedence.Newer,
          Precedence.of(older, newer) == Precedence.Older,
        )
      }
    },
    test("a release is newer than every commit and the pointer of its line") {
      check(lineGen.flatMap(line => commitOn(line).map(line -> _))) { (line, pin) =>
        val release = DepRevision.Release(line)
        assertTrue(
          Precedence.of(release, DepRevision.Commit(parsed(pin))) == Precedence.Newer,
          Precedence.of(DepRevision.Commit(parsed(pin)), release) == Precedence.Older,
          Precedence.of(release, DepRevision.Pointer(line)) == Precedence.Newer,
        )
      }
    },
    test("two commits of one line have no order, and one commit is the same however it is written") {
      check(twoCommits) { (a, b) =>
        assertTrue(
          Precedence.of(DepRevision.Commit(a), DepRevision.Commit(b)) == Precedence.Unordered,
          Precedence.of(DepRevision.Commit(a), DepRevision.UnstoredCommit(a)) == Precedence.Same,
        )
      }
    },
    test("every revision is the same as itself") {
      check(RevisionGens.revision)(revision => assertTrue(Precedence.of(revision, revision) == Precedence.Same))
    },
    test("revisions with no zipx line follow the order the plugin supplies") {
      assertTrue(
        Precedence.of(DepRevision.Release(ReleaseVersion("2.1.24")), DepRevision.Other("2.1.25-M26")) ==
          Precedence.Older,
        Precedence.of(DepRevision.Other("2.1.25-M26"), DepRevision.Release(ReleaseVersion("2.1.24"))) ==
          Precedence.Newer,
      )
    },
  )

  private val stale = suite("CatalogConflict.stale")(
    test("the catalog overrules every want that is older or has no order with it") {
      val gen = for
        (older, current) <- twoLines
        (stated, other)  <- twoCommitsOn(current)
        before           <- revisionOn(older)
        catalog          <- row(stated.storedId)
      yield (catalog, List(before, DepRevision.Commit(other), DepRevision.Commit(stated)))
      check(gen) { (catalog, wants) =>
        assertTrue(CatalogConflict.stale(widgets, catalog, wants.map(wanted)).isEmpty)
      }
    },
    test("a want above the catalog is stale, and the newest one is named") {
      val gen = for
        (current, newer) <- twoLines
        catalog          <- row(current)
        pin              <- commitOn(newer)
      yield (catalog, newer, pin)
      check(gen) { (catalog, newer, pin) =>
        val wants = List(DepRevision.Commit(parsed(pin)), DepRevision.Release(newer)).map(wanted)
        assertTrue(
          CatalogConflict.stale(widgets, catalog, wants) ==
            Some(CatalogConflict.Stale(widgets, catalog, wanted(DepRevision.Release(newer))))
        )
      }
    },
    test("each message names the command that moves the catalog") {
      def messageOf(stated: String, wants: DepRevision): Option[String] =
        DepVersion.make(stated).toOption.flatMap { version =>
          val catalog = Lib(GroupId("com.example"), ArtifactId("widgets"), version, Cross.Binary, None, Nil, None, None)
          CatalogConflict.stale(widgets, catalog, List(wanted(wants))).map(_.message)
        }
      assertTrue(
        messageOf("1.4.2", DepRevision.of("1.5.0")).contains(
          "com.example:widgets_3: the catalog pins 1.4.2, and com.example:client_3 needs 1.5.0. Update the catalog: sbt zipxDepUpdate, or pin 1.5.0"
        ),
        messageOf("1.4.2-1234abcd5678-SNAPSHOT", DepRevision.of("1.4.2"))
          .exists(_.endsWith("That line is released: sbt 'zipxPinRelease widgets'")),
        messageOf("1.4.2", DepRevision.of("1.5.0-9876fedcba09-SNAPSHOT"))
          .exists(_.endsWith("Move the pin to that line: sbt 'zipxSnapshotAdvance widgets 1.5.0'")),
        messageOf("1.4.2-1234abcd5678-SNAPSHOT", DepRevision.of("1.5.0-SNAPSHOT"))
          .exists(_.endsWith("Move the pin to that line: sbt 'zipxSnapshotAdvance widgets 1.5.0'")),
      )
    },
  )

  private val unpinned = suite("CatalogConflict.unpinned")(
    test("two commits of one line with nothing to order them are refused, both named") {
      check(twoCommits) { (a, b) =>
        val found = CatalogConflict.unpinned(widgets, List(DepRevision.Commit(a), DepRevision.UnstoredCommit(b)))
        assertTrue(
          found == Some(CatalogConflict.Unpinned(widgets, ::(a, List(b)))),
          found.exists(_.message.contains(s"${a.storedId} and ${b.storedId}, commits of ${a.line} with no order")),
        )
      }
    },
    test("one commit, however often and however written, and commits of different lines pass") {
      val gen = for
        (older, newer) <- twoLines
        a              <- commitOn(older)
        b              <- commitOn(newer)
      yield (parsed(a), parsed(b))
      check(gen) { (a, b) =>
        assertTrue(
          CatalogConflict.unpinned(widgets, List(DepRevision.Commit(a), DepRevision.UnstoredCommit(a))).isEmpty,
          CatalogConflict.unpinned(widgets, List(DepRevision.Commit(a), DepRevision.Commit(b))).isEmpty,
          CatalogConflict.unpinned(widgets, List(DepRevision.Commit(a), DepRevision.Release(a.line))).isEmpty,
        )
      }
    },
  )

  def spec = suite("CatalogAuthority")(precedence, stale, unpinned)
end CatalogAuthoritySpec
