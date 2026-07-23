package zipx.docs

import zipx.core.*

/** Small graphs for DocSpec planner examples (not the full core Fixtures monorepo). */
object DocsFixtures:

  val config: PlanConfig = PlanConfig(cacheEpoch = "0.1.0-ci", skipMergedPrPush = false)

  val libGraph: ModuleGraph = ModuleGraph(
    List(
      ModuleNode("schema", publishes = true, crossScalaVersions = List("3.8.4"), baseDir = "schema"),
      ModuleNode(
        "api",
        dependsOn = List("schema"),
        publishes = true,
        crossScalaVersions = List("3.8.4"),
        baseDir = "api",
      ),
      ModuleNode(
        "service",
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
