package zipx.core

/** A GitHub Actions reusable-workflow call (`jobs.<id>.uses` + `with:`).
  *
  * When a [[Capability]] sets [[Capability.workflowCall]], the planner emits a once-job that invokes another workflow
  * instead of running checkout / sbt steps. Used by the Specular Pages paved path ([[zipx.specular.ZipxDocs]]).
  */
final case class WorkflowCall(
    uses: String,
    withInputs: Map[String, String] = Map.empty,
)
