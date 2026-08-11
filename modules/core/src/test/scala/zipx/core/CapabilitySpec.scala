package zipx.core

import zio.test.*

object CapabilitySpec extends ZIOSpecDefault:

  private val api = ModuleNode(ModuleId("api"), publishes = true, crossScalaVersions = List("3.3.6", "2.13.16"))

  def spec = suite("Capability")(
    suite("CommandSource setters")(
      test("running sets Fixed and changes nothing else material") {
        val base = Capability.testGraph
        val next = base.running(SbtCommand.unsafeTask("about"))
        assertTrue(
          next.command == CommandSource.Fixed(SbtCommand.unsafeTask("about")),
          next.name == base.name,
          next.scope == base.scope,
          next.sessionTail.isEmpty,
        )
      },
      test("runningEach scopes with module; runningEachCross sets cross when needed") {
        val each  = Capability.publish.runningEach(SbtCommand.unsafeTask("publishSigned"))
        val cross = Capability.publish.runningEachCross(SbtCommand.unsafeTask("publishSigned"))
        assertTrue(
          each.command.commandFor(api).text == "api/publishSigned",
          cross.command.commandFor(api).text == "+api/publishSigned",
        )
      },
      test("runningNothing is ActionsOnly") {
        assertTrue(Capability.test.runningNothing.command == CommandSource.ActionsOnly)
      },
      test("thenOnce accumulates") {
        val cap = Capability.publish
          .thenOnce(SbtCommand.unsafeCommand("sonaRelease"))
          .thenOnce(SbtCommand.unsafeTask("about"))
        assertTrue(cap.sessionTail.map(_.text).contains("sonaRelease; about"))
      },
      test("sessionCommand appends the tail") {
        val cap  = Capability.test.thenOnce(SbtCommand.unsafeTask("docs/specularSite"))
        val base = SbtCommand.unsafeTask("testFull")
        assertTrue(
          cap.sessionCommand(Some(base)).map(_.text).contains("testFull; docs/specularSite"),
          cap.sessionCommand(None).map(_.text).contains("docs/specularSite"),
        )
      },
    ),
    suite("sessionTail validation")(
      test("Aggregate + thenOnce plans with the tail once") {
        val graph = GraphFixture(List(api, ModuleNode(ModuleId("core"), publishes = true)))
        val cap   = Capability.publish
          .runningEach(SbtCommand.unsafeTask("publishSigned"))
          .thenOnce(SbtCommand.unsafeCommand("sonaRelease"))
        val run = Planner
          .plan(graph, List(cap), PlanConfig())
          .jobs("publish")
          .steps
          .flatMap(_.run)
          .mkString
        assertTrue(
          run.contains("api/publishSigned"),
          run.contains("core/publishSigned"),
          run.contains("sonaRelease"),
        )
      },
      test("Layer + thenOnce is rejected with a fix naming Aggregate") {
        val err = scala.util
          .Try(
            Planner.plan(
              GraphFixture(List(api)),
              List(Capability.publishLayers.thenOnce(SbtCommand.unsafeCommand("sonaRelease"))),
              PlanConfig(),
            )
          )
          .failed
          .get
          .getMessage
        assertTrue(err.contains("publish"), err.contains("Layer"), err.contains("Aggregate"))
      },
      test("Graph + thenOnce is rejected") {
        val err = scala.util
          .Try(
            Planner.plan(
              GraphFixture(List(api)),
              List(Capability.publishGraph.thenOnce(SbtCommand.unsafeCommand("sonaRelease"))),
              PlanConfig(),
            )
          )
          .failed
          .get
          .getMessage
        assertTrue(err.contains("Graph"), err.contains("per module"))
      },
      test("ActionsOnly + thenOnce is rejected") {
        val err = scala.util
          .Try(
            Planner.plan(
              GraphFixture(List(api)),
              List(Capability.steps(CapabilityName("pages"), _ => Nil).thenOnce(SbtCommand.unsafeTask("noop"))),
              PlanConfig(),
            )
          )
          .failed
          .get
          .getMessage
        assertTrue(err.contains("ActionsOnly"))
      },
    ),
  )
end CapabilitySpec
