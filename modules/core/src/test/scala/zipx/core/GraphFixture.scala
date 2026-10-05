package zipx.core

/** Throws on a cycle: in a node list a test wrote, a cycle is a bug in the test, not user input. Test scope only, so
  * `src/main` keeps [[ModuleGraph.make]] as its one checked constructor.
  */
object GraphFixture:

  def apply(nodes: List[ModuleNode]): ModuleGraph =
    ModuleGraph.make(nodes) match
      case Right(graph) => graph
      case Left(error)  => throw AssertionError(s"test fixture is not a valid module graph: $error")

  val empty: ModuleGraph = apply(Nil)

end GraphFixture
