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
  ): Workflow =
    val setupJava = Step(
      name = Some("Setup JDK"),
      uses = Some(pins.setupJava),
      `with` = ListMap("distribution" -> "temurin", "java-version" -> javaVersion),
    )
    Workflow(
      name = "zipx pin snapshot",
      on = Triggers(push = Some(BranchFilter(branches = pushBranches))),
      permissions = ListMap("contents" -> "write"),
      jobs = ListMap(
        "pin-snapshot" -> Job(
          name = Some("Submit pin snapshot"),
          runsOn = List(runnerOs),
          steps = List(
            Step(uses = Some(pins.checkout)),
            setupJava,
            Step(uses = Some(pins.setupSbt)),
            Step(name = Some("Submit dependency snapshot"), run = Some("sbt zipxPinSubmit")),
          ),
        )
      ),
    )
  end plan

  def render(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      pushBranches: List[String],
  ): Either[String, String] =
    Render.render(plan(pins, javaVersion, runnerOs, pushBranches)).map(ActionPinFile.annotateUses(_, pins))
end PinSnapshotWorkflow
