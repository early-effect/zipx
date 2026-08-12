package zipx.docs

import zipx.core.*

/** Small graphs for DocSpec planner examples (not the full core Fixtures monorepo). */
object DocsFixtures:

  val config: PlanConfig =
    PlanConfig(
      cacheEpoch = CacheEpoch.Fixed("0.1.0-ci"),
      skipMergedPrPush = false,
      verifyCleanLabel = None,
      // Doc fragments teach job ids and YAML shape; Auto collapse is covered on the Matrix collapse page.
      // Capabilities that call `.withMatrixCollapse(...)` still win over this empty map via their own field.
    )

  val libGraph: ModuleGraph = GraphFixture(
    List(
      ModuleNode(ModuleId("schema"), publishes = true, crossScalaVersions = List("3.8.4"), baseDir = "schema"),
      ModuleNode(
        ModuleId("api"),
        dependsOn = List("schema"),
        publishes = true,
        crossScalaVersions = List("3.8.4"),
        baseDir = "api",
      ),
      ModuleNode(
        ModuleId("service"),
        dependsOn = List("api"),
        docker = true,
        publishes = false,
        crossScalaVersions = List("3.8.4"),
        baseDir = "service",
      ),
    )
  )

  /** Same as [[libGraph]] (service already has `docker = true`). Alias for deploy/docker recipes. */
  val dockerLibGraph: ModuleGraph = libGraph

end DocsFixtures
