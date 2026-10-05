package zipx.docs

import zipx.core.*

object DocsFixtures:

  val config: PlanConfig =
    PlanConfig(
      cacheEpoch = CacheEpoch.Fixed("0.1.0-SNAPSHOT"),
      skipMergedPrPush = false,
      verifyCleanLabel = None,
      cachePurgeLabel = None,
    )

  val libGraph: ModuleGraph = GraphFixture(
    List(
      ModuleNode(ModuleId("schema"), publishes = true, crossScalaVersions = List("3.9.0"), baseDir = "schema"),
      ModuleNode(
        ModuleId("api"),
        dependsOn = List("schema"),
        publishes = true,
        crossScalaVersions = List("3.9.0"),
        baseDir = "api",
      ),
      ModuleNode(
        ModuleId("service"),
        dependsOn = List("api"),
        docker = true,
        publishes = false,
        crossScalaVersions = List("3.9.0"),
        baseDir = "service",
      ),
    )
  )

  /** An alias: `libGraph`'s `service` already sets `docker = true`. */
  val dockerLibGraph: ModuleGraph = libGraph

end DocsFixtures
