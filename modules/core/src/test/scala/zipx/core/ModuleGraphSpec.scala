package zipx.core

import zio.test.*

object ModuleGraphSpec extends ZIOSpecDefault:
  import Fixtures.*

  def spec = suite("ModuleGraph")(
    test("topological sort places dependencies before dependents") {
      val order           = sampleGraph.topologicalSort
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
      assertTrue(
        layers.head == List("core", "schema"),
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
      val affected = sampleGraph.affectedClosure(Set("schema"))
      assertTrue(
        affected.contains("schema"),
        affected.contains("api"),
        affected.contains("clientA"),
        affected.contains("clientB"),
        affected.contains("legacyClient"),
        !affected.contains("core"),
        !affected.contains("workerA"),
      )
    },
    test("affected closure of a leaf is just itself") {
      assertTrue(sampleGraph.affectedClosure(Set("clientA")) == Set("clientA"))
    },
    test("make rejects a cycle, naming the modules involved") {
      val cyclic = ModuleGraph.make(List(ModuleNode("a", List("b")), ModuleNode("b", List("a"))))
      assertTrue(
        cyclic.isLeft,
        cyclic.swap.exists(_.contains("cycle")),
        cyclic.swap.exists(_.contains("a, b")),
      )
    },
    test("apply throws where make reports, for a fixture whose cycle would be a test bug") {
      assertTrue(
        scala.util.Try(ModuleGraph(List(ModuleNode("a", List("b")), ModuleNode("b", List("a"))))).isFailure
      )
    },
    test("subsetLayers gives the contracted publish order (L0/L1/L2)") {
      val layers = sampleGraph.subsetLayers(_.publishes)
      assertTrue(
        layers == List(
          List("schema"),
          List("api", "legacyClient"),
          List("clientA", "clientB"),
        )
      )
    },
    test("subsetLayers contracts edges through excluded intermediates") {
      val g = ModuleGraph(
        List(
          ModuleNode("a"),
          ModuleNode("b", dependsOn = List("a")),
          ModuleNode("c", dependsOn = List("b")),
        )
      )
      assertTrue(g.subsetLayers(n => n.id == "a" || n.id == "c") == List(List("a"), List("c")))
    },
    test("self-cycle is detected") {
      assertTrue(ModuleGraph.make(List(ModuleNode("a", dependsOn = List("a")))).isLeft)
    },
    test("three-node cycle is detected, and every id in it is reported") {
      val cyclic = ModuleGraph.make(
        List(
          ModuleNode("a", dependsOn = List("c")),
          ModuleNode("b", dependsOn = List("a")),
          ModuleNode("c", dependsOn = List("b")),
        )
      )
      assertTrue(cyclic.isLeft, cyclic.swap.exists(_.contains("a, b, c")))
    },
    test("cycle reports the ids without building a graph, for a caller wording its own error") {
      assertTrue(
        ModuleGraph.cycle(List(ModuleNode("a", List("b")), ModuleNode("b", List("a")))) == Some(List("a", "b")),
        ModuleGraph.cycle(List(ModuleNode("a"), ModuleNode("b", List("a")))).isEmpty,
      )
    },
    test("mapNodes rewrites attributes and keeps the layers") {
      val flagged = sampleGraph.mapNodes {
        case n if n.id == "api" => n.copy(docker = true)
        case n                  => n
      }
      assertTrue(
        flagged.get("api").exists(_.docker),
        !flagged.get("schema").exists(_.docker),
        flagged.topologicalLayers == sampleGraph.topologicalLayers,
        flagged.ids == sampleGraph.ids,
      )
    },
    test("mapNodes ignores edits to id and dependsOn, which is what makes it total") {
      // A structure-preserving map cannot invalidate the layers, so there is no cycle to report and no Either to unwrap.
      val rewired = sampleGraph.mapNodes(n => n.copy(id = "renamed", dependsOn = List("clientA")))
      assertTrue(
        rewired.ids == sampleGraph.ids,
        rewired.directDeps("clientA") == sampleGraph.directDeps("clientA"),
        rewired.topologicalSort == sampleGraph.topologicalSort,
      )
    },
    test("external dependsOn ids are dropped from directDeps") {
      val g = ModuleGraph(List(ModuleNode("a", dependsOn = List("outside", "b")), ModuleNode("b")))
      assertTrue(g.directDeps("a") == List("b"), g.transitiveDeps("a") == Set("b"))
    },
    test("affectedClosure ignores seed ids absent from the graph") {
      assertTrue(sampleGraph.affectedClosure(Set("nope", "schema")).contains("schema"))
      assertTrue(!sampleGraph.affectedClosure(Set("nope")).contains("nope"))
    },
    test("duplicate node ids: last definition wins in get; ids lists every occurrence") {
      val g = ModuleGraph(
        List(
          ModuleNode("a", publishes = false),
          ModuleNode("a", publishes = true),
        )
      )
      assertTrue(g.get("a").exists(_.publishes), g.ids == List("a", "a"))
    },
    test("empty graph sorts and layers to empty") {
      val g = ModuleGraph(Nil)
      assertTrue(g.topologicalSort == Nil, g.topologicalLayers == Nil, g.subsetLayers(_ => true) == Nil)
    },
    test("diamond publish contraction: two paths to the same publisher") {
      val g = ModuleGraph(
        List(
          ModuleNode("root", publishes = true),
          ModuleNode("midA", dependsOn = List("root")),
          ModuleNode("midB", dependsOn = List("root")),
          ModuleNode("leaf", dependsOn = List("midA", "midB"), publishes = true),
        )
      )
      assertTrue(g.subsetLayers(_.publishes) == List(List("root"), List("leaf")))
    },
  )
end ModuleGraphSpec
