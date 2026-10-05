package zipx.core

import zipx.workflow.ActionRef

/** A reusable-workflow call (`jobs.<id>.uses` + `with:`), emitted as a once-job with no checkout or sbt steps. `uses`
  * is an [[zipx.workflow.ActionRef]] because a reusable workflow needs its `@ref` as much as an action does.
  */
final case class WorkflowCall(
    uses: ActionRef,
    withInputs: Map[String, String] = Map.empty,
)
