package zipx.core

/** The cross-published monorepo graph shared by the core tests: a diamond over `api`, one 2.13-only publisher, and
  * non-publishing services and batch modules.
  */
object Fixtures:

  val scala2 = "2.13.16"
  val scala3 = "3.8.4"
  val cross  = List(scala2, scala3)

  val sampleGraph: ModuleGraph = GraphFixture(
    List(
      ModuleNode(ModuleId("core"), crossScalaVersions = List(scala3), testTask = SbtCommand.unsafeTask("testFull")),
      ModuleNode(ModuleId("schema"), publishes = true, crossScalaVersions = cross),
      ModuleNode(ModuleId("api"), dependsOn = List("schema"), publishes = true, crossScalaVersions = cross),
      ModuleNode(
        ModuleId("legacyClient"),
        dependsOn = List("schema"),
        publishes = true,
        crossScalaVersions = List(scala2),
      ),
      ModuleNode(ModuleId("clientA"), dependsOn = List("api"), publishes = true, crossScalaVersions = cross),
      ModuleNode(ModuleId("clientB"), dependsOn = List("api"), publishes = true, crossScalaVersions = cross),
      ModuleNode(
        ModuleId("serviceA"),
        dependsOn = List("core", "api"),
        crossScalaVersions = List(scala3),
        testTask = SbtCommand.unsafeTask("testFull"),
      ),
      ModuleNode(
        ModuleId("serviceB"),
        dependsOn = List("core", "api"),
        crossScalaVersions = List(scala3),
        testTask = SbtCommand.unsafeTask("testFull"),
      ),
      ModuleNode(
        ModuleId("serviceC"),
        dependsOn = List("api"),
        crossScalaVersions = List(scala3),
        testTask = SbtCommand.unsafeTask("testFull"),
      ),
      ModuleNode(ModuleId("serviceD"), dependsOn = List("core", "api"), crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("batchA"), dependsOn = List("core"), crossScalaVersions = List(scala3)),
      ModuleNode(ModuleId("batchB"), dependsOn = List("core"), crossScalaVersions = List(scala3)),
    )
  )
end Fixtures
