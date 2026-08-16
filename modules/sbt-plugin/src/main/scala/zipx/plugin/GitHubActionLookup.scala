package zipx.plugin

import zipx.core.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Latest GitHub Action release tag plus a peeled commit SHA. Tests inject [[fetch]]. */
final class GitHubActionLookup(
    fetch: String => Either[String, String] = GitHubActionLookup.httpGet
):

  def latest(name: String): Either[String, Option[GitHubActionMeta.Release]] =
    val repo = name.split('/').take(2).mkString("/")
    fetch(s"${GitHubActionLookup.Api}/repos/$repo/releases").flatMap { body =>
      GitHubActionMeta.pickLatestRelease(body).flatMap {
        case Some(rel) => peel(repo, rel)
        case None      =>
          fetch(s"${GitHubActionLookup.Api}/repos/$repo/tags").flatMap { tags =>
            GitHubActionMeta.pickLatestTag(tags).flatMap {
              case Some(rel) => peel(repo, rel)
              case None      => Right(None)
            }
          }
      }
    }
  end latest

  private def peel(repo: String, rel: GitHubActionMeta.Release): Either[String, Option[GitHubActionMeta.Release]] =
    rel.sha.filter(s => GitSha.make(s).isRight) match
      case Some(sha) => Right(Some(rel.copy(sha = Some(sha))))
      case None      =>
        val tag = rel.tag
        fetch(s"${GitHubActionLookup.Api}/repos/$repo/git/ref/tags/$tag").flatMap { refJson =>
          GitHubActionMeta
            .peelFromRef(
              refJson,
              tagSha => fetch(s"${GitHubActionLookup.Api}/repos/$repo/git/tags/$tagSha"),
            )
            .map(sha => Some(rel.copy(sha = Some(sha))))
        }
end GitHubActionLookup

object GitHubActionLookup:
  val Api: String = "https://api.github.com"

  private val Client: HttpClient =
    HttpClient.newBuilder.nn.connectTimeout(Duration.ofSeconds(10)).nn.build.nn

  private def httpGet(url: String): Either[String, String] =
    try
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .nn
        .timeout(Duration.ofSeconds(20))
        .nn
        .header("Accept", "application/vnd.github+json")
        .nn
        .header("User-Agent", "zipx-action-update")
        .nn
      val authed = sys.env.get("GITHUB_TOKEN").orElse(sys.env.get("GH_TOKEN")) match
        case Some(tok) => req.header("Authorization", s"Bearer $tok").nn
        case None      => req
      val res = Client.send(authed.GET.nn.build.nn, HttpResponse.BodyHandlers.ofString).nn
      if res.statusCode == 200 then Right(Option(res.body).getOrElse(""))
      else if res.statusCode == 404 then Right("[]")
      else Left(s"GitHub $url: HTTP ${res.statusCode}")
    catch case ex: Exception => Left(s"GitHub $url: ${ex.getMessage}")
end GitHubActionLookup
