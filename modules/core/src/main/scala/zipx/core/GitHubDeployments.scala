package zipx.core

import zio.json.*

/** Each module's last successful deploy per Environment, read back from GitHub's deployment records.
  *
  * GitHub records a deployment for every job that binds an Environment, but against the run's own commit, which is the
  * dispatched branch head rather than a rolled-back `sha`, and without saying which module the job shipped.
  * `zipx-deploy.yml` therefore sets each job's environment url to `…/commit/<sha>#<module>`, and this reads that url. A
  * record whose url does not have that shape is ignored, so a deploy zipx did not make never narrows `changed`.
  */
object GitHubDeployments:

  /** How many recent deployments one lookup reads. A module whose last deploy is older is treated as never deployed,
    * which over-deploys it once.
    */
  val Window: Int = 100

  private val UrlShape = """.*/commit/([0-9a-fA-F]{40})#([A-Za-z_][A-Za-z0-9_-]*)""".r

  /** The GraphQL request body for the deployments of `environments`, newest first. */
  def query(repository: String, environments: List[String]): Either[String, String] =
    repository.split('/') match
      case Array(owner, name) if owner.nonEmpty && name.nonEmpty =>
        val q =
          "query($owner:String!,$name:String!,$envs:[String!]){repository(owner:$owner,name:$name){" +
            s"deployments(environments:$$envs,first:$Window,orderBy:{field:CREATED_AT,direction:DESC})" +
            "{nodes{environment latestStatus{state environmentUrl}}}}}"
        Right(Request(q, Variables(owner, name, environments.distinct.sorted)).toJson)
      case _ => Left(s"zipx: GITHUB_REPOSITORY '$repository' is not owner/name")

  type Post = (String, String, Map[String, String]) => Either[String, HttpLookupResult]

  /** [[query]], sent to GitHub's GraphQL endpoint, then [[parse]]. */
  def lookup(
      repository: String,
      token: String,
      environments: List[String],
      endpoint: String = "https://api.github.com/graphql",
      post: Post = (url, body, headers) => HttpLookup.post(url, body, headers),
  ): Either[String, List[LastDeploy]] =
    for
      body     <- query(repository, environments)
      response <- post(endpoint, body, Map("Authorization" -> s"Bearer $token", "Content-Type" -> "application/json"))
      _        <- Either.cond(
        response.status == 200,
        (),
        s"zipx: deployments query returned HTTP ${response.status}: ${response.body.take(300)}",
      )
      deploys <- parse(response.body)
    yield deploys

  /** The newest successful deploy of each module to each environment. */
  def parse(body: String): Either[String, List[LastDeploy]] =
    body.fromJson[Response].left.map(e => s"zipx: unexpected deployments response: $e").flatMap { response =>
      response.errors match
        case Some(errors) if errors.nonEmpty =>
          Left(s"zipx: deployments query failed: ${errors.map(_.message).mkString("; ")}")
        case _ =>
          val nodes   = response.data.flatMap(_.repository).map(_.deployments.nodes).getOrElse(Nil)
          val deploys = nodes.flatMap { node =>
            node.latestStatus.filter(_.state == "SUCCESS").flatMap(_.environmentUrl).collect {
              case UrlShape(sha, module) =>
                LastDeploy(node.environment, ModuleId.unsafeMake(module), GitSha.unsafeMake(sha.toLowerCase))
            }
          }
          Right(deploys.distinctBy(d => (d.environment, d.module)))
    }

  private final case class Variables(owner: String, name: String, envs: List[String]) derives JsonEncoder
  private final case class Request(query: String, variables: Variables) derives JsonEncoder

  private final case class Status(state: String, environmentUrl: Option[String]) derives JsonDecoder
  private final case class Node(environment: String, latestStatus: Option[Status]) derives JsonDecoder
  private final case class Nodes(nodes: List[Node]) derives JsonDecoder
  private final case class Repository(deployments: Nodes) derives JsonDecoder
  private final case class Data(repository: Option[Repository]) derives JsonDecoder
  private final case class Error(message: String) derives JsonDecoder
  private final case class Response(data: Option[Data], errors: Option[List[Error]]) derives JsonDecoder

end GitHubDeployments
