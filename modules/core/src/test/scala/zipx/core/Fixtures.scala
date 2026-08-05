package zipx.core

/** The cross-published monorepo graph shared by the core tests: a diamond over `api`, one 2.13-only publisher, and
  * non-publishing services and workers.
  */
object Fixtures:

  val scala2 = "2.13.16"
  val scala3 = "3.8.4"
  val cross  = List(scala2, scala3)

  val sampleGraph: ModuleGraph = ModuleGraph(
    List(
      ModuleNode("core", crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("schema", publishes = true, crossScalaVersions = cross),
      ModuleNode("api", dependsOn = List("schema"), publishes = true, crossScalaVersions = cross),
      ModuleNode("legacyClient", dependsOn = List("schema"), publishes = true, crossScalaVersions = List(scala2)),
      ModuleNode("clientA", dependsOn = List("api"), publishes = true, crossScalaVersions = cross),
      ModuleNode("clientB", dependsOn = List("api"), publishes = true, crossScalaVersions = cross),
      ModuleNode("serviceA", dependsOn = List("core", "api"), crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("serviceB", dependsOn = List("core", "api"), crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("serviceC", dependsOn = List("api"), crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("serviceD", dependsOn = List("core", "api"), crossScalaVersions = List(scala3)),
      ModuleNode("workerA", dependsOn = List("core"), crossScalaVersions = List(scala3)),
      ModuleNode("workerB", dependsOn = List("core"), crossScalaVersions = List(scala3)),
    )
  )
end Fixtures
