package zipx.core

import zio.test.*

object RemoteCacheSmokeSpec extends ZIOSpecDefault:
  def spec = suite("RemoteCacheSmoke")(
    test("smoke plan uses RemoteCacheProof sidecar pins") {
      val wf  = RemoteCacheSmoke.plan()
      val job = wf.jobs("test")
      assertTrue(
        job.services.contains(RemoteCacheProof.serviceName),
        job.services(RemoteCacheProof.serviceName).image == RemoteCacheProof.image,
        job.env.get(RemoteCacheProof.envUri).contains(RemoteCacheProof.grpcLocalhost),
        wf.jobs.keySet == Set("test"),
      )
    },
    test("live remote-cache proof is Aggregate test, not a parallel once-job") {
      // Put/Get runs inside core tests via Testcontainers; CI only needs the normal `test` job.
      val wf = Planner.plan(
        RemoteCacheSmoke.graph,
        List(Capability.test),
        RemoteCacheSmoke.config().copy(skipMergedPrPush = true),
      )
      assertTrue(
        wf.jobs.contains("test"),
        !wf.jobs.contains("remote-cache-it"),
        wf.jobs("test").needs.contains("verify-gate"),
      )
    },
  )
end RemoteCacheSmokeSpec
