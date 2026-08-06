package zipx.core

import zipx.workflow.ActionRef

/** A GitHub Actions reusable-workflow call (`jobs.<id>.uses` + `with:`).
  *
  * When a [[Capability]] sets [[Capability.workflowCall]], the planner emits a once-job that invokes another workflow
  * instead of running checkout / sbt steps. Used by the Specular Pages paved path (`ZipxDocs`).
  *
  * `uses` is an [[zipx.workflow.ActionRef]] for the same reason `Step.uses` is: this is the third way a `uses:` value
  * reaches the YAML, and a reusable workflow needs its `@ref` exactly as much as an action does.
  */
final case class WorkflowCall(
    uses: ActionRef,
    withInputs: Map[String, String] = Map.empty,
)
