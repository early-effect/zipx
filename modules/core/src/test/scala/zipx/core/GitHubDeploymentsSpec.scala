package zipx.core

import zio.test.*

object GitHubDeploymentsSpec extends ZIOSpecDefault:

  private val a = "a" * 40
  private val b = "b" * 40

  private def node(env: String, state: String, url: String): String =
    s"""{"environment":"$env","latestStatus":{"state":"$state","environmentUrl":"$url"}}"""

  private def response(nodes: String*): String =
    s"""{"data":{"repository":{"deployments":{"nodes":[${nodes.mkString(",")}]}}}}"""

  private def commit(sha: String, module: String) = s"https://github.com/o/r/commit/$sha#$module"

  def spec = suite("GitHubDeployments")(
    test("the query names the repository and asks for each environment once, newest first") {
      val body = GitHubDeployments.query("early-effect/zipx-ci-lab", List("lab-stg", "zipx-images", "lab-stg"))
      assertTrue(
        body.exists(_.contains(""""owner":"early-effect","name":"zipx-ci-lab","envs":["lab-stg","zipx-images"]""")),
        body.exists(_.contains("direction:DESC")),
        GitHubDeployments.query("not-a-slug", Nil).isLeft,
      )
    },
    test("the newest successful deploy of each module wins, read from the url zipx wrote") {
      val body = response(
        node("lab-stg", "FAILURE", commit(b, "workerA")),
        node("lab-stg", "SUCCESS", commit(a, "workerA")),
        node("lab-stg", "SUCCESS", commit(b, "workerA")),
        node("zipx-images", "SUCCESS", commit(b, "svcB")),
      )
      assertTrue(
        GitHubDeployments.parse(body) == Right(
          List(
            LastDeploy("lab-stg", ModuleId("workerA"), GitSha.unsafeMake(a)),
            LastDeploy("zipx-images", ModuleId("svcB"), GitSha.unsafeMake(b)),
          )
        )
      )
    },
    test("a deploy zipx did not make, or with no status yet, never narrows changed") {
      val body = response(
        node("lab-stg", "SUCCESS", "https://example.com/stg"),
        """{"environment":"lab-stg","latestStatus":null}""",
      )
      assertTrue(GitHubDeployments.parse(body) == Right(Nil))
    },
    test("GraphQL errors and unexpected bodies are failures, not an empty history") {
      assertTrue(
        GitHubDeployments
          .parse("""{"errors":[{"message":"Resource not accessible by integration"}]}""")
          .left
          .exists(_.contains("Resource not accessible")),
        GitHubDeployments.parse("<html>").isLeft,
      )
    },
    test("lookup authenticates with the token, and reports a non-200 with its status") {
      val github: GitHubDeployments.Post = (_, body, headers) =>
        val authorized = headers.get("Authorization").contains("Bearer tkn") && body.contains("lab-prd")
        val status     = if authorized then 200 else 401
        Right(HttpLookupResult(status, response(node("lab-prd", "SUCCESS", commit(a, "workerB"))), Map.empty))
      assertTrue(
        GitHubDeployments.lookup("o/r", "tkn", List("lab-prd"), post = github).map(_.map(_.module)) ==
          Right(List(ModuleId("workerB"))),
        GitHubDeployments.lookup("o/r", "wrong", List("lab-prd"), post = github).left.exists(_.contains("HTTP 401")),
      )
    },
  )
end GitHubDeploymentsSpec
