package zipx.core

import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Default-branch snapshot submit. Emitted only when some feed sets `submitSnapshot`. */
object PinSnapshotWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-pin-snapshot.yml"

  def plan(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      pushBranches: List[String],
      sbtImage: Option[SbtImagePlan] = None,
  ): Workflow =
    SbtImageWorkflow.use(
      Workflow(
        name = "zipx pin snapshot",
        on = Triggers(push = Some(BranchFilter(branches = pushBranches))),
        permissions = ListMap("contents" -> "write"),
        jobs = ListMap(
          "pin-snapshot" -> Job(
            name = Some("Submit pin snapshot"),
            runsOn = List(runnerOs),
            env = EnvValue.renderAll(SnapshotPins.ciEnv(Map.empty)),
            steps = List(Step(uses = Some(pins.checkout))) ++
              SbtImageWorkflow.toolchain(pins, javaVersion, runnerOs, "pin-snapshot", sbtImage) ++
              List(Step(name = Some("Submit dependency snapshot"), run = Some("sbt zipxPinSubmit"))),
          )
        ),
      ),
      sbtImage,
      pins.checkout,
      runnerOs,
    )
  end plan

  def render(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      pushBranches: List[String],
      sbtImage: Option[SbtImagePlan] = None,
  ): Either[String, String] =
    Render
      .render(plan(pins, javaVersion, runnerOs, pushBranches, sbtImage))
      .map(ActionPinFile.annotateUses(_, pins))
end PinSnapshotWorkflow
