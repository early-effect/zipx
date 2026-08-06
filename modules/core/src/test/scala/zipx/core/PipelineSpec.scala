package zipx.core

import zio.test.*
import zipx.workflow.*

object PipelineSpec extends ZIOSpecDefault:
  import Fixtures.*
  import EnvValue.secret

  private val graph = sampleGraph.mapNodes {
    case n if n.id == "serviceA" => n.copy(docker = true)
    case n                       => n
  }

  private val deployTargets = List(
    Target(
      TargetName("staging"),
      env = Map(
        "AWS_REGION"  -> EnvValue.plain("us-west-2"),
        "DEPLOY_ROLE" -> secret"STAGING_ROLE",
        "TIER"        -> EnvValue.plain("staging"),
      ),
    ),
    Target(
      TargetName("prod"),
      environment = Some("production"),
      env = Map(
        "AWS_REGION"  -> EnvValue.plain("us-east-1"),
        "DEPLOY_ROLE" -> secret"PROD_ROLE",
        "TIER"        -> EnvValue.plain("prod"),
      ),
      condition = Some(JobCondition.refIs("refs/heads/main")),
    ),
  )

  private val deploy = Capability
    .deployGraph(
      participates = _.id == "serviceA",
      command = n => SbtCommand.module(n, SbtCommand("Docker/publish")),
      targets = _ => deployTargets,
      permissions = Map("id-token" -> "write", "contents" -> "read"),
    )
    .copy(
      extraSteps = _ =>
        List(
          Step(
            name = Some("Configure credentials"),
            uses = Some("aws-actions/configure-aws-credentials@v6"),
            `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}", "aws-region" -> "${{ env.AWS_REGION }}"),
          )
        )
    )

  private val config =
    PlanConfig(cacheEpoch = CacheEpoch.Fixed("9.9.9"), affected = AffectedMode.Always, skipMergedPrPush = false)

  private val wf =
    Planner.plan(graph, List(Capability.testGraph, Capability.publishGraph, Capability.dockerGraph, deploy), config)
  private def job(id: String) = wf.jobs(id)

  def spec = suite("Pipeline (M6e end-to-end)")(
    test("the full pipeline emits every stage's jobs") {
      assertTrue(
        wf.jobs.contains("test-schema"),
        wf.jobs.contains("test-serviceA"),
        wf.jobs.contains("publish-schema"),
        !wf.jobs.contains("publish-serviceA"),
        wf.jobs.contains("docker-serviceA"),
        wf.jobs.contains("deploy-serviceA-staging"),
        wf.jobs.contains("deploy-serviceA-prod"),
      )
    },
    test("publishing is dependency-ordered (schema → api → clients)") {
      assertTrue(
        job("publish-schema").needs == Nil,
        job("publish-api").needs == List("publish-schema"),
        job("publish-clientA").needs == List("publish-api"),
      )
    },
    test("deploy depends on the service's docker job (cross-capability, cross-phase)") {
      assertTrue(
        job("deploy-serviceA-prod").needs.contains("docker-serviceA"),
        job("deploy-serviceA-staging").needs.contains("docker-serviceA"),
      )
    },
    test("only the prod target carries the approval environment") {
      assertTrue(
        job("deploy-serviceA-prod").environment.contains("production"),
        job("deploy-serviceA-staging").environment.isEmpty,
      )
    },
    test("deploy jobs carry OIDC permissions and the credential step") {
      val prod = job("deploy-serviceA-prod")
      assertTrue(
        prod.permissions.get("id-token").contains("write"),
        prod.steps.exists(_.uses.contains("aws-actions/configure-aws-credentials@v6")),
        prod.env.get("DEPLOY_ROLE").contains("${{ secrets.PROD_ROLE }}"),
      )
    },
    test("phase order in the YAML: test before publish before docker before deploy") {
      val keys                         = wf.jobs.keys.toList
      def firstIndexOf(prefix: String) = keys.indexWhere(_.startsWith(prefix))
      assertTrue(
        firstIndexOf("test-") < firstIndexOf("publish-"),
        firstIndexOf("publish-") < firstIndexOf("docker-"),
        firstIndexOf("docker-") < firstIndexOf("deploy-"),
      )
    },
    test("the whole workflow renders deterministically (byte-identical twice)") {
      assertTrue(Render.render(wf).isRight, Render.render(wf) == Render.render(wf))
    },
  )
end PipelineSpec
