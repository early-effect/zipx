package zipx.core

import zio.test.*

object AffectedSpec extends ZIOSpecDefault:

  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("models"), baseDir = "models"),
      ModuleNode(ModuleId("coreLib"), dependsOn = List("models"), baseDir = "core-lib"),
      ModuleNode(ModuleId("client"), dependsOn = List("coreLib"), baseDir = "client"),
      ModuleNode(ModuleId("service"), dependsOn = List("coreLib"), baseDir = "service"),
    )
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
      assertTrue(
        Affected.affectedModules(graph, List("models/src/main/scala/Models.scala")) ==
          Set("models", "coreLib", "client", "service")
      )
    },
    test("a leaf change affects only that leaf") {
      assertTrue(
        Affected.affectedModules(graph, List("client/src/main/scala/Client.scala")) == Set("client")
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
        Affected.affectedModules(graph, List("client/x.scala", "build.sbt")) == graph.ids.toSet,
      )
    },
    test("files under no module are ignored (empty affected set)") {
      assertTrue(Affected.affectedModules(graph, List("docs/readme.md", ".github/CODEOWNERS")) == Set.empty)
    },
    test("a failed diff (None) emits the 'all' sentinel, not an empty list") {
      assertTrue(
        Affected.outputModules(graph, None) == List("all"),
        Affected.outputModules(graph, None) == Affected.AllSentinel,
        Affected.outputModules(graph, None).nonEmpty,
        graph.ids.forall(id => !Affected.outputModules(graph, None).contains(id)),
      )
    },
    test("the sentinel satisfies the generated job condition for every module") {
      val emitted                               = Affected.outputModules(graph, None)
      def gatePasses(moduleId: String): Boolean =
        emitted.contains(moduleId) || emitted.contains("all")
      assertTrue(graph.ids.forall(gatePasses))
    },
    test("a successful diff finding nothing (Some(Nil)) stays empty: it is not a failure") {
      assertTrue(
        Affected.outputModules(graph, Some(Nil)).isEmpty,
        graph.ids.forall(id => !Affected.outputModules(graph, Some(Nil)).contains(id)),
      )
    },
    test("a successful diff is unaffected by the fail-open path") {
      assertTrue(
        Affected.outputModules(graph, Some(List("client/src/main/scala/Client.scala"))) == List("client"),
        Affected.outputModules(graph, Some(List("build.sbt"))) == graph.ids.toList.sorted,
        Affected.outputModules(graph, Some(List("models/x.scala"))) ==
          List("client", "coreLib", "models", "service"),
      )
    },
    test("longest-prefix wins when base dirs would otherwise overlap") {
      val nested = GraphFixture(
        List(
          ModuleNode(ModuleId("outer"), baseDir = "mods"),
          ModuleNode(ModuleId("inner"), baseDir = "mods/inner"),
        )
      )
      assertTrue(
        Affected.owningModule(nested, "mods/inner/X.scala").contains("inner"),
        Affected.owningModule(nested, "mods/Other.scala").contains("outer"),
      )
    },
    test("sibling base dirs that share a name prefix must not cross-match") {
      val g =
        GraphFixture(
          List(ModuleNode(ModuleId("core"), baseDir = "core"), ModuleNode(ModuleId("coreLib"), baseDir = "core-lib"))
        )
      assertTrue(
        Affected.owningModule(g, "core-lib/src/X.scala").contains("coreLib"),
        Affected.owningModule(g, "core/src/X.scala").contains("core"),
        Affected.owningModule(g, "core-extra/X.scala").isEmpty,
      )
    },
    test("a directory name that is a strict superstring of a base dir does not match") {
      val g = GraphFixture(List(ModuleNode(ModuleId("app"), baseDir = "app")))
      assertTrue(
        Affected.owningModule(g, "application/Main.scala").isEmpty,
        Affected.owningModule(g, "app/Main.scala").contains("app"),
      )
    },
    test("diamond dependency: closure dedupes the shared apex") {
      val diamond = GraphFixture(
        List(
          ModuleNode(ModuleId("d"), baseDir = "d"),
          ModuleNode(ModuleId("b"), dependsOn = List("d"), baseDir = "b"),
          ModuleNode(ModuleId("c"), dependsOn = List("d"), baseDir = "c"),
          ModuleNode(ModuleId("a"), dependsOn = List("b", "c"), baseDir = "a"),
        )
      )
      assertTrue(Affected.affectedModules(diamond, List("d/X.scala")) == Set("a", "b", "c", "d"))
    },
    test("multiple seeds across independent subtrees union their closures") {
      val affected = Affected.affectedModules(graph, List("models/X.scala", "client/Y.scala"))
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
      assertTrue(
        Affected.affectedModules(graph, List("core-lib/project/plugins.sbt")) == graph.ids.toSet,
        Affected.affectedModules(graph, List("core-lib/build.sbt")) == graph.ids.toSet,
      )
    },
    test("empty baseDir never owns a file (root aggregators are invisible)") {
      val g = GraphFixture(
        List(
          ModuleNode(ModuleId("root"), baseDir = ""),
          ModuleNode(ModuleId("lib"), baseDir = "lib"),
        )
      )
      assertTrue(
        Affected.owningModule(g, "build.sbt").isEmpty,
        Affected.owningModule(g, "something.txt").isEmpty,
        Affected.owningModule(g, "lib/X.scala").contains("lib"),
      )
    },
    test("baseDir with a trailing slash still matches") {
      val g = GraphFixture(List(ModuleNode(ModuleId("app"), baseDir = "app/")))
      assertTrue(
        Affected.owningModule(g, "app/Main.scala").contains("app"),
        Affected.owningModule(g, "app").contains("app"),
      )
    },
    test("the exact path `project` (no slash) forces a full build") {
      assertTrue(Affected.affectedModules(graph, List("project")) == graph.ids.toSet)
    },
    test("`.sbt.bak` is not a build file (suffix must be exactly `.sbt`)") {
      assertTrue(
        Affected.affectedModules(graph, List("docs/foo.sbt.bak")) == Set.empty,
        Affected.affectedModules(graph, List("client/foo.sbt.bak")) == Set("client"),
      )
    },
    test("path that equals a baseDir with nested sibling does not steal the sibling") {
      val g = GraphFixture(
        List(
          ModuleNode(ModuleId("a"), baseDir = "pkgs/a"),
          ModuleNode(ModuleId("ab"), baseDir = "pkgs/ab"),
        )
      )
      assertTrue(
        Affected.owningModule(g, "pkgs/ab/X.scala").contains("ab"),
        Affected.owningModule(g, "pkgs/a/X.scala").contains("a"),
      )
    },
  )
end AffectedSpec
