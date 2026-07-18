package zipx.core

import zio.test.*
import zipx.workflow.*

/** M6e — end-to-end capability proof. Plans the FULL set of capabilities (test → publish → docker → gated multi-target
  * deploy) together on the sample graph and asserts the complete pipeline holds. Where the per-capability specs check
  * one behavior in isolation, this catches interaction bugs: phase ordering across capabilities, cross-capability
  * `needs`, and that a realistic multi-environment build generates entirely from the model with no external config.
  */
object PipelineSpec extends ZIOSpecDefault:
  import Fixtures.*
  import EnvValue.secret

  // A graph where serviceA is a docker image AND a deploy target (the "app" shape), alongside the publishing libraries.
  private val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
    case n if n.id == "serviceA" => n.copy(docker = true)
    case n                       => n
  })

  private val deployTargets = List(
    Target(
      "staging",
      env = Map(
        "AWS_REGION"  -> EnvValue.plain("us-west-2"),
        "DEPLOY_ROLE" -> secret"STAGING_ROLE",
        "TIER"        -> EnvValue.plain("staging"),
      ),
    ),
    Target(
      "prod",
      environment = Some("production"),
      env = Map(
        "AWS_REGION"  -> EnvValue.plain("us-east-1"),
        "DEPLOY_ROLE" -> secret"PROD_ROLE",
        "TIER"        -> EnvValue.plain("prod"),
      ),
      condition = Some("github.ref == 'refs/heads/main'"),
    ),
  )

  private val deploy = Capability
    .deployGraph(
      participates = _.id == "serviceA",
      command = n => s"${n.id}/Docker/publish",
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

  private val config = PlanConfig(cacheEpoch = "9.9.9", affected = AffectedMode.Always, skipMergedPrPush = false)

  private val wf =
    Planner.plan(graph, List(Capability.testGraph, Capability.publishGraph, Capability.dockerGraph, deploy), config)
  private def job(id: String) = wf.jobs(id)

  def spec = suite("Pipeline (M6e end-to-end)")(
    test("the full pipeline emits every stage's jobs") {
      assertTrue(
        // Verify: one test job per module.
        wf.jobs.contains("test-schema"),
        wf.jobs.contains("test-serviceA"),
        // Publish: only for publishing libraries, not the service.
        wf.jobs.contains("publish-schema"),
        !wf.jobs.contains("publish-serviceA"),
        // Docker: only for the opted-in service.
        wf.jobs.contains("docker-serviceA"),
        // Deploy: one job per target.
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
      assertTrue(Render.render(wf) == Render.render(wf))
    },
  )
end PipelineSpec
