package zipx.core

import zipx.workflow.Job
import zio.test.*

object SnapshotChannelSpec extends ZIOSpecDefault:
  import Fixtures.sampleGraph

  private val base = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.4.2-SNAPSHOT"),
    affected = AffectedMode.Always,
  )

  private val gConfig: Gen[Any, PlanConfig] =
    for
      skipMerged <- Gen.boolean
      rehydrate  <- Gen.boolean
    yield base.copy(skipMergedPrPush = skipMerged, cacheRehydrateOnMerge = rehydrate)

  private def snapshotsJob(config: PlanConfig): Option[Job] =
    Planner.plan(sampleGraph, List(Capability.test, Capability.snapshots()), config).jobs.get("snapshots")

  private def cacheMode(job: Job): Option[String] =
    job.steps.flatMap(_.`with`.get("cache-mode")).headOption

  def spec = suite("snapshots")(
    test("allJobIds names the one job the plan emits, under every merge-push setting") {
      check(gConfig) { config =>
        val cap = Capability.snapshots()
        val wf  = Planner.plan(sampleGraph, List(Capability.test, cap), config)
        val ids = Planner.allJobIds(cap, sampleGraph, config).map(id => id: String)
        assertTrue(ids == List("snapshots"), ids.forall(wf.jobs.contains))
      }
    },
    test("it runs on a default-branch push or a dispatch, restores the build snapshot, and never saves one") {
      check(gConfig) { config =>
        val job = snapshotsJob(config)
        assertTrue(
          job.flatMap(_.`if`).exists(_.contains("github.ref == 'refs/heads/main'")),
          job.flatMap(_.`if`).exists(_.contains("github.event_name == 'workflow_dispatch'")),
          job.flatMap(cacheMode).contains("restore"),
          job.exists(_.steps.exists(_.run.exists(_.contains("zipxSnapshotPublish")))),
        )
      }
    },
    test("it waits on test and tolerates it skipping, which Verify does on a merged-PR push and a dispatch") {
      check(gConfig) { config =>
        val cond = snapshotsJob(config).flatMap(_.`if`).getOrElse("")
        assertTrue(
          snapshotsJob(config).exists(_.needs.contains("test")),
          cond.contains("!cancelled() && needs"),
          cond.contains("needs.test.result != 'failure'"),
        )
      }
    },
    test("on a skipped merge push it waits on cache-rehydrate, which owns that push's build snapshot") {
      check(gConfig) { config =>
        val rehydrates = config.skipMergedPrPush && config.cacheRehydrateOnMerge
        val job        = snapshotsJob(config)
        assertTrue(
          job.exists(_.needs.contains("cache-rehydrate")) == rehydrates,
          job.flatMap(_.`if`).exists(_.contains("needs.cache-rehydrate.result != 'failure'")) == rehydrates,
        )
      }
    },
  )
end SnapshotChannelSpec
