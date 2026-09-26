package zipx.core

import zio.json.*

/** The labels of the PR a push merged, which decide whether a [[DeployTrigger.Staged]] merge deploys.
  *
  * GitHub's push event does not say which PR it merged, so this asks for the PRs associated with the pushed commit and
  * keeps the one whose merge commit it is. A direct push matches none and returns no labels.
  */
object GitHubPullRequests:

  /** The GraphQL request body for the PRs associated with `sha`. */
  def query(repository: String, sha: GitSha): Either[String, String] =
    repository.split('/') match
      case Array(owner, name) if owner.nonEmpty && name.nonEmpty =>
        val q =
          "query($owner:String!,$name:String!,$oid:GitObjectID!){repository(owner:$owner,name:$name){" +
            "object(oid:$oid){... on Commit{associatedPullRequests(first:10)" +
            "{nodes{mergeCommit{oid} labels(first:100){nodes{name}}}}}}}}"
        Right(Request(q, Variables(owner, name, sha)).toJson)
      case _ => Left(s"zipx: GITHUB_REPOSITORY '$repository' is not owner/name")

  /** [[query]], sent to GitHub's GraphQL endpoint, then [[parse]]. */
  def mergedLabels(
      repository: String,
      token: String,
      sha: GitSha,
      endpoint: String = "https://api.github.com/graphql",
      post: GitHubDeployments.Post = (url, body, headers) => HttpLookup.post(url, body, headers),
  ): Either[String, Set[String]] =
    for
      body     <- query(repository, sha)
      response <- post(endpoint, body, Map("Authorization" -> s"Bearer $token", "Content-Type" -> "application/json"))
      _        <- Either.cond(
        response.status == 200,
        (),
        s"zipx: pull request query returned HTTP ${response.status}: ${response.body.take(300)}",
      )
      labels <- parse(response.body, sha)
    yield labels

  /** The labels of the associated PR whose merge commit is `sha`, or none when no PR merged it. */
  def parse(body: String, sha: GitSha): Either[String, Set[String]] =
    body.fromJson[Response].left.map(e => s"zipx: unexpected pull request response: $e").flatMap { response =>
      response.errors match
        case Some(errors) if errors.nonEmpty =>
          Left(s"zipx: pull request query failed: ${errors.map(_.message).mkString("; ")}")
        case _ =>
          val prs = response.data
            .flatMap(_.repository)
            .flatMap(_.`object`)
            .flatMap(_.associatedPullRequests)
            .map(_.nodes)
            .getOrElse(Nil)
          val merged = prs.filter(_.mergeCommit.exists(_.oid.equalsIgnoreCase(sha)))
          Right(merged.flatMap(_.labels.nodes.map(_.name)).toSet)
    }

  private final case class Variables(owner: String, name: String, oid: String) derives JsonEncoder
  private final case class Request(query: String, variables: Variables) derives JsonEncoder

  private final case class Label(name: String) derives JsonDecoder
  private final case class Labels(nodes: List[Label]) derives JsonDecoder
  private final case class MergeCommit(oid: String) derives JsonDecoder
  private final case class Pr(mergeCommit: Option[MergeCommit], labels: Labels) derives JsonDecoder
  private final case class Prs(nodes: List[Pr]) derives JsonDecoder
  private final case class Commit(associatedPullRequests: Option[Prs]) derives JsonDecoder
  private final case class Repository(`object`: Option[Commit]) derives JsonDecoder
  private final case class Data(repository: Option[Repository]) derives JsonDecoder
  private final case class Error(message: String) derives JsonDecoder
  private final case class Response(data: Option[Data], errors: Option[List[Error]]) derives JsonDecoder

end GitHubPullRequests
