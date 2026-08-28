package zipx.core

import zio.test.*
import zipx.workflow.*

object ModverPublishSpec extends ZIOSpecDefault:

  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("models"), publishes = true),
      ModuleNode(ModuleId("coreLib"), dependsOn = List("models"), publishes = true),
      ModuleNode(ModuleId("client"), dependsOn = List("coreLib"), publishes = true),
      ModuleNode(ModuleId("service"), dependsOn = List("coreLib"), publishes = false, docker = true),
    )
  )

  private val lockstep = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val independent = lockstep.copy(modverPublish = true)

  private val publishCmd = SbtCommand.unsafeTask("zipxModverPublishSigned")
  private val cap        = ZipxModver.publish(publishCmd)

  def spec = suite("ModverPublish")(
    test("off is byte-identical to a lockstep Graph publish plan") {
      val a = Planner.plan(graph, List(Capability.publishGraph), lockstep)
      val b = Planner.plan(graph, List(Capability.publishGraph), lockstep.copy(modverPublish = false))
      assertTrue(a == b, !a.jobs.contains("modver"))
    },
    test("ships plus docker still generates") {
      val wf = Planner.plan(graph, List(cap, Capability.dockerGraph), independent)
      assertTrue(
        wf.jobs.contains("modver"),
        wf.jobs.contains("publish-client"),
        wf.jobs.keys.exists(_.startsWith("docker")),
        wf.jobs.values.exists(j => j.`if`.exists(_.contains("refs/tags/v")) && j.name.exists(_.contains("docker"))),
      )
    },
    test("Graph publish if contains the compact array id and no all sentinel") {
      val wf  = Planner.plan(graph, List(cap), independent)
      val iff = wf.jobs("publish-client").`if`.getOrElse("")
      assertTrue(
        iff.contains("contains(fromJson(needs.modver.outputs.modules), 'client')"),
        !iff.contains("'all'"),
        iff.contains("github.event_name == 'push'"),
        iff.contains("github.ref == 'refs/heads/main'"),
        iff.contains("workflow_dispatch"),
      )
    },
    test("only client in JSON still lets publish-client run when coreLib skipped") {
      val wf  = Planner.plan(graph, List(cap), independent)
      val iff = wf.jobs("publish-client").`if`.getOrElse("")
      assertTrue(
        wf.jobs("publish-client").needs.contains("publish-coreLib"),
        wf.jobs("publish-client").needs.contains("modver"),
        iff.contains("needs.publish-coreLib.result != 'failure'"),
        iff.contains("!cancelled()"),
      )
    },
    test("modver job is OnDefaultPush and cats the array") {
      val wf  = Planner.plan(graph, List(cap), independent)
      val job = wf.jobs("modver")
      val run = job.steps.last.run.getOrElse("")
      assertTrue(
        job.`if`.exists(_.contains("workflow_dispatch")),
        job.`if`.exists(_.contains("refs/heads/main")),
        run.contains("zipxModverPublishModules"),
        run.contains("target/zipx-modver-modules.json"),
        !run.contains("[\\\"all\\\"]"),
        !run.contains("[\"all\"]"),
      )
    },
    test("dispatch is registry-only; a bad before fails closed") {
      val run = Planner.plan(graph, List(cap), independent).jobs("modver").steps.last.run.getOrElse("")
      assertTrue(
        run.contains("workflow_dispatch"),
        run.contains("zipxModverPublishModules"),
        run.contains("0000000000000000000000000000000000000000"),
        run.contains("exit 1"),
      )
    },
    test("allJobIds matches emitted Graph publish keys") {
      val ids = Planner.allJobIds(cap, graph, independent).map(id => id: String).sorted
      val wf  = Planner.plan(graph, List(cap), independent)
      val pub = wf.jobs.keys.filter(_.startsWith("publish-")).toList.sorted
      assertTrue(ids == pub, wf.jobs.contains("modver"))
    },
    test("default-branch runs are not cancelled when modverPublish is on") {
      val c = Planner.plan(graph, List(cap), independent).concurrency
      assertTrue(
        c.exists(_.cancelInProgress.contains("refs/heads/main")),
        c.exists(_.cancelInProgress.contains("refs/tags/")),
      )
    },
    test("workflow_dispatch is on when modverPublish is on") {
      val wf = Planner.plan(graph, List(cap), independent)
      assertTrue(wf.on.workflowDispatch)
    },
    test("Aggregate library publish is refused when ships are present") {
      val err = scala.util.Try(Planner.plan(graph, List(Capability.publish), independent)).failed.get
      assertTrue(
        err.getMessage.contains("Ship rows require Graph publish"),
        err.getMessage.contains("ZipxModver.publish"),
      )
    },
    test("OnReleaseTag library publish is refused when ships are present") {
      val err = scala.util.Try(Planner.plan(graph, List(Capability.publishGraph), independent)).failed.get
      assertTrue(err.getMessage.contains("cannot use Gate.OnReleaseTag as the publish gate"))
    },
    test("OnDefaultPush and a tag ref condition is refused") {
      val bad = cap.copy(condition = Some(JobCondition.refIs("refs/tags/v1.0.0")))
      val err = scala.util.Try(Planner.plan(graph, List(bad), independent)).failed.get
      assertTrue(err.getMessage.contains("can never run"))
    },
    test("ModverRegistry pomUrl and registryStatus") {
      val gav = Gav("org.foo", "bar_3", "1.4.2")
      assertTrue(
        ModverRegistry.MavenCentral.pomUrl(gav).contains("repo1.maven.org"),
        ModverRegistry.GitHubPackages("acme", "libs").pomUrl(gav).contains("maven.pkg.github.com/acme/libs"),
        Modver.registryStatus(200) == Right(RegistryStatus.Published),
        Modver.registryStatus(404) == Right(RegistryStatus.Missing),
        Modver.registryStatus(410) == Right(RegistryStatus.Missing),
        Modver.registryStatus(500).isLeft,
      )
    },
    test("epochHash is 16 hex chars and stable") {
      val ships = List[PublishedRow](Ship("client", "0.3.0"), ShipGroup("libs", "1.4.2")("models", "coreLib"))
      val hash  = Modver.epochHash(ships)
      assertTrue(
        hash.length == 16,
        hash == Modver.epochHash(ships.reverse),
        hash != Modver.epochHash(List(Ship("client", "0.3.1"))),
      )
    },
    test("ShipCatalog bakes the hash as cache-epoch") {
      val hash = Modver.epochHash(List(Ship("client", "0.3.0")))
      val step = ZipxComposites.sbtSetupStep(
        PlanConfig(cacheEpoch = CacheEpoch.ShipCatalog, shipEpochHash = Some(hash)),
        JobId("test"),
        None,
        localCache = true,
      )
      assertTrue(step.`with`.get("cache-epoch").contains(hash))
    },
    test("mixed Missing binaries keep only those GAVs") {
      val index = ShipIndex.from(List(Ship("client", "0.3.1")))
      val g     = GraphFixture(List(ModuleNode(ModuleId("client"), publishes = true)))
      val g213  = Gav("org", "client_2.13", "0.3.1")
      val g3    = Gav("org", "client_3", "0.3.1")
      val moved =
        MovedRows(versionChanged = Set(ShipRef.One(ModuleId("client"))), added = Set.empty, newMembers = Set.empty)
      val lookup: Gav => Either[String, RegistryStatus] =
        gav =>
          if gav == g213 then Right(RegistryStatus.Published)
          else if gav == g3 then Right(RegistryStatus.Missing)
          else Left("unexpected")
      Modver.filterUnpublished(moved, index, g, _ => List(g213, g3), lookup) match
        case Left(err)  => assertTrue(err.isEmpty)
        case Right(pub) => assertTrue(pub.get(ModuleId("client")).contains(List(g3)))
    },
  )
end ModverPublishSpec
