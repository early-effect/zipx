package zipx.core

import neotype.unwrap
import zio.test.*
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.*

object ActionOnlyCapabilitySpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val Notify = CapabilityName("notify")

  private def actionOnly(
      gate: Gate = Gate.Always,
      needs: List[CapabilityName] = Nil,
      condition: Option[JobCondition] = None,
      permissions: Map[String, String] = Map("contents" -> "read"),
  ): Capability =
    Capability.steps(
      name = Notify,
      steps = _ =>
        List(
          Step.run(Script(Exec("echo", Word.lit("notified")))).named("Notify").build
        ),
      phase = Phase.Verify,
      gate = gate,
      needsCapabilities = needs,
      permissions = permissions,
      condition = condition,
    )

  private def usesAction(step: Step, prefix: String): Boolean =
    step.uses.exists(_.unwrap.startsWith(prefix))

  def spec = suite("action-only capabilities")(
    test("Once action-only job keeps permissions, needs, gate, and condition") {
      val fmt = CapabilityName("fmt")
      val cap = actionOnly(
        gate = Gate.OnReleaseTag,
        needs = List(fmt),
        condition = Some(JobCondition.repositoryIs("acme/app")),
        permissions = Map("id-token" -> "write", "contents" -> "read"),
      )
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.once(fmt, SbtCommand.unsafeTask("scalafmtCheckAll")), cap),
        config,
      )
      val job = wf.jobs("notify")
      assertTrue(
        job.permissions.get("id-token").contains("write"),
        job.permissions.get("contents").contains("read"),
        job.needs.contains("fmt"),
        job.`if`.exists(_.contains("refs/tags/v")),
        job.`if`.exists(_.contains("acme/app")),
        job.steps.exists(_.name.contains("Notify")),
      )
    },
    test("action-only job skips setup-java, setup-sbt, and actions/cache") {
      val job = Planner.plan(sampleGraph, List(actionOnly()), config).jobs("notify")
      assertTrue(
        !job.steps.exists(_.uses.contains(ZipxComposites.SbtSetupRef)),
        !job.steps.exists(usesAction(_, "actions/setup-java")),
        !job.steps.exists(usesAction(_, "sbt/setup-sbt")),
        !job.steps.exists(usesAction(_, "actions/cache")),
        job.steps.exists(usesAction(_, "actions/checkout")),
        !job.steps.exists(_.run.exists(_.contains("sbt "))),
      )
    },
    test("action-only job does not merge remote-cache sidecar") {
      val cfg = config.copy(cache = RemoteCacheProof.sidecar)
      val job = Planner.plan(sampleGraph, List(actionOnly()), cfg).jobs("notify")
      assertTrue(
        !job.services.contains(RemoteCacheProof.serviceName),
        !job.env.contains(RemoteCacheProof.envUri),
      )
    },
    test("existing Once capabilities still emit the toolchain") {
      val fmt = Capability.once(CapabilityName("fmt"), SbtCommand.unsafeTask("scalafmtCheckAll"))
      val job = Planner.plan(sampleGraph, List(fmt), config).jobs("fmt")
      assertTrue(
        job.steps.exists(_.uses.contains(ZipxComposites.SbtSetupRef)),
        job.steps.exists(_.run.exists(_.contains("scalafmtCheckAll"))),
      )
    },
  )
end ActionOnlyCapabilitySpec
