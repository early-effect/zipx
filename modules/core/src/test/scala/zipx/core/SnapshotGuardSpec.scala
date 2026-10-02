package zipx.core

import zio.test.*

object SnapshotGuardSpec extends ZIOSpecDefault:

  private val libs = ShipGroup("libs", "1.0.0")("models")
  private val side = Ship("side", "0.2.0")

  private def sentence(verdict: SnapshotVerdict): String =
    (verdict.refusals ++ verdict.warnings ++ verdict.hints).mkString("\n")

  def spec = suite("SnapshotGuard")(
    test("Fail refuses a changed, untagged, or unreadable row and names the bump command") {
      val changed    = SnapshotGuard.decide(List(libs -> ReleasedDrift.Changed("v1.0.0")), DriftGate.Fail)
      val untagged   = SnapshotGuard.decide(List(libs -> ReleasedDrift.Untagged("libs/v1.0.0")), DriftGate.Fail)
      val unreadable = SnapshotGuard.decide(List(libs -> ReleasedDrift.Unreadable("git diff failed")), DriftGate.Fail)
      assertTrue(
        changed.refusals == List(SnapshotGuard.shadowed(libs, "v1.0.0")),
        changed.warnings.isEmpty,
        sentence(changed).contains(SnapshotGuard.BumpCommand),
        sentence(changed).contains("shadowed"),
        untagged.refusals == List(SnapshotGuard.untagged(libs, "libs/v1.0.0")),
        unreadable.refusals == List(SnapshotGuard.unreadable(libs, "git diff failed")),
        !sentence(unreadable).contains("shadowed"),
      )
    },
    test("Warn uses the same sentence and does not refuse") {
      val verdict = SnapshotGuard.decide(List(libs -> ReleasedDrift.Changed("v1.0.0")), DriftGate.Warn)
      assertTrue(
        verdict.refusals.isEmpty,
        verdict.warnings == List(SnapshotGuard.shadowed(libs, "v1.0.0")),
      )
    },
    test("an unchanged row is a hint under both gates") {
      val fail = SnapshotGuard.decide(List(libs -> ReleasedDrift.Unchanged("v1.0.0")), DriftGate.Fail)
      val warn = SnapshotGuard.decide(List(libs -> ReleasedDrift.Unchanged("v1.0.0")), DriftGate.Warn)
      assertTrue(
        !fail.refuses,
        fail.hints == List(SnapshotGuard.hint(libs, "v1.0.0")),
        warn == fail,
      )
    },
    test("a mix names only the rows that are not clean, in description order") {
      val verdict = SnapshotGuard.decide(
        List(side -> ReleasedDrift.Changed("side/v0.2.0"), libs -> ReleasedDrift.Unchanged("libs/v1.0.0")),
        DriftGate.Fail,
      )
      assertTrue(
        verdict.refusals == List(SnapshotGuard.shadowed(side, "side/v0.2.0")),
        verdict.hints == List(SnapshotGuard.hint(libs, "libs/v1.0.0")),
        verdict.warnings.isEmpty,
      )
    },
  )
end SnapshotGuardSpec
