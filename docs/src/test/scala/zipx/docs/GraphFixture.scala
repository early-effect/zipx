package zipx.docs

import zipx.core.ModuleGraph
import zipx.core.ModuleNode

/** A [[ModuleGraph]] from a node list written on a docs page, where a cycle would be a bug in the page.
  *
  * `zipx.core` offers only `ModuleGraph.make`, which returns an `Either`, because a real graph comes from an sbt build
  * and a cycle in one is user input. A page's graph is a literal, so unwrapping here keeps the `Either` out of examples
  * whose source is rendered on the site. The same call [[DocsRender.yaml]] makes, for the same reason.
  */
object GraphFixture:

  def apply(nodes: List[ModuleNode]): ModuleGraph =
    ModuleGraph.make(nodes) match
      case Right(graph) => graph
      case Left(error)  => throw AssertionError(s"docs fixture is not a valid module graph: $error")

  val empty: ModuleGraph = apply(Nil)

end GraphFixture
