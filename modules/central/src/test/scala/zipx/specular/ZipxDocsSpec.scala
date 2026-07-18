package zipx.specular

import zio.test.*
import zipx.core.*

object ZipxDocsSpec extends ZIOSpecDefault:

  private val config = PlanConfig(workflowName = "CI", cacheEpoch = "1.0.0", affected = AffectedMode.Always)

  def spec = suite("ZipxDocs")(
    test("pages emits a reusable-workflow job with Pages permissions, gated on release tags") {
      val wf  = Planner.plan(ModuleGraph(Nil), List(ZipxDocs.pages()), config)
      val job = wf.jobs("docs")
      assertTrue(
        job.uses.contains(ZipxDocs.ReusableWorkflow),
        job.`with`.get("sbt-project").contains("docs"),
        job.steps.isEmpty,
        job.runsOn.isEmpty,
        job.permissions.get("pages").contains("write"),
        job.permissions.get("id-token").contains("write"),
        job.`if`.exists(_.contains("refs/tags/v")),
      )
    },
    test("pages forwards sbtProject and javaVersion inputs") {
      val job = Planner
        .plan(ModuleGraph(Nil), List(ZipxDocs.pages(sbtProject = "site", javaVersion = Some("25"))), config)
        .jobs("docs")
      assertTrue(
        job.`with`.get("sbt-project").contains("site"),
        job.`with`.get("java-version").contains("25"),
      )
    },
  )
end ZipxDocsSpec
