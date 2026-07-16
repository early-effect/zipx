package zipx.core

import zio.test.*

object AffectedSpec extends ZIOSpecDefault:

  // A small graph with base dirs, mirroring the example monorepo layout.
  private val graph = ModuleGraph(
    List(
      ModuleNode("models", baseDir = "models"),
      ModuleNode("coreLib", dependsOn = List("models"), baseDir = "core-lib"),
      ModuleNode("client", dependsOn = List("coreLib"), baseDir = "client"),
      ModuleNode("service", dependsOn = List("coreLib"), baseDir = "service"),
    ),
  )

  def spec = suite("Affected")(
    test("maps a changed file to its owning module by base-dir prefix") {
      assertTrue(
        Affected.owningModule(graph, "core-lib/src/main/scala/Core.scala").contains("coreLib"),
        Affected.owningModule(graph, "models/src/main/scala/Models.scala").contains("models"),
        Affected.owningModule(graph, "README.md").isEmpty,
      )
    },
    test("affected set includes the changed module and all its transitive dependents") {
      // Changing models affects everything downstream.
      assertTrue(
        Affected.affectedModules(graph, List("models/src/main/scala/Models.scala")) ==
          Set("models", "coreLib", "client", "service"),
      )
    },
    test("a leaf change affects only that leaf") {
      assertTrue(
        Affected.affectedModules(graph, List("client/src/main/scala/Client.scala")) == Set("client"),
      )
    },
    test("a coreLib change affects coreLib and its dependents but not models") {
      val affected = Affected.affectedModules(graph, List("core-lib/src/main/scala/Core.scala"))
      assertTrue(
        affected == Set("coreLib", "client", "service"),
        !affected.contains("models"),
      )
    },
    test("a build-file change forces a full build (all modules affected)") {
      assertTrue(
        Affected.affectedModules(graph, List("build.sbt")) == graph.ids.toSet,
        Affected.affectedModules(graph, List("project/plugins.sbt")) == graph.ids.toSet,
        // Even mixed with a leaf change, a build file wins.
        Affected.affectedModules(graph, List("client/x.scala", "build.sbt")) == graph.ids.toSet,
      )
    },
    test("files under no module are ignored (empty affected set)") {
      assertTrue(Affected.affectedModules(graph, List("docs/readme.md", ".github/CODEOWNERS")) == Set.empty)
    },
    test("longest-prefix wins when base dirs would otherwise overlap") {
      val nested = ModuleGraph(
        List(
          ModuleNode("outer", baseDir = "mods"),
          ModuleNode("inner", baseDir = "mods/inner"),
        ),
      )
      assertTrue(
        Affected.owningModule(nested, "mods/inner/X.scala").contains("inner"),
        Affected.owningModule(nested, "mods/Other.scala").contains("outer"),
      )
    },
    // ---- Pathological cases ----
    test("sibling base dirs that share a name prefix must not cross-match") {
      // `core` and `core-lib` share the prefix "core"; a naive startsWith would map core-lib files to core.
      val g = ModuleGraph(List(ModuleNode("core", baseDir = "core"), ModuleNode("coreLib", baseDir = "core-lib")))
      assertTrue(
        Affected.owningModule(g, "core-lib/src/X.scala").contains("coreLib"),
        Affected.owningModule(g, "core/src/X.scala").contains("core"),
        // `core-extra` belongs to neither module.
        Affected.owningModule(g, "core-extra/X.scala").isEmpty,
      )
    },
    test("a directory name that is a strict superstring of a base dir does not match") {
      val g = ModuleGraph(List(ModuleNode("app", baseDir = "app")))
      assertTrue(
        Affected.owningModule(g, "application/Main.scala").isEmpty, // NOT "app"
        Affected.owningModule(g, "app/Main.scala").contains("app"),
      )
    },
    test("diamond dependency: closure dedupes the shared apex") {
      // a → b, a → c, b → d, c → d ; changing d affects everything above it exactly once.
      val diamond = ModuleGraph(
        List(
          ModuleNode("d", baseDir = "d"),
          ModuleNode("b", dependsOn = List("d"), baseDir = "b"),
          ModuleNode("c", dependsOn = List("d"), baseDir = "c"),
          ModuleNode("a", dependsOn = List("b", "c"), baseDir = "a"),
        ),
      )
      assertTrue(Affected.affectedModules(diamond, List("d/X.scala")) == Set("a", "b", "c", "d"))
    },
    test("multiple seeds across independent subtrees union their closures") {
      val affected = Affected.affectedModules(graph, List("models/X.scala", "client/Y.scala"))
      // models pulls in everything downstream; client adds only itself (already covered) → full set.
      assertTrue(affected == Set("models", "coreLib", "client", "service"))
    },
    test("empty change set affects nothing") {
      assertTrue(Affected.affectedModules(graph, Nil) == Set.empty)
    },
    test("the base-dir path itself (no trailing slash) maps to its module") {
      assertTrue(Affected.owningModule(graph, "models").contains("models"))
    },
    test("a path change unrelated to any module and not a build file affects nothing") {
      assertTrue(Affected.affectedModules(graph, List(".github/workflows/ci.yml", "LICENSE")) == Set.empty)
    },
    test("a build file nested under a module dir still forces a full build") {
      // e.g. a module-local `project/` meta-build change, or a stray .sbt under a module.
      assertTrue(
        Affected.affectedModules(graph, List("core-lib/project/plugins.sbt")) == graph.ids.toSet,
        Affected.affectedModules(graph, List("core-lib/build.sbt")) == graph.ids.toSet,
      )
    },
  )
end AffectedSpec
