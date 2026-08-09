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

  /** A `projectMatrix` build as sbt 2.0.5 actually resolves one: synthetic `.sbt/matrix/<id>` base dirs, with the real
    * directories reaching zipx only through `unmanagedSourceDirectories`. Taken from `print coreJS/baseDirectory` and
    * `print coreJS/Compile/unmanagedSourceDirectories` on a live build.
    */
  private val matrix = GraphFixture(
    List(
      ModuleNode(
        ModuleId("core"),
        baseDir = ".sbt/matrix/core",
        sourcePaths = List(
          "core/src/main/scala",
          "core/src/main/scalajvm",
          "core/src/test/scala",
          "core/src/test/scalajvm",
        ),
      ),
      ModuleNode(
        ModuleId("coreJS"),
        baseDir = ".sbt/matrix/coreJS",
        sourcePaths = List(
          "core/src/main/scala",
          "core/src/main/scalajs",
          "core/src/test/scala",
          "core/src/test/scalajs",
        ),
      ),
      ModuleNode(ModuleId("site"), dependsOn = List("core"), baseDir = "site"),
      ModuleNode(ModuleId("cli"), dependsOn = List("coreJS"), baseDir = "cli"),
    )
  )

  def spec = suite("Affected")(
    test("maps a changed file to its owning module by base-dir prefix") {
      assertTrue(
        Affected.owningModules(graph, "core-lib/src/main/scala/Core.scala") == Set("coreLib"),
        Affected.owningModules(graph, "models/src/main/scala/Models.scala") == Set("models"),
        Affected.owningModules(graph, "README.md").isEmpty,
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
        Affected.owningModules(nested, "mods/inner/X.scala") == Set("inner"),
        Affected.owningModules(nested, "mods/Other.scala") == Set("outer"),
      )
    },
    test("sibling base dirs that share a name prefix must not cross-match") {
      val g =
        GraphFixture(
          List(ModuleNode(ModuleId("core"), baseDir = "core"), ModuleNode(ModuleId("coreLib"), baseDir = "core-lib"))
        )
      assertTrue(
        Affected.owningModules(g, "core-lib/src/X.scala") == Set("coreLib"),
        Affected.owningModules(g, "core/src/X.scala") == Set("core"),
        Affected.owningModules(g, "core-extra/X.scala").isEmpty,
      )
    },
    test("a directory name that is a strict superstring of a base dir does not match") {
      val g = GraphFixture(List(ModuleNode(ModuleId("app"), baseDir = "app")))
      assertTrue(
        Affected.owningModules(g, "application/Main.scala").isEmpty,
        Affected.owningModules(g, "app/Main.scala") == Set("app"),
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
      assertTrue(Affected.owningModules(graph, "models") == Set("models"))
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
        Affected.owningModules(g, "build.sbt").isEmpty,
        Affected.owningModules(g, "something.txt").isEmpty,
        Affected.owningModules(g, "lib/X.scala") == Set("lib"),
      )
    },
    test("baseDir with a trailing slash still matches") {
      val g = GraphFixture(List(ModuleNode(ModuleId("app"), baseDir = "app/")))
      assertTrue(
        Affected.owningModules(g, "app/Main.scala") == Set("app"),
        Affected.owningModules(g, "app") == Set("app"),
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
        Affected.owningModules(g, "pkgs/ab/X.scala") == Set("ab"),
        Affected.owningModules(g, "pkgs/a/X.scala") == Set("a"),
      )
    },
    suite("cross-built modules (#73)")(
      // A `projectMatrix` row's baseDir is a synthetic `.sbt/matrix/<id>`, so only sourcePaths can answer.
      test("shared sources affect every platform row; platform-specific sources affect one") {
        assertTrue(
          Affected.owningModules(matrix, "core/src/main/scala/Foo.scala") == Set("core", "coreJS"),
          Affected.owningModules(matrix, "core/src/main/scalajs/Foo.scala") == Set("coreJS"),
          Affected.owningModules(matrix, "core/src/main/scalajvm/Foo.scala") == Set("core"),
          Affected.owningModules(matrix, "core/src/test/scala/FooSpec.scala") == Set("core", "coreJS"),
        )
      },
      test("sourcePaths are what answer: without them the synthetic base dir owns no source at all") {
        // The defect, exactly. `.sbt/matrix/<id>` is a real directory git never sees a source file in.
        val basesOnly = GraphFixture(matrix.nodes.map(_.copy(sourcePaths = Nil)))
        assertTrue(
          Affected.owningModules(basesOnly, "core/src/main/scala/Foo.scala").isEmpty,
          Affected.affectedModules(basesOnly, List("core/src/main/scala/Foo.scala")).isEmpty,
        )
      },
      test("a shared change reaches both rows' dependents, which is the bug this closes") {
        // Under the old `Option` return this was Set("coreJS", "cli") or Set("core", "site"), never both.
        assertTrue(
          Affected.affectedModules(matrix, List("core/src/main/scala/Foo.scala")) ==
            Set("core", "coreJS", "site", "cli"),
          Affected.affectedModules(matrix, List("core/src/main/scalajs/Foo.scala")) == Set("coreJS", "cli"),
        )
      },
      test("a non-source file still resolves through baseDir for an ordinary module") {
        assertTrue(Affected.owningModules(matrix, "site/README.md") == Set("site"))
      },
    ),
  )
end AffectedSpec
