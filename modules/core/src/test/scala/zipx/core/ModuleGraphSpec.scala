package zipx.core

import zio.test.*

object ModuleGraphSpec extends ZIOSpecDefault:
  import Fixtures.*

  def spec = suite("ModuleGraph")(
    test("matrixRoot defaults to id so existing fixtures keep compiling") {
      val n = ModuleNode(ModuleId("schema"), publishes = true)
      assertTrue(n.matrixRoot == n.id, (n.matrixRoot: String) == "schema")
    },
    test("topological sort places dependencies before dependents") {
      val order           = sampleGraph.topologicalSort
      def idx(id: String) = order.indexOf(id)
      assertTrue(
        idx("schema") < idx("api"),
        idx("api") < idx("clientA"),
        idx("api") < idx("clientB"),
        idx("schema") < idx("legacyClient"),
        idx("core") < idx("batchA"),
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
        !affected.contains("batchA"),
      )
    },
    test("affected closure of a leaf is just itself") {
      assertTrue(sampleGraph.affectedClosure(Set("clientA")) == Set("clientA"))
    },
    test("make rejects a cycle, naming the modules involved") {
      val cyclic = ModuleGraph.make(List(ModuleNode(ModuleId("a"), List("b")), ModuleNode(ModuleId("b"), List("a"))))
      assertTrue(
        cyclic.isLeft,
        cyclic.swap.exists(_.contains("cycle")),
        cyclic.swap.exists(_.contains("a, b")),
      )
    },
    test("the test-scope fixture throws where make reports, so a bad fixture fails at the fixture") {
      // `make` is the only constructor `src/main` offers; `GraphFixture` is the test-scope helper that unwraps it, and a
      // cycle in a literal node list is a bug in the test rather than user input. Asserted so the helper cannot quietly
      // start returning some default graph instead.
      assertTrue(
        scala.util
          .Try(GraphFixture(List(ModuleNode(ModuleId("a"), List("b")), ModuleNode(ModuleId("b"), List("a")))))
          .isFailure
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
      val g = GraphFixture(
        List(
          ModuleNode(ModuleId("a")),
          ModuleNode(ModuleId("b"), dependsOn = List("a")),
          ModuleNode(ModuleId("c"), dependsOn = List("b")),
        )
      )
      assertTrue(g.subsetLayers(n => n.id == "a" || n.id == "c") == List(List("a"), List("c")))
    },
    test("self-cycle is detected") {
      assertTrue(ModuleGraph.make(List(ModuleNode(ModuleId("a"), dependsOn = List("a")))).isLeft)
    },
    test("three-node cycle is detected, and every id in it is reported") {
      val cyclic = ModuleGraph.make(
        List(
          ModuleNode(ModuleId("a"), dependsOn = List("c")),
          ModuleNode(ModuleId("b"), dependsOn = List("a")),
          ModuleNode(ModuleId("c"), dependsOn = List("b")),
        )
      )
      assertTrue(cyclic.isLeft, cyclic.swap.exists(_.contains("a, b, c")))
    },
    test("cycle reports the names without building a graph, for a caller wording its own error") {
      // Edges rather than nodes: the caller that needs this is ordering capabilities, whose names are not module ids.
      assertTrue(
        ModuleGraph.cycle(Map("a" -> List("b"), "b" -> List("a"))) == Some(List("a", "b")),
        ModuleGraph.cycle(Map("a" -> Nil, "b" -> List("a"))).isEmpty,
      )
    },
    test("cycle ignores a dependency on a name it has no edges for, as an external dep is ignored") {
      assertTrue(ModuleGraph.cycle(Map("a" -> List("absent"))).isEmpty)
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
      val rewired = sampleGraph.mapNodes(n => n.copy(id = ModuleId("renamed"), dependsOn = List("clientA")))
      assertTrue(
        rewired.ids == sampleGraph.ids,
        rewired.directDeps("clientA") == sampleGraph.directDeps("clientA"),
        rewired.topologicalSort == sampleGraph.topologicalSort,
      )
    },
    test("external dependsOn ids are dropped from directDeps") {
      val g = GraphFixture(List(ModuleNode(ModuleId("a"), dependsOn = List("outside", "b")), ModuleNode(ModuleId("b"))))
      assertTrue(g.directDeps("a") == List("b"), g.transitiveDeps("a") == Set("b"))
    },
    test("affectedClosure ignores seed ids absent from the graph") {
      assertTrue(sampleGraph.affectedClosure(Set("nope", "schema")).contains("schema"))
      assertTrue(!sampleGraph.affectedClosure(Set("nope")).contains("nope"))
    },
    test("duplicate node ids: last definition wins in get; ids lists every occurrence") {
      val g = GraphFixture(
        List(
          ModuleNode(ModuleId("a"), publishes = false),
          ModuleNode(ModuleId("a"), publishes = true),
        )
      )
      assertTrue(g.get("a").exists(_.publishes), g.ids == List("a", "a"))
    },
    test("empty graph sorts and layers to empty") {
      val g = GraphFixture(Nil)
      assertTrue(g.topologicalSort == Nil, g.topologicalLayers == Nil, g.subsetLayers(_ => true) == Nil)
    },
    test("diamond publish contraction: two paths to the same publisher") {
      val g = GraphFixture(
        List(
          ModuleNode(ModuleId("root"), publishes = true),
          ModuleNode(ModuleId("cedarA"), dependsOn = List("root")),
          ModuleNode(ModuleId("cedarB"), dependsOn = List("root")),
          ModuleNode(ModuleId("spruce"), dependsOn = List("cedarA", "cedarB"), publishes = true),
        )
      )
      assertTrue(g.subsetLayers(_.publishes) == List(List("root"), List("spruce")))
    },
  )
end ModuleGraphSpec
