package zipx.core

import zipx.workflow.Expr
import zio.test.*

object TestAffectedSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val full = SbtCommand.unsafeTask("testFull")

  /** The sample graph plus the root aggregator a real build has, which the plugin marks CI-irrelevant. */
  private val graph = GraphFixture(
    ModuleNode(ModuleId("root"), ciRelevant = false) :: sampleGraph.nodes
  )

  private val ids        = graph.topologicalSort
  private val relevant   = graph.nodes.filter(_.ciRelevant).map(n => n.id: String).toSet
  private val testTaskOf = graph.nodes.map(n => (n.id: String) -> (n.testTask.text: String)).toMap
  private val genSubset  = Gen.setOf(Gen.elements(ids*))

  private def plan(aggregated: Set[String], affected: List[String]): TestAffected.Run =
    TestAffected.plan(full, graph, aggregated.map(ModuleId.unsafeMake), affected)

  /** The session text and modules a narrowed run tests; `None` when it tested everything. */
  private def narrowed(run: TestAffected.Run): Option[(String, List[String])] = run match
    case TestAffected.Run.Everything(_)             => None
    case TestAffected.Run.Nothing                   => Some("" -> Nil)
    case TestAffected.Run.Modules(command, modules) => Some((command.text: String) -> modules.map(id => id: String))

  def spec = suite("TestAffected")(
    test("when the diff cannot narrow anything, it runs exactly the full command") {
      check(genSubset)(agg => assertTrue(plan(agg, Affected.AllSentinel) == TestAffected.Run.Everything(full)))
    },
    test(
      "it tests each affected CI-relevant module the root aggregate reaches, in dependency order, and nothing else"
    ) {
      check(genSubset, genSubset) { (agg, affected) =>
        val expected = ids.filter(id => relevant.contains(id) && agg.contains(id) && affected.contains(id))
        val session  = expected.map(id => s"$id/${testTaskOf(id)}").mkString("; ")
        assertTrue(narrowed(plan(agg, affected.toList)).contains(session -> expected))
      }
    },
    test("an affected aggregator is not tested, since its test task would rerun every module it aggregates") {
      assertTrue(narrowed(plan(Set("root", "api"), List("root", "api"))).contains("api/test" -> List("api")))
    },
    test("an affected module outside the root aggregate is not tested") {
      assertTrue(narrowed(plan(Set("api"), List("api", "schema"))).contains("api/test" -> List("api")))
    },
    test("the job passes its base as the argument, for GitHub to substitute before the shell runs") {
      val base = Expr.github("event.pull_request.base.sha")
      assertTrue(
        TestAffected.command(base).render.render == "sbt 'zipxTestAffected ${{ github.event.pull_request.base.sha }}'"
      )
    },
  )
end TestAffectedSpec
