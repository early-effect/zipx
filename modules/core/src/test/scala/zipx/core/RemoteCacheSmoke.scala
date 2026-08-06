package zipx.core

import zipx.workflow.*

/** Minimal Aggregate + BazelRemoteSidecar graph for planner proofs (not a checked-in workflow).
  *
  * In test scope, not `src/main`: nothing but `RemoteCacheSmokeSpec` uses it, and a fixture in main scope would be the
  * one caller keeping an unchecked graph constructor alive there.
  */
object RemoteCacheSmoke:

  /** Tiny one-module graph (enough for Aggregate test). */
  val graph: ModuleGraph = GraphFixture(
    List(ModuleNode(ModuleId("lib"), publishes = false, crossScalaVersions = List("3.8.4"), baseDir = "lib"))
  )

  def config(
      javaVersion: JdkVersion = PlanConfig.DefaultJdkVersion,
      runnerOs: RunnerOs = PlanConfig.DefaultRunnerOs,
  ): PlanConfig =
    PlanConfig(
      javaVersion = javaVersion,
      runnerOs = runnerOs,
      cache = RemoteCacheProof.sidecar,
      cacheEpoch = CacheEpoch.Fixed("0.0.0"),
      skipMergedPrPush = false,
      scalaMatrix = false,
      verifyCleanLabel = None,
    )

  def plan(
      javaVersion: JdkVersion = PlanConfig.DefaultJdkVersion,
      runnerOs: RunnerOs = PlanConfig.DefaultRunnerOs,
  ): Workflow =
    Planner.plan(graph, List(Capability.test), config(javaVersion, runnerOs))

end RemoteCacheSmoke
