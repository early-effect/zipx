package zipx.workflow

import scala.collection.immutable.ListMap

/** A generated GitHub Actions composite action (`action.yml`).
  *
  * Same contract as a generated workflow: zipx owns the bytes, `zipxWorkflowCheck` diffs them, and consumers do not
  * hand-edit. Nested third-party `uses:` stay SHA-pinned via the consumer's action-pins file.
  */
final case class CompositeAction(
    name: String,
    description: String,
    inputs: ListMap[String, CompositeInput] = ListMap.empty,
    outputs: ListMap[String, CompositeOutput] = ListMap.empty,
    steps: List[Step],
)

final case class CompositeInput(
    description: String,
    required: Boolean = false,
    default: Option[String] = None,
)

final case class CompositeOutput(
    description: String,
    value: String,
)
