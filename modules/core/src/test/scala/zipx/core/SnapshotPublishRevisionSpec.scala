package zipx.core

import zio.test.*

object SnapshotPublishRevisionSpec extends ZIOSpecDefault:

  private val fullRaw           = "1234abcd56780123456789abcdef0123456789ab"
  private val stampRaw          = "20140707-1030"
  private val row: PublishedRow = Ship("models", "1.4.2")
  private val fileReg           = ArtifactRegistry.Url("file:///tmp/zipx-repo")
  private val shaProps          = Map(SnapshotPublishRevision.ShaProperty -> fullRaw)

  private def published(session: BuildSession, props: Map[String, String], registry: ArtifactRegistry = fileReg) =
    session.artifactVersion(row, registry, props)

  def spec = suite("SnapshotPublishRevision")(
    test("development compiles <line>-ci and a release session publishes the catalog number") {
      assertTrue(
        published(BuildSession.Development, shaProps) == Right("1.4.2-ci"),
        published(BuildSession.Release, Map.empty) == Right("1.4.2"),
      )
    },
    test("a clean commit publishes the bare id, and Central appends -SNAPSHOT") {
      val pr = PullRequestNumber.make(42).map(BuildSession.PullRequestSnapshot(_))
      assertTrue(
        published(BuildSession.SnapshotPublish, shaProps) == Right("1.4.2-1234abcd5678"),
        published(BuildSession.SnapshotPublish, shaProps, ArtifactRegistry.MavenCentral) == Right(
          "1.4.2-1234abcd5678-SNAPSHOT"
        ),
        pr.exists(session => published(session, shaProps) == Right("1.4.2-1234abcd5678")),
      )
    },
    test("a pointer publish is <line>-SNAPSHOT even when the sha is set") {
      val props = shaProps + (SnapshotPublishRevision.PointerProperty -> "true")
      assertTrue(
        published(BuildSession.SnapshotPublish, props) == Right("1.4.2-SNAPSHOT"),
        published(BuildSession.SnapshotPublish, props, ArtifactRegistry.MavenCentral) == Right("1.4.2-SNAPSHOT"),
      )
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
    test("a commit pin is immutable, including the Central suffix, and the pointer is not") {
      assertTrue(
        SnapshotPublishRevision.isImmutablePin("1.4.2-1234abcd5678"),
        SnapshotPublishRevision.isImmutablePin("1.4.2-1234abcd5678-SNAPSHOT"),
        !SnapshotPublishRevision.isImmutablePin("1.4.2-1234abcd5678+20140707-1030"),
        !SnapshotPublishRevision.isImmutablePin("1.4.2-SNAPSHOT"),
        !SnapshotPublishRevision.isImmutablePin("1.0.0-RC1-SNAPSHOT"),
        SnapshotPublishRevision.isPointer("1.4.2-SNAPSHOT"),
        !SnapshotPublishRevision.isPointer("1.4.2-1234abcd5678-SNAPSHOT"),
        !SnapshotPublishRevision.isPointer("1.0.0-RC1-SNAPSHOT"),
        !SnapshotPublishRevision.isPointer("1.4.2"),
      )
    },
    test("refusing the pointer names zipxSnapshotStatus") {
      assertTrue(
        SnapshotPublishRevision.pointerRefusal(Seq("1.4.2-SNAPSHOT")) ==
          "1.4.2-SNAPSHOT is the snapshot pointer, not a build. Run sbt zipxSnapshotStatus",
        SnapshotPublishRevision.pointerRefusal(Seq("1.4.2-SNAPSHOT", "0.3.0-SNAPSHOT")) ==
          "1.4.2-SNAPSHOT, 0.3.0-SNAPSHOT are snapshot pointers, not builds. Run sbt zipxSnapshotStatus",
      )
    },
  )
end SnapshotPublishRevisionSpec
