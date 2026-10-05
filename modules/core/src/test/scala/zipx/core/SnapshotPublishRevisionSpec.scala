package zipx.core

import zio.test.*

object SnapshotPublishRevisionSpec extends ZIOSpecDefault:

  private val fullRaw           = "1234abcd56780123456789abcdef0123456789ab"
  private val stampRaw          = "20140707-1030"
  private val row: PublishedRow = Ship("models", "1.4.2")
  private val shaProps          = Map(SnapshotPublishRevision.ShaProperty -> fullRaw)

  private def published(session: BuildSession, props: Map[String, String]) =
    session.artifactVersion(row, props)

  def spec = suite("SnapshotPublishRevision")(
    test("development compiles <line>-ci and a release session publishes the catalog number") {
      assertTrue(
        published(BuildSession.Development, shaProps) == Right("1.4.2-ci"),
        published(BuildSession.Release, Map.empty) == Right("1.4.2"),
      )
    },
    test("a clean commit publishes <id>-SNAPSHOT, the coordinate every registry stores, from main or a PR") {
      val pr = PullRequestNumber.make(42).map(BuildSession.PullRequestSnapshot(_))
      assertTrue(
        published(BuildSession.SnapshotPublish, shaProps) == Right("1.4.2-1234abcd5678-SNAPSHOT"),
        pr.exists(session => published(session, shaProps) == Right("1.4.2-1234abcd5678-SNAPSHOT")),
      )
    },
    test("a local publish of a clean commit stores the same coordinate, so one pin resolves from either") {
      val local = shaProps + (SnapshotPublishRevision.LocalProperty -> "true")
      assertTrue(
        published(BuildSession.SnapshotPublish, local) == published(BuildSession.SnapshotPublish, shaProps)
      )
    },
    test("a pointer publish is <line>-SNAPSHOT even when the sha is set") {
      val props = shaProps + (SnapshotPublishRevision.PointerProperty -> "true")
      assertTrue(published(BuildSession.SnapshotPublish, props) == Right("1.4.2-SNAPSHOT"))
    },
    test("local publish keeps the dirty id, and a registry publish refuses it") {
      val dirty = shaProps + (SnapshotPublishRevision.DirtyProperty -> stampRaw)
      val local = dirty + (SnapshotPublishRevision.LocalProperty    -> "true")
      val id    = "1.4.2-1234abcd5678+20140707-1030"
      assertTrue(
        published(BuildSession.SnapshotPublish, local) == Right(id),
        published(BuildSession.SnapshotPublish, dirty) == Left(SnapshotRevisionError.Unstable(id)),
      )
    },
    test("a git-less local publish is HEAD+stamp, and a registry publish refuses it") {
      val props = Map(SnapshotPublishRevision.DirtyProperty -> stampRaw)
      val local = props + (SnapshotPublishRevision.LocalProperty -> "true")
      assertTrue(
        published(BuildSession.SnapshotPublish, local) == Right(s"HEAD+$stampRaw"),
        published(BuildSession.SnapshotPublish, props) == Left(SnapshotRevisionError.Unstable(s"HEAD+$stampRaw")),
        published(BuildSession.SnapshotPublish, Map.empty) == Left(
          SnapshotRevisionError.NotCommitPin(SnapshotPublishRevision.ShaProperty)
        ),
      )
    },
  )
end SnapshotPublishRevisionSpec
