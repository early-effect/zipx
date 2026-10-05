package zipx.core

import zio.test.*

object SnapshotPlanSpec extends ZIOSpecDefault:

  private val full    = GitSha("1234abcd56780123456789abcdef0123456789ab")
  private val newer   = GitSha("9876fedcba09876543210fedcba9876543210abc")
  private val line    = ReleaseVersion("1.4.2")
  private val widgets = "1.4.2-1234abcd5678-SNAPSHOT"
  private val bare    = "1.4.2-1234abcd5678"

  private def commit(raw: String): Either[SnapshotRevisionError, SnapshotRevision.Commit] =
    DepRevision.of(raw) match
      case DepRevision.Commit(pin) => Right(pin)
      case _                       => Left(SnapshotRevisionError.NotCommitPin(raw))

  def spec = suite("SnapshotPlan")(
    test("status names a newer sha and does not claim the pin moved") {
      val text =
        SnapshotStatus
          .report("widgets", widgets, PointerRead.Names(newer), artifactPresent = true)
          .map(SnapshotStatus.render)
      assertTrue(
        text == Right(
          s"""widgets $widgets
             |  commit 1234abcd5678
             |  latest snapshot of 1.4.2 is 1.4.2-9876fedcba09-SNAPSHOT
             |  run: sbt 'zipxSnapshotAdvance widgets'""".stripMargin
        )
      )
    },
    test("status says when the pin is the pointer's sha") {
      val text =
        SnapshotStatus
          .report("widgets", widgets, PointerRead.Names(full), artifactPresent = true)
          .map(SnapshotStatus.render)
      assertTrue(text.toOption.exists(_.contains("this is the latest snapshot of 1.4.2")))
    },
    test("a pointer with no commit, or no pointer, is reported for that row and the command carries on") {
      def render(pointer: PointerRead) =
        SnapshotStatus.report("widgets", widgets, pointer, artifactPresent = true).map(SnapshotStatus.render)
      assertTrue(
        render(PointerRead.Unnamed)
          .exists(_.contains("the 1.4.2-SNAPSHOT pointer names no commit (no zipx.snapshot.sha)")),
        render(PointerRead.Unnamed).exists(_.contains("sbt zipxSnapshotPublish")),
        render(PointerRead.Absent).exists(_.contains("has no 1.4.2-SNAPSHOT pointer, so the latest is unknown")),
      )
    },
    test("a pointer read stops at the first that names a commit, and only an absent one is absent") {
      val named = PointerRead.Names(full)
      assertTrue(
        PointerRead.first(Iterator(PointerRead.Absent, PointerRead.Unnamed, named)) == named,
        PointerRead.first(Iterator(PointerRead.Absent, PointerRead.Unnamed)) == PointerRead.Unnamed,
        PointerRead.first(Iterator(PointerRead.Absent)) == PointerRead.Absent,
        PointerRead.of(None) == PointerRead.Absent,
        PointerRead.of(Some("<project><version>1.4.2-SNAPSHOT</version></project>")) == PointerRead.Unnamed,
        PointerRead.of(
          Some(s"<project><properties><zipx.snapshot.sha>$full</zipx.snapshot.sha></properties></project>")
        ) ==
          named,
      )
    },
    test("a deleted snapshot names the 90 day window and advance") {
      val text =
        SnapshotStatus
          .report("widgets", widgets, PointerRead.Absent, artifactPresent = false)
          .map(SnapshotStatus.render)
      assertTrue(
        text.toOption.exists(_.contains("no longer has 1.4.2-1234abcd5678-SNAPSHOT (snapshots are kept 90 days)")),
        text.toOption.exists(_.contains("zipxSnapshotAdvance widgets")),
      )
    },
    test("a dirty pin is a local build") {
      val text = SnapshotStatus
        .report("widgets", "1.4.2-1234abcd5678+20140707-1030", PointerRead.Absent, artifactPresent = false)
        .map(SnapshotStatus.render)
      assertTrue(
        text.toOption.exists(_.contains("local build")),
        text.toOption.exists(_.contains("will not resolve on another machine")),
      )
    },
    test("advance rewrites only the sha, in the stored form, and a dirty pin is refused") {
      assertTrue(
        PinRewrite.advance(widgets, newer) == Right(Some("1.4.2-9876fedcba09-SNAPSHOT")),
        PinRewrite.advance(widgets, full) == Right(None),
        PinRewrite.advance("1.4.2-1234abcd5678+20140707-1030", newer).isLeft,
      )
    },
    test("advance repairs a bare pin, even at the sha it already names") {
      assertTrue(
        PinRewrite.advance(bare, full) == Right(Some(widgets)),
        PinRewrite.advance(bare, newer) == Right(Some("1.4.2-9876fedcba09-SNAPSHOT")),
      )
    },
    test("advance to a line moves a commit pin or a release row there, and refuses a dirty pin and the pointer") {
      val next = ReleaseVersion("1.5.0")
      assertTrue(
        PinRewrite.moveTo(widgets, next, newer) == Right(Some("1.5.0-9876fedcba09-SNAPSHOT")),
        PinRewrite.moveTo("1.4.2", next, newer) == Right(Some("1.5.0-9876fedcba09-SNAPSHOT")),
        PinRewrite.moveTo("1.5.0-9876fedcba09-SNAPSHOT", next, newer) == Right(None),
        PinRewrite.moveTo("1.4.2-1234abcd5678+20140707-1030", next, newer).isLeft,
        PinRewrite.moveTo("1.4.2-SNAPSHOT", next, newer).isLeft,
      )
    },
    test("pin release stays on the same line") {
      assertTrue(
        PinRewrite.pinRelease(widgets, lineReleased = true) == Right("1.4.2"),
        PinRewrite
          .pinRelease(widgets, lineReleased = false)
          .left
          .toOption
          .exists(_.contains("not on the release repository")),
        PinRewrite.pinRelease("1.4.2", lineReleased = true).left.toOption.exists(_.contains("already a release")),
        PinRewrite.pinRelease("1.4.2-1234abcd5678+20140707-1030", lineReleased = true).isLeft,
        PinRewrite.pinRelease("1.4.2-SNAPSHOT", lineReleased = true) == Right("1.4.2"),
      )
    },
    test("the catalog rewrite touches one Lib constructor") {
      val source = """val widgets = Lib("com.example", "widgets", "1.4.2-1234abcd5678-SNAPSHOT")"""
      assertTrue(
        PinRewrite.replace(source, "com.example", "widgets", widgets, "1.4.2") ==
          Right("""val widgets = Lib("com.example", "widgets", "1.4.2")""")
      )
    },
    test("release plan names the ship and the sha, and all refuses") {
      val text = commit(widgets).map { pin =>
        ReleaseReadiness.render(
          List(
            ShipGate(
              "client",
              ReleaseVersion("0.3.0"),
              List(ReleaseBlocker.CommitPin("com.example", "widgets", pin)),
              Nil,
            ),
            ShipGate("libs", line, Nil, Nil),
          ),
          "main",
          None,
        )
      }
      assertTrue(
        text == Right(
          """Not ready.
            |
            |client 0.3.0 cannot release.
            |  com.example:widgets is 1.4.2-1234abcd5678-SNAPSHOT (a snapshot: it is not the release number).
            |  A release POM cannot depend on a snapshot build. The snapshot repository deletes it.
            |
            |  1. Release widgets 1.4.2 from the build that publishes it. One deployment.
            |  2. Here: sbt 'zipxPinRelease widgets'
            |     That rewrites the pin from 1.4.2-1234abcd5678-SNAPSHOT to 1.4.2. Same number.
            |  3. sbt zipxReleasePlan
            |
            |libs 1.4.2 can release.
            |  gh workflow run zipx-release.yml --ref main -f ships=libs
            |  That is one registry deployment. client stays a snapshot.
            |
            |all refuses, because client is included.""".stripMargin
        )
      )
    },
    test("a clear plan prints the dispatch and the shadow sentence") {
      val text = ReleaseReadiness.render(List(ShipGate("libs", line, Nil, Nil)), "main", None)
      assertTrue(
        text.contains("Ready."),
        text.contains("gh workflow run zipx-release.yml --ref main -f ships=all"),
        text.contains(ReleaseReadiness.shadowSentences(List(line))),
        text.contains("next line is 1.4.3"),
      )
    },
    test("a dirty dependency is not something to release") {
      val dirty = ReleaseBlocker.LocalPin("com.example", "widgets", "1.4.2-1234abcd5678+20140707-1030")
      val text  = ReleaseReadiness.render(
        List(ShipGate("client", ReleaseVersion("0.3.0"), List(dirty), Nil)),
        "main",
        None,
      )
      assertTrue(
        text.contains("a local build"),
        text.contains("nothing to release it to"),
        text.contains("all refuses, because client is included."),
      )
    },
    test("an in-repo unreleased upstream rides along") {
      val text = ReleaseReadiness.render(
        List(ShipGate("client", ReleaseVersion("0.3.0"), Nil, List("libs"))),
        "main",
        None,
      )
      assertTrue(text.contains("libs is in this build and is not released, so it rides along."))
    },
    test("the pointer POM records the full sha") {
      val xml =
        "<project><properties><zipx.snapshot.sha>1234abcd56780123456789abcdef0123456789ab</zipx.snapshot.sha></properties></project>"
      assertTrue(SnapshotPointer.shaFromPom(xml) == Right(full))
    },
    test("a broken metadata cache is the xml and its checksum sidecars") {
      assertTrue(
        SnapshotPointer.deleteBroken(xmlExists = false, sha1Agrees = Some(true), md5Agrees = None),
        SnapshotPointer.deleteBroken(xmlExists = true, sha1Agrees = Some(false), md5Agrees = Some(true)),
        !SnapshotPointer.deleteBroken(xmlExists = true, sha1Agrees = Some(true), md5Agrees = None),
        !SnapshotPointer.deleteBroken(xmlExists = false, sha1Agrees = None, md5Agrees = None),
      )
    },
    test("dep update names pin release and does not take a newer line") {
      val pin = Lib("com.example", "widgets", "1.4.2-1234abcd5678-SNAPSHOT")
      assertTrue(
        ZipxCatalog
          .outdated(List(pin), FakeReleases.crossing, FakeReleases.of("widgets_3" -> List("1.4.2", "1.4.3")))
          .map(_.bumps) == Right(Nil),
        SnapshotPinAdvice.message("widgets", widgets, Some("1.4.3")).exists(_.contains("zipxPinRelease widgets")),
        SnapshotPinAdvice.message("widgets", widgets, None).exists(_.contains("stays until 1.4.2")),
        SnapshotPinAdvice
          .message("widgets", "1.4.2-SNAPSHOT", Some("1.4.2"))
          .exists(_.contains("zipxPinRelease widgets")),
      )
    },
    test("a release refuses a commit pin, stored or bare") {
      assertTrue(
        ReleasePlan.refuseSnapshots(List(s"com.example:widgets:$widgets")).isLeft,
        ReleasePlan.refuseSnapshots(List(s"com.example:widgets:$bare")).isLeft,
      )
    },
  )
end SnapshotPlanSpec
