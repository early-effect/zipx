package zipx.core

import zio.test.*

/** The coverage capability (#74).
  *
  * What is actually under test is one thing: the measured task is never a bare `test`. On sbt 2.0 that is `testQuick`,
  * so a coverage job running it prints "No tests to run", reports near-zero, and passes. Every assertion here exists to
  * make that regression a red test rather than a green pipeline.
  */
object CoverageSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val config = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private def plan(capabilities: Capability*) = Planner.plan(sampleGraph, capabilities.toList, config)

  /** The command step: the last step that runs sbt, since `postSteps` puts the upload after it. */
  private def command(job: zipx.workflow.Job): String =
    job.steps.flatMap(_.run).filter(_.contains("sbt ")).lastOption.getOrElse("")

  def spec = suite("Coverage")(
    suite("the measured task is never sbt 2's `test`")(
      test("a module that overrode zipxTestTask gets its own task, in the module scope") {
        val wf = plan(Coverage.graph())
        assertTrue(
          command(wf.jobs("coverage-serviceA")).contains("coverage; serviceA/testFull; serviceA/coverageReport")
        )
      },
      test("a module that did not override it gets testFull, not the `test` default") {
        // `schema` carries ModuleNode.DefaultTestTask, which is where the footgun lives.
        val wf = plan(Coverage.graph())
        assertTrue(
          sampleGraph.nodes.find(_.id == "schema").exists(_.testTask == ModuleNode.DefaultTestTask),
          command(wf.jobs("coverage-schema")).contains("coverage; schema/testFull; schema/coverageReport"),
        )
      },
      test("no coverage job anywhere runs a bare `test`") {
        val wf       = plan(Coverage.graph())
        val commands = wf.jobs.collect { case (id, job) if id.startsWith("coverage") => command(job) }.toList
        assertTrue(
          commands.nonEmpty,
          commands.forall(c => !c.contains("/test'") && !c.contains("/test;")),
          commands.forall(_.contains("testFull")),
        )
      },
      test("the once form runs testFull too, since a root `test` is testQuick as well") {
        assertTrue(command(plan(Coverage.once()).jobs("coverage")) == "sbt 'coverage; testFull; coverageAggregate'")
      },
      test("measuredTask substitutes only for the default, leaving an explicit choice alone") {
        val default  = ModuleNode(ModuleId("a"))
        val explicit = ModuleNode(ModuleId("b"), testTask = SbtCommand.unsafeTask("Test/testOnly com.example.*"))
        assertTrue(
          Coverage.measuredTask(default).text == "testFull",
          Coverage.measuredTask(explicit) == explicit.testTask,
        )
      },
      test("`_.testTask` opts into literal inheritance, default and all") {
        val wf = plan(Coverage.graph(task = _.testTask))
        assertTrue(
          command(wf.jobs("coverage-schema")).contains("coverage; schema/test; schema/coverageReport"),
          command(wf.jobs("coverage-serviceA")).contains("serviceA/testFull"),
        )
      },
    ),
    suite("the command is one sbt session, because `coverage` is a session toggle")(
      test("enable, task and report share a single invocation") {
        val run = command(plan(Coverage.graph()).jobs("coverage-serviceA"))
        assertTrue(run.startsWith("sbt 'coverage;"), run.count(_ == ';') == 2)
      },
      test("no trailing coverageOff: the session ends with the job") {
        assertTrue(!command(plan(Coverage.once()).jobs("coverage")).contains("coverageOff"))
      },
      test("the command is Built, so it raises no unchecked-fragment warning") {
        val caps = List(Coverage.once(), Coverage.graph())
        assertTrue(Steps.rawWarnings(caps, config).isEmpty)
      },
    ),
    suite("report upload")(
      test("on by default, using the pinned uploadArtifact action") {
        val steps = plan(Coverage.once()).jobs("coverage").steps
        assertTrue(
          steps.last.uses.contains(ActionPins.Defaults.uploadArtifact),
          steps.last.`with`.get("name").contains(Coverage.DefaultArtifact),
          steps.last.`with`.get("if-no-files-found").contains("error"),
        )
      },
      test("Graph jobs name the artifact per module, so six jobs do not collide on one name") {
        val wf    = plan(Coverage.graph())
        val names = wf.jobs.collect {
          case (id, job) if id.startsWith("coverage") => job.steps.last.`with`.get("name")
        }.toList
        assertTrue(
          names.forall(_.isDefined),
          names.distinct.size == names.size,
          wf.jobs("coverage-serviceA").steps.last.`with`.get("name").contains("coverage-report-serviceA"),
        )
      },
      test("uploadReport = false emits no upload step") {
        val steps = plan(Coverage.once(uploadReport = false)).jobs("coverage").steps
        assertTrue(steps.forall(s => !s.uses.contains(ActionPins.Defaults.uploadArtifact)))
      },
    ),
    suite("it is an ordinary Verify capability, so the planner's gating applies unchanged")(
      test("Graph coverage is affected-gated") {
        val gated = config.copy(affected = AffectedMode.AffectedOnPR)
        val wf    = Planner.plan(sampleGraph, List(Coverage.graph()), gated)
        assertTrue(wf.jobs("coverage-serviceA").`if`.exists(_.contains("affected")))
      },
      test("a distinct name coexists with the built-in test capability") {
        val wf = plan(Capability.testGraph, Coverage.graph(name = CapabilityName("cov")))
        assertTrue(wf.jobs.contains("test-serviceA"), wf.jobs.contains("cov-serviceA"))
      },
    ),
  )
end CoverageSpec
