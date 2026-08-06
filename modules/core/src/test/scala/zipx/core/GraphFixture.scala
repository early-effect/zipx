package zipx.core

/** A [[ModuleGraph]] from a node list written in a test, where a cycle is a bug in the test rather than user input.
  *
  * This exists in test scope so that `src/main` has no unchecked way to build a graph: [[ModuleGraph.make]] is the only
  * constructor a published API offers, and the sbt plugin reports its `Left` through `ZipxPlugin.orFail`. Throwing here
  * is the same call `DocsRender.yaml` makes: a fixture that cannot be planned should fail the suite loudly, at the
  * fixture, rather than be threaded through every assertion as an `Either`.
  */
object GraphFixture:

  def apply(nodes: List[ModuleNode]): ModuleGraph =
    ModuleGraph.make(nodes) match
      case Right(graph) => graph
      case Left(error)  => throw AssertionError(s"test fixture is not a valid module graph: $error")

  val empty: ModuleGraph = apply(Nil)

end GraphFixture
