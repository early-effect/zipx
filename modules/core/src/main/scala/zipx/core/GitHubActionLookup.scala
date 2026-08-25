package zipx.core

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

  private def httpGet(url: String): Either[String, String] =
    val tokenHeader =
      sys.env.get("GITHUB_TOKEN").orElse(sys.env.get("GH_TOKEN")).map(tok => "Authorization" -> s"Bearer $tok")
    val headers = Map(
      "Accept"     -> "application/vnd.github+json",
      "User-Agent" -> "zipx-action-update",
    ) ++ tokenHeader.toMap
    HttpLookup.get(url, headers = headers, timeout = Duration.ofSeconds(20)) match
      case Left(err)                       => Left(s"GitHub $url: $err")
      case Right(res) if res.status == 200 => Right(Option(res.body).getOrElse(""))
      case Right(res) if res.status == 404 => Right("[]")
      case Right(res)                      => Left(s"GitHub $url: HTTP ${res.status}")
  end httpGet
end GitHubActionLookup
