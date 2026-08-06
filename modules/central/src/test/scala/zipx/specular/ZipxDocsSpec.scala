package zipx.specular

import zio.test.*
import zipx.core.*

object ZipxDocsSpec extends ZIOSpecDefault:

  private val config =
    PlanConfig(
      workflowName = WorkflowName("CI"),
      cacheEpoch = CacheEpoch.Fixed("1.0.0"),
      affected = AffectedMode.Always,
    )

  def spec = suite("ZipxDocs")(
    test("pages emits a reusable-workflow job with Pages permissions, on tag or workflow_dispatch") {
      val wf  = Planner.plan(GraphFixture(Nil), List(ZipxDocs.pages()), config)
      val job = wf.jobs("docs")
      assertTrue(
        job.uses.contains(ZipxDocs.ReusableWorkflow),
        job.`with`.get("sbt-project").contains("docs"),
        job.steps.isEmpty,
        job.runsOn.isEmpty,
        job.permissions.get("pages").contains("write"),
        job.permissions.get("id-token").contains("write"),
        job.`if`.exists(_.contains("refs/tags/v")),
        job.`if`.exists(_.contains("workflow_dispatch")),
        job.`if`.exists(_.contains("||")),
      )
    },
    test("pages andCondition layers a fork gate without wiping tag|dispatch") {
      val job = Planner
        .plan(
          GraphFixture(Nil),
          List(ZipxDocs.pages().andCondition(JobCondition.repositoryIs("early-effect/zipx"))),
          config,
        )
        .jobs("docs")
      assertTrue(
        job.`if`.exists(_.contains("workflow_dispatch")),
        job.`if`.exists(_.contains("early-effect/zipx")),
        job.`if`.exists(_.contains("&&")),
      )
    },
    test("pages forwards sbtProject and javaVersion inputs") {
      val job = Planner
        .plan(
          GraphFixture(Nil),
          List(ZipxDocs.pages(sbtProject = "site", javaVersion = Some(JdkVersion("25")))),
          config,
        )
        .jobs("docs")
      assertTrue(
        job.`with`.get("sbt-project").contains("site"),
        job.`with`.get("java-version").contains("25"),
      )
    },
    test("pages omits PlanConfig.env so GHA accepts the uses: caller job") {
      val job = Planner
        .plan(
          GraphFixture(Nil),
          List(ZipxDocs.pages()),
          config.copy(env = Map("PLAYWRIGHT_BROWSERS_PATH" -> EnvValue.plain("/tmp/browsers"))),
        )
        .jobs("docs")
      assertTrue(
        job.uses.contains(ZipxDocs.ReusableWorkflow),
        job.env.isEmpty,
        job.runsOn.isEmpty,
      )
    },
  )
end ZipxDocsSpec
