package zipx.core

import zio.test.*

object ModuleGraphSpec extends ZIOSpecDefault:
  import Fixtures.*

  def spec = suite("ModuleGraph")(
    test("topological sort places dependencies before dependents") {
      val order = sampleGraph.topologicalSort
      def idx(id: String) = order.indexOf(id)
      assertTrue(
        idx("schema") < idx("api"),
        idx("api") < idx("clientA"),
        idx("api") < idx("clientB"),
        idx("schema") < idx("legacyClient"),
        idx("core") < idx("workerA"),
        order.toSet == sampleGraph.ids.toSet,
      )
    },
    test("topological sort is deterministic (stable across runs)") {
      assertTrue(sampleGraph.topologicalSort == sampleGraph.topologicalSort)
    },
    test("topological layers group independent modules; roots first") {
      val layers = sampleGraph.topologicalLayers
      // Layer 0 = modules with no in-graph deps: core, schema (sorted).
      assertTrue(
        layers.head == List("core", "schema"),
        // api and legacyClient (both need only schema) land in the same layer, after schema.
        layers.exists(l => l.contains("api") && l.contains("legacyClient")),
      )
    },
    test("transitive deps follow the chain") {
      assertTrue(
        sampleGraph.transitiveDeps("clientA") == Set("api", "schema"),
        sampleGraph.transitiveDeps("schema") == Set.empty,
      )
    },
    test("affected closure includes seeds and all transitive dependents") {
      // Changing schema affects everything downstream of it.
      val affected = sampleGraph.affectedClosure(Set("schema"))
      assertTrue(
        affected.contains("schema"),
        affected.contains("api"),
        affected.contains("clientA"),
        affected.contains("clientB"),
        affected.contains("legacyClient"),
        // core is not downstream of schema.
        !affected.contains("core"),
        !affected.contains("workerA"),
      )
    },
    test("affected closure of a leaf is just itself") {
      assertTrue(sampleGraph.affectedClosure(Set("clientA")) == Set("clientA"))
    },
    test("detects cycles") {
      val cyclic = ModuleGraph(List(ModuleNode("a", List("b")), ModuleNode("b", List("a"))))
      assertTrue(scala.util.Try(cyclic.topologicalSort).isFailure)
    },
    test("subsetLayers gives the contracted publish order (L0/L1/L2)") {
      // Publishers only, edges contracted through non-publishers.
      val layers = sampleGraph.subsetLayers(_.publishes)
      assertTrue(
        layers == List(
          List("schema"),                 // L0
          List("api", "legacyClient"),    // L1 (both need only schema)
          List("clientA", "clientB"),     // L2 (need api)
        ),
      )
    },
    test("subsetLayers contracts edges through excluded intermediates") {
      // a(inc) → b(excl) → c(inc): c's nearest included ancestor is a, so a before c despite b between them.
      val g = ModuleGraph(
        List(
          ModuleNode("a"),
          ModuleNode("b", dependsOn = List("a")),
          ModuleNode("c", dependsOn = List("b")),
        ),
      )
      assertTrue(g.subsetLayers(n => n.id == "a" || n.id == "c") == List(List("a"), List("c")))
    },
  )
end ModuleGraphSpec
