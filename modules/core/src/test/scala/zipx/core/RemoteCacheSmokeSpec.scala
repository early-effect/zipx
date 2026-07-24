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
    test("remote-cache-it once-job stays parallel with Aggregate test") {
      val wf = Planner.plan(
        RemoteCacheSmoke.graph,
        List(
          Capability.test,
          Capability.once(name = "remote-cache-it", command = "it/test"),
        ),
        RemoteCacheSmoke.config().copy(skipMergedPrPush = true),
      )
      assertTrue(
        wf.jobs.contains("test"),
        wf.jobs.contains("remote-cache-it"),
        wf.jobs("test").needs.contains("verify-gate"),
        wf.jobs("remote-cache-it").needs.contains("verify-gate"),
        !wf.jobs("remote-cache-it").needs.contains("test"),
        !wf.jobs("test").needs.contains("remote-cache-it"),
      )
    },
  )
end RemoteCacheSmokeSpec
