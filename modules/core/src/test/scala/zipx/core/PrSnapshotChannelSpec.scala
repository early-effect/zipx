package zipx.core

import zipx.workflow.ExprLiteral
import zio.test.*

object PrSnapshotChannelSpec extends ZIOSpecDefault:
  import Fixtures.sampleGraph

  private val config = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.4.2-SNAPSHOT"),
    affected = AffectedMode.Always,
  )

  private val cap = Capability.pullRequestSnapshots(ExprLiteral("snapshots"))

  private def job = Planner.plan(sampleGraph, List(Capability.test, cap), config).jobs.get("snapshots-pr")

  def spec = suite("snapshots-pr")(
    test("allJobIds names the one job the plan emits") {
      val wf = Planner.plan(sampleGraph, List(Capability.test, cap), config)
      assertTrue(
        Planner.allJobIds(cap, sampleGraph, config).map(id => id: String) == List("snapshots-pr"),
        wf.jobs.contains("snapshots-pr"),
      )
    },
    test("it runs only for a labeled pull request from this repository, never a fork's") {
      val cond = job.flatMap(_.`if`).getOrElse("")
      assertTrue(
        cond.contains("github.event_name == 'pull_request'"),
        cond.contains("contains(github.event.pull_request.labels.*.name, 'snapshots')"),
        cond.contains("github.event.pull_request.head.repo.full_name == github.repository"),
      )
    },
    test("it publishes after the PR's test, from the PR's restored cache, and never saves") {
      assertTrue(
        job.exists(_.needs.contains("test")),
        job.flatMap(_.`if`).exists(_.contains("needs.test.result != 'failure'")),
        job.exists(_.steps.exists(_.`with`.get("cache-mode").contains("restore"))),
        job.exists(_.steps.exists(_.run.exists(_.contains("zipxSnapshotPublish pr")))),
      )
    },
  )
end PrSnapshotChannelSpec
