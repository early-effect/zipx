package zipx.github

import zio.test.*
import zipx.core.*

object ZipxGitHubPackagesSpec extends ZIOSpecDefault:

  private val config = PlanConfig(
    workflowName = "CI",
    cacheEpoch = "1.0.0",
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  private val graph = ModuleGraph(
    List(
      ModuleNode("lib", publishes = true, crossScalaVersions = List("3.3.3"))
    )
  )

  def spec = suite("ZipxGitHubPackages")(
    test("sameRepo sets packages permissions, github.token, and publish flag") {
      val cap = ZipxGitHubPackages.sameRepo()
      val job = Planner.plan(graph, List(cap), config).jobs("github-packages")
      assertTrue(
        cap.name == "github-packages",
        cap.permissions.get("packages").contains("write"),
        cap.permissions.get("contents").contains("read"),
        job.env.get("GITHUB_TOKEN").contains("${{ github.token }}"),
        job.env.get("PUBLISH_GITHUB_PACKAGES").contains("true"),
        job.`if`.exists(_.contains("refs/tags/v")),
      )
    },
    test("sameRepo repository becomes JobCondition.repositoryIs") {
      val cap = ZipxGitHubPackages.sameRepo(repository = Some("acme/fork"))
      assertTrue(
        cap.condition.contains(JobCondition.repositoryIs("acme/fork")),
        Planner
          .plan(graph, List(cap), config)
          .jobs("github-packages")
          .`if`
          .exists(_.contains("github.repository == 'acme/fork'")),
      )
    },
    test("sharedRegistry uses secret token and optional registry env") {
      val cap = ZipxGitHubPackages.sharedRegistry(
        tokenSecret = "GH_PACKAGES_TOKEN",
        packagesRepo = Some("https://maven.pkg.github.com/acme/pkgs"),
        publishOrg = Some("acme"),
      )
      val job = Planner.plan(graph, List(cap), config).jobs("github-packages")
      assertTrue(
        job.env.get("GITHUB_TOKEN").contains("${{ secrets.GH_PACKAGES_TOKEN }}"),
        job.env.get("PUBLISH_PACKAGES_REPO").contains("https://maven.pkg.github.com/acme/pkgs"),
        job.env.get("PUBLISH_ORG").contains("acme"),
        job.env.get("PUBLISH_GITHUB_PACKAGES").contains("true"),
      )
    },
    test("coexists with ZipxCentral-shaped publish under a distinct name") {
      val central = Capability.publish
      val ghp     = ZipxGitHubPackages.sameRepo(repository = Some("acme/fork"))
      val wf      = Planner.plan(graph, List(central, ghp), config)
      assertTrue(
        wf.jobs.keySet.contains("publish"),
        wf.jobs.keySet.contains("github-packages"),
        !wf.jobs("publish").`if`.exists(_.contains("acme/fork")),
        wf.jobs("github-packages").`if`.exists(_.contains("acme/fork")),
      )
    },
    test("Graph scope fans out per publishing module") {
      val cap = ZipxGitHubPackages.sameRepo(scope = CapabilityScope.Graph)
      val wf  = Planner.plan(graph, List(cap), config)
      assertTrue(wf.jobs.contains("github-packages-lib"))
    },
    test("explicit condition ANDs with repository") {
      val cap = ZipxGitHubPackages.sameRepo(
        repository = Some("acme/fork"),
        condition = Some(JobCondition.varNonEmpty("EXTRA")),
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
