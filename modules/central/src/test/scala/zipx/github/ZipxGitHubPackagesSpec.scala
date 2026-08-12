package zipx.github

import zio.test.*
import zipx.core.*

object ZipxGitHubPackagesSpec extends ZIOSpecDefault:

  private val config = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.0.0"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  private val graph = GraphFixture(
    List(
      ModuleNode(ModuleId("lib"), publishes = true, crossScalaVersions = List("3.3.3")),
      ModuleNode(ModuleId("util"), publishes = true, crossScalaVersions = List("3.3.3")),
    )
  )

  private val gMode: Gen[Any, MatrixCollapse] =
    Gen.elements(MatrixCollapse.values.toList*)

  def spec = suite("ZipxGitHubPackages")(
    test("sameRepo sets packages permissions, github.token, and publish flag under every collapse mode") {
      check(gMode) { mode =>
        val cap  = ZipxGitHubPackages.sameRepo().withMatrixCollapse(mode)
        val wf   = Planner.plan(graph, List(cap), config)
        val jobs = Planner.allJobIds(cap, graph, config).map(id => id: String).map(wf.jobs(_))
        assertTrue(
          cap.name == "github-packages",
          cap.permissions.get("packages").contains("write"),
          cap.permissions.get("contents").contains("read"),
          jobs.nonEmpty,
          jobs.forall { job =>
            job.env.get("GITHUB_TOKEN").contains("${{ github.token }}") &&
            job.env.get("PUBLISH_GITHUB_PACKAGES").contains("true") &&
            job.`if`.exists(_.contains("refs/tags/v"))
          },
        )
      }
    },
    test("a fork gate is a JobCondition like any other") {
      check(gMode) { mode =>
        val cap = ZipxGitHubPackages
          .sameRepo(condition = Some(JobCondition.repositoryIs("acme/fork")))
          .withMatrixCollapse(mode)
        val wf   = Planner.plan(graph, List(cap), config)
        val jobs = Planner.allJobIds(cap, graph, config).map(id => id: String).map(wf.jobs(_))
        assertTrue(
          cap.condition.contains(JobCondition.repositoryIs("acme/fork")),
          jobs.forall(_.`if`.exists(_.contains("github.repository == 'acme/fork'"))),
        )
      }
    },
    test("sharedRegistry uses secret token and optional registry env") {
      check(gMode) { mode =>
        val cap = ZipxGitHubPackages
          .sharedRegistry(
            token = EnvValue.secret("GH_PACKAGES_TOKEN"),
            packagesRepo = Some("https://maven.pkg.github.com/acme/pkgs"),
            publishOrg = Some("acme"),
          )
          .withMatrixCollapse(mode)
        val wf   = Planner.plan(graph, List(cap), config)
        val jobs = Planner.allJobIds(cap, graph, config).map(id => id: String).map(wf.jobs(_))
        assertTrue(
          jobs.forall { job =>
            job.env.get("GITHUB_TOKEN").contains("${{ secrets.GH_PACKAGES_TOKEN }}") &&
            job.env.get("PUBLISH_PACKAGES_REPO").contains("https://maven.pkg.github.com/acme/pkgs") &&
            job.env.get("PUBLISH_ORG").contains("acme") &&
            job.env.get("PUBLISH_GITHUB_PACKAGES").contains("true")
          }
        )
      }
    },
    test("coexists with ZipxCentral-shaped publish under a distinct name") {
      check(gMode) { mode =>
        val central = Capability.publish.withMatrixCollapse(mode)
        val ghp     = ZipxGitHubPackages
          .sameRepo(condition = Some(JobCondition.repositoryIs("acme/fork")))
          .withMatrixCollapse(mode)
        val wf = Planner.plan(graph, List(central, ghp), config)
        assertTrue(
          Planner.allJobIds(central, graph, config).map(id => id: String).forall(wf.jobs.contains),
          Planner.allJobIds(ghp, graph, config).map(id => id: String).forall(wf.jobs.contains),
          wf.jobs("github-packages").`if`.exists(_.contains("acme/fork")) ||
            Planner
              .allJobIds(ghp, graph, config)
              .map(id => id: String)
              .forall(id => wf.jobs(id).`if`.exists(_.contains("acme/fork"))),
          !wf.jobs("publish").`if`.exists(_.contains("acme/fork")),
        )
      }
    },
    test("Graph scope job ids match allJobIds under every collapse mode") {
      check(gMode) { mode =>
        val cap  = ZipxGitHubPackages.sameRepo(scope = CapabilityScope.Graph).withMatrixCollapse(mode)
        val wf   = Planner.plan(graph, List(cap), config)
        val ids  = Planner.allJobIds(cap, graph, config).map(id => id: String).sorted
        val keys =
          wf.jobs.keys.filter(k => k == "github-packages" || k.startsWith("github-packages-")).toList.sorted
        assertTrue(ids == keys, ids.nonEmpty)
      }
    },
    test("a fork gate ANDs with a second filter through the condition itself") {
      val cap = ZipxGitHubPackages.sameRepo(
        condition = Some(JobCondition.repositoryIs("acme/fork") && JobCondition.varNonEmpty("EXTRA"))
      )
      val rendered = cap.condition.map(_.render).getOrElse("")
      assertTrue(
        rendered.contains("github.repository == 'acme/fork'"),
        rendered.contains("vars.EXTRA != ''"),
        rendered.contains("&&"),
      )
    },
  )
end ZipxGitHubPackagesSpec
