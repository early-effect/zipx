package zipx.core

import zio.test.*

object SnapshotPlanSpec extends ZIOSpecDefault:

  private val full    = GitSha("1234abcd56780123456789abcdef0123456789ab")
  private val newer   = GitSha("9876fedcba09876543210fedcba9876543210abc")
  private val line    = ReleaseVersion("1.4.2")
  private val widgets = "1.4.2-1234abcd5678"

  private def commit(raw: String): Either[SnapshotRevisionError, SnapshotRevision.Commit] =
    SnapshotRevision.parse(raw).flatMap {
      case pin: SnapshotRevision.Commit => Right(pin)
      case other                        => Left(SnapshotRevisionError.NotCommitPin(other.id))
    }

  def spec = suite("SnapshotPlan")(
    test("status names a newer sha and does not claim the pin moved") {
      val text =
        SnapshotStatus.report("widgets", widgets, Some(newer), artifactPresent = true).map(SnapshotStatus.render)
      assertTrue(
        text == Right(
          s"""widgets $widgets
             |  commit 1234abcd5678
             |  latest snapshot of 1.4.2 is 1.4.2-9876fedcba09
             |  run: sbt 'zipxSnapshotAdvance widgets'""".stripMargin
        )
      )
    },
    test("status says when the pin is the pointer's sha") {
      val text =
        SnapshotStatus.report("widgets", widgets, Some(full), artifactPresent = true).map(SnapshotStatus.render)
      assertTrue(text.toOption.exists(_.contains("this is the latest snapshot of 1.4.2")))
    },
    test("a deleted snapshot names the 90 day window and advance") {
      val text = SnapshotStatus.report("widgets", widgets, None, artifactPresent = false).map(SnapshotStatus.render)
      assertTrue(
        text.toOption.exists(_.contains("no longer has 1.4.2-1234abcd5678 (snapshots are kept 90 days)")),
        text.toOption.exists(_.contains("zipxSnapshotAdvance widgets")),
      )
    },
    test("a dirty pin is a local build") {
      val text = SnapshotStatus
        .report("widgets", "1.4.2-1234abcd5678+20140707-1030", None, artifactPresent = false)
        .map(SnapshotStatus.render)
      assertTrue(
        text.toOption.exists(_.contains("local build")),
        text.toOption.exists(_.contains("will not resolve on another machine")),
      )
    },
    test("advance rewrites only the sha, and a dirty pin is refused") {
      assertTrue(
        PinRewrite.advance(widgets, newer) == Right(Some("1.4.2-9876fedcba09")),
        PinRewrite.advance(widgets, full) == Right(None),
        PinRewrite.advance("1.4.2-1234abcd5678+20140707-1030", newer).isLeft,
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
      val source = """val widgets = Lib("com.example", "widgets", "1.4.2-1234abcd5678")"""
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
            |  com.example:widgets is 1.4.2-1234abcd5678 (a snapshot: it is not the release number).
            |  A release POM cannot depend on a snapshot build. The snapshot repository deletes it.
            |
            |  1. Release widgets 1.4.2 from the build that publishes it. One deployment.
            |  2. Here: sbt 'zipxPinRelease widgets'
            |     That rewrites the pin from 1.4.2-1234abcd5678 to 1.4.2. Same number.
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
      val pin = Lib("com.example", "widgets", "1.4.2-1234abcd5678")
      assertTrue(
        ZipxCatalog.outdated(List(pin), _ => Right(Some("1.4.3"))) == Right(Nil),
        SnapshotPinAdvice.message("widgets", widgets, Some("1.4.3")).exists(_.contains("zipxPinRelease widgets")),
        SnapshotPinAdvice.message("widgets", widgets, None).exists(_.contains("stays until 1.4.2")),
        SnapshotPinAdvice
          .message("widgets", "1.4.2-SNAPSHOT", Some("1.4.2"))
          .exists(_.contains("zipxPinRelease widgets")),
      )
    },
    test("a release refuses a commit pin") {
      assertTrue(
        ReleasePlan.refuseSnapshots(List(s"com.example:widgets:$widgets")).isLeft
      )
    },
  )
end SnapshotPlanSpec
