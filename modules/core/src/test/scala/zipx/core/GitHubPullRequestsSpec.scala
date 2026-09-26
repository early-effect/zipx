package zipx.core

import zio.test.*

object GitHubPullRequestsSpec extends ZIOSpecDefault:

  private val merge = GitSha.unsafeMake("a" * 40)
  private val other = "b" * 40

  private def pr(mergeCommit: Option[String], labels: String*): String =
    val commit = mergeCommit.fold("null")(oid => s"""{"oid":"$oid"}""")
    val names  = labels.map(l => s"""{"name":"$l"}""").mkString(",")
    s"""{"mergeCommit":$commit,"labels":{"nodes":[$names]}}"""

  private def response(prs: String*): String =
    s"""{"data":{"repository":{"object":{"associatedPullRequests":{"nodes":[${prs.mkString(",")}]}}}}}"""

  def spec = suite("GitHubPullRequests")(
    test("the query names the repository and the pushed commit") {
      val body = GitHubPullRequests.query("early-effect/zipx-ci-lab", merge)
      assertTrue(
        body.exists(_.contains(s""""owner":"early-effect","name":"zipx-ci-lab","oid":"$merge"""")),
        body.exists(_.contains("associatedPullRequests")),
        GitHubPullRequests.query("not-a-slug", merge).isLeft,
      )
    },
    test("reads the labels of the PR whose merge commit was pushed, and no other") {
      val body = response(pr(Some(other), "no-deploy"), pr(Some(merge.toUpperCase), "docs", "coverage"), pr(None, "x"))
      assertTrue(GitHubPullRequests.parse(body, merge) == Right(Set("docs", "coverage")))
    },
    test("a direct push, which merged no PR, has no labels") {
      assertTrue(
        GitHubPullRequests.parse(response(), merge) == Right(Set.empty),
        GitHubPullRequests.parse("""{"data":{"repository":{"object":null}}}""", merge) == Right(Set.empty),
      )
    },
    test("a GraphQL error or an unexpected body is a failure, never an empty label set") {
      assertTrue(
        GitHubPullRequests.parse("""{"errors":[{"message":"Resource not accessible"}]}""", merge).isLeft,
        GitHubPullRequests.parse("<html>", merge).isLeft,
      )
    },
    test("a non-200 response fails the lookup") {
      val lookup = GitHubPullRequests.mergedLabels(
        "o/r",
        "t",
        merge,
        post = (_, _, _) => Right(HttpLookupResult(403, "forbidden", Map.empty)),
      )
      assertTrue(lookup.left.exists(_.contains("HTTP 403")))
    },
  )
end GitHubPullRequestsSpec
