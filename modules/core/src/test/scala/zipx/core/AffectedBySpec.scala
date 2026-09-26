package zipx.core

import zio.test.*

object AffectedBySpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"))

  private val imageIt =
    Capability
      .once(
        name = CapabilityName("image-it"),
        command = SbtCommand.unsafeTask("imageIt/testFull"),
        phase = Phase.Verify,
        gate = Gate.Always,
      )
      .withAffectedBy(n => n.id == "serviceA" || n.id == "serviceB")

  private def refusal(capability: Capability): Option[String] =
    scala.util.Try(Planner.plan(sampleGraph, List(capability), config)).failed.toOption.map(_.getMessage)

  def spec = suite("withAffectedBy")(
    test("a Once job needs affected and runs when one of its modules is affected, or when nothing can be narrowed") {
      val wf  = Planner.plan(sampleGraph, List(imageIt), config)
      val job = wf.jobs("image-it")
      assertTrue(
        wf.jobs.contains("affected"),
        job.needs.contains("affected"),
        job.`if`.exists(
          _.contains(
            "(contains(fromJson(needs.affected.outputs.modules), 'serviceA') || " +
              "contains(fromJson(needs.affected.outputs.modules), 'serviceB') || " +
              "contains(fromJson(needs.affected.outputs.modules), 'all'))"
          )
        ),
        job.`if`.exists(_.contains("(!cancelled() && (contains(fromJson(needs.affected.outputs.modules), 'serviceA')")),
      )
    },
    test("with affected gating off, the job runs ungated as before") {
      val wf = Planner.plan(sampleGraph, List(imageIt), config.copy(affected = AffectedMode.Always))
      assertTrue(!wf.jobs.contains("affected"), !wf.jobs("image-it").`if`.exists(_.contains("needs.affected")))
    },
    test("a job without it is unchanged") {
      val plain = imageIt.copy(affectedBy = None)
      assertTrue(!Planner.plan(sampleGraph, List(plain), config).jobs("image-it").needs.contains("affected"))
    },
    test("a Graph capability refuses it, since its jobs are already gated per module") {
      val graphed = Capability.testGraph.withAffectedBy(_ => true)
      assertTrue(refusal(graphed).exists(_.contains("only a Once job needs it")))
    },
    test("a predicate matching no module is refused, since the job would run only when nothing can be narrowed") {
      assertTrue(refusal(imageIt.withAffectedBy(_ => false)).exists(_.contains("matches no module")))
    },
  )
end AffectedBySpec
