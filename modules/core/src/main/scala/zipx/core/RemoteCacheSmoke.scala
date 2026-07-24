package zipx.core

import zipx.workflow.*

/** Minimal Aggregate + BazelRemoteSidecar graph for planner / DocSpec proofs (not a checked-in workflow). */
object RemoteCacheSmoke:

  /** Tiny one-module graph (enough for Aggregate test). */
  val graph: ModuleGraph = ModuleGraph(
    List(ModuleNode("lib", publishes = false, crossScalaVersions = List("3.8.4"), baseDir = "lib"))
  )

  def config(javaVersion: String = "21", runnerOs: String = "ubuntu-latest"): PlanConfig =
    PlanConfig(
      workflowName = "CI",
      javaVersion = javaVersion,
      runnerOs = runnerOs,
      cache = RemoteCacheProof.sidecar,
      cacheEpoch = "0.0.0",
      skipMergedPrPush = false,
      scalaMatrix = false,
    )

  def plan(javaVersion: String = "21", runnerOs: String = "ubuntu-latest"): Workflow =
    Planner.plan(graph, List(Capability.test), config(javaVersion, runnerOs))

end RemoteCacheSmoke
