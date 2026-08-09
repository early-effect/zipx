package zipx.core

import zio.test.*
import zipx.workflow.{ActionRef, JobService, Render}

/** `Capability.container` and `Capability.services`: a capability's own job runtime (#72).
  *
  * A suite of its own because the risk is not in the two fields, it is in the *five* places a [[Job]] is built. Before
  * this, `services` came from one source (the cache backend) and every site wrote `cache.services` verbatim, so a site
  * that keeps doing that silently drops a sidecar the build asked for. Every scope is therefore asserted separately
  * rather than through one representative one.
  */
object CapabilityRuntimeSpec extends ZIOSpecDefault:
  import Fixtures.*
  import Rendered.yaml

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val postgres = JobService("postgres:17", ports = List("5432:5432"), options = Some("--health-cmd pg_isready"))
  private val redis    = JobService("redis:7", ports = List("6379:6379"))

  private val image = "ghcr.io/early-effect/build-base:1"

  private def plan(capability: Capability, cfg: PlanConfig = config) =
    Planner.plan(sampleGraph, List(capability), cfg)

  /** The same runtime asked for on every scope, so each scope's assertion reads identically. */
  private def withRuntime(capability: Capability): Capability =
    capability.withService("postgres", postgres).inContainer(image)

  private def assertRuntime(job: zipx.workflow.Job): TestResult =
    assertTrue(
      job.container.contains(image),
      job.services.get("postgres").contains(postgres),
    )

  def spec = suite("Capability container and services")(
    suite("every Job construction site carries them, not just the one that was easy to test")(
      test("Once, the scope a database-backed integration suite actually uses") {
        val cap = withRuntime(Capability.once(name = CapabilityName("it"), command = SbtCommand("it/testFull")))
        assertRuntime(plan(cap).jobs("it"))
      },
      test("Aggregate with no targets") {
        assertRuntime(plan(withRuntime(Capability.testJoined)).jobs("test"))
      },
      test("Aggregate fanned out per target, where each target's job needs the same sidecar") {
        val cap =
          withRuntime(Capability.testJoined).withTargets(_ => List(Target(TargetName("a")), Target(TargetName("b"))))
        val wf = plan(cap)
        assertRuntime(wf.jobs("test-a")) && assertRuntime(wf.jobs("test-b"))
      },
      test("Layer, every wave of it and not only L0") {
        val wf    = plan(withRuntime(Capability.testLayers))
        val layer = wf.jobs.keys.filter(_.startsWith("test-L")).toList
        assertTrue(layer.size > 1) && TestResult.allSuccesses(layer.map(id => assertRuntime(wf.jobs(id))))
      },
      test("Graph, including the matrixed jobs") {
        val wf     = plan(withRuntime(Capability.testGraph))
        val graphs = wf.jobs.keys.filter(_.startsWith("test-")).toList
        assertTrue(graphs.nonEmpty) && TestResult.allSuccesses(graphs.map(id => assertRuntime(wf.jobs(id))))
      },
    ),
    suite("several sidecars, and the cache backend beside them")(
      test("two services coexist, since a suite may need a database and a cache") {
        val cap = Capability
          .once(name = CapabilityName("it"), command = SbtCommand("it/testFull"))
          .withServices(Map("postgres" -> postgres, "redis" -> redis))
        val job = plan(cap).jobs("it")
        assertTrue(job.services.get("postgres").contains(postgres), job.services.get("redis").contains(redis))
      },
      test("a BazelRemoteSidecar job carries the capability's sidecar and zipx's, both") {
        val job = plan(withRuntime(Capability.testGraph), config.copy(cache = RemoteCacheProof.sidecar))
          .jobs("test-core")
        assertTrue(
          job.services.get("postgres").contains(postgres),
          job.services.get(RemoteCacheProof.serviceName).exists(_.image == RemoteCacheProof.image),
          job.services.size == 2,
        )
      },
      test("the cache sidecar wins a colliding id, because the sbt invocation is configured to reach it") {
        // A capability losing its own sidecar surfaces as a connection error in the test that wanted it. The cache
        // sidecar losing would fail *every* job in the workflow on a name nobody chose deliberately.
        val cap = Capability.testGraph.withService(RemoteCacheProof.serviceName, postgres)
        val job = plan(cap, config.copy(cache = RemoteCacheProof.sidecar)).jobs("test-core")
        assertTrue(
          job.services(RemoteCacheProof.serviceName).image == RemoteCacheProof.image,
          job.services.size == 1,
        )
      },
    ),
    suite("what a capability cannot ask for")(
      // GitHub rejects `container:` and `services:` beside `uses:`, and `onceJob`'s workflowCall branch has nowhere to
      // put them. Dropping them silently would leave a job whose steps expect a sidecar that is not there.
      test("services with workflowCall is refused, naming the field") {
        val cap = Capability
          .once(name = CapabilityName("pages"), command = SbtCommand("noop"))
          .withService("postgres", postgres)
          .copy(workflowCall = Some(WorkflowCall(ActionRef("org/repo/.github/workflows/pages.yml@main"))))
        val err = scala.util.Try(plan(cap)).failed.get.getMessage
        assertTrue(err.contains("'pages'"), err.contains("services"), !err.contains("container"))
      },
      test("container with workflowCall is refused too") {
        val cap = Capability
          .once(name = CapabilityName("pages"), command = SbtCommand("noop"))
          .inContainer(image)
          .copy(workflowCall = Some(WorkflowCall(ActionRef("org/repo/.github/workflows/pages.yml@main"))))
        val err = scala.util.Try(plan(cap)).failed.get.getMessage
        assertTrue(err.contains("container"), !err.contains("services"))
      },
      test("both at once are named in one message, so a fix is one edit rather than two runs") {
        val cap = withRuntime(Capability.once(name = CapabilityName("pages"), command = SbtCommand("noop")))
          .copy(workflowCall = Some(WorkflowCall(ActionRef("org/repo/.github/workflows/pages.yml@main"))))
        val err = scala.util.Try(plan(cap)).failed.get.getMessage
        assertTrue(err.contains("container and services"))
      },
      test("a workflowCall capability asking for neither still plans, which is every existing one") {
        val cap = Capability
          .once(name = CapabilityName("pages"), command = SbtCommand("noop"))
          .copy(workflowCall = Some(WorkflowCall(ActionRef("org/repo/.github/workflows/pages.yml@main"))))
        assertTrue(plan(cap).jobs("pages").uses.isDefined)
      },
    ),
    suite("the rendered YAML, and nothing changed for a build that asks for neither")(
      test("both keys reach the file in the shape GitHub reads") {
        val out =
          Render.render(plan(withRuntime(Capability.once(CapabilityName("it"), SbtCommand("it/testFull"))))).yaml
        assertTrue(
          out.contains(s"container: $image"),
          out.contains("postgres:"),
          out.contains("image: postgres:17"),
          // Quoted by the renderer, since a bare `5432:5432` would read as a nested mapping key.
          out.contains("""- "5432:5432""""),
          out.contains("options: --health-cmd pg_isready"),
        )
      },
      test("a capability declaring neither renders byte-identically to before the fields existed") {
        // The regression guard for the five sites: `container` stays absent and `services` stays whatever the cache
        // backend put there, so no existing workflow file moves.
        val plain    = Render.render(plan(Capability.testGraph)).yaml
        val sidecar  = Render.render(plan(Capability.testGraph, config.copy(cache = RemoteCacheProof.sidecar))).yaml
        val jobPlain = plan(Capability.testGraph).jobs("test-core")
        assertTrue(
          !plain.contains("container:"),
          !plain.contains("services:"),
          jobPlain.container.isEmpty,
          jobPlain.services.isEmpty,
          sidecar.contains(RemoteCacheProof.serviceName),
        )
      },
    ),
  )
end CapabilityRuntimeSpec
