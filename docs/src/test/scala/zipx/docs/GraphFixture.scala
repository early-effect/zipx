package zipx.docs

import zipx.core.ModuleGraph
import zipx.core.ModuleNode

/** Throws on a cycle: a page's graph is a literal, so a cycle is a bug in the page, and unwrapping here keeps the
  * `Either` out of examples whose source is rendered on the site.
  */
object GraphFixture:

  def apply(nodes: List[ModuleNode]): ModuleGraph =
    ModuleGraph.make(nodes) match
      case Right(graph) => graph
      case Left(error)  => throw AssertionError(s"docs fixture is not a valid module graph: $error")

  val empty: ModuleGraph = apply(Nil)

end GraphFixture
