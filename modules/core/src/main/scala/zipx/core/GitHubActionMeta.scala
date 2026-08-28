package zipx.core

import zio.json.*

/** Parse GitHub releases/tags/git-ref JSON for [[Action]] bumps. HTTP lives in [[GitHubActionLookup]]. */
object GitHubActionMeta:

  final case class Release(tag: String, sha: Option[String])

  private final case class GhRelease(tag_name: String, draft: Option[Boolean], prerelease: Option[Boolean])
      derives JsonDecoder
  private final case class GhCommit(sha: Option[String]) derives JsonDecoder
  private final case class GhTag(name: String, commit: Option[GhCommit]) derives JsonDecoder
  private final case class GhGitObject(`type`: Option[String], sha: Option[String]) derives JsonDecoder
  private final case class GhGitRef(`object`: Option[GhGitObject], sha: Option[String], `type`: Option[String])
      derives JsonDecoder

  def pickLatestRelease(json: String): Either[String, Option[Release]] =
    val wrapped = if json.trim.startsWith("[") then json else s"[$json]"
    wrapped.fromJson[List[GhRelease]] match
      case Left(err)    => Left(s"github releases: $err")
      case Right(items) =>
        val tags = items
          .filter(r => !r.draft.contains(true) && !r.prerelease.contains(true))
          .map(_.tag_name)
          .filterNot(isPrereleaseTag)
        Right(VersionStrategy.npm.latestStable(tags).orElse(tags.headOption).map(tag => Release(tag, None)))
  end pickLatestRelease

  def pickLatestTag(json: String): Either[String, Option[Release]] =
    val wrapped = if json.trim.startsWith("[") then json else s"[$json]"
    wrapped.fromJson[List[GhTag]] match
      case Left(err)    => Left(s"github tags: $err")
      case Right(items) =>
        val tags = items.flatMap { t =>
          Option.when(!isPrereleaseTag(t.name))(t.name -> t.commit.flatMap(_.sha))
        }
        Right(
          VersionStrategy.npm
            .latestStable(tags.map(_._1))
            .orElse(tags.headOption.map(_._1))
            .flatMap(latest => tags.find(_._1 == latest).map { case (tag, sha) => Release(tag, sha) })
        )
    end match
  end pickLatestTag

  def peelSha(refJson: String, tagObjectJson: Option[String] = None): Either[String, String] =
    gitObject(refJson).flatMap { obj =>
      (obj.sha, obj.`type`) match
        case (Some(s), Some("commit")) if GitSha.make(s).isRight => Right(s)
        case (Some(_), Some("tag"))                              =>
          tagObjectJson match
            case None       => Left("annotated tag needs a git/tags object to peel to a commit SHA")
            case Some(body) =>
              gitObject(body).flatMap { peeled =>
                peeled.sha match
                  case Some(s) if GitSha.make(s).isRight => Right(s)
                  case Some(s)                           => Left(s"peeled tag object is not a 40-hex SHA: $s")
                  case None                              => Left("git/tags object has no sha")
              }
        case (Some(s), _) if GitSha.make(s).isRight => Right(s)
        case (Some(s), _)                           => Left(s"git ref is not a 40-hex SHA: $s")
        case _                                      => Left("git ref JSON has no object.sha")
    }
  end peelSha

  private def gitObject(json: String): Either[String, GhGitObject] =
    json.fromJson[GhGitRef] match
      case Left(err) => Left(s"git ref JSON: $err")
      case Right(r)  => Right(r.`object`.getOrElse(GhGitObject(r.`type`, r.sha)))

  def isPrereleaseTag(tag: String): Boolean =
    val t = tag.stripPrefix("v").toLowerCase
    t.contains("rc") || t.contains("alpha") || t.contains("beta") || t.contains("milestone") || t.contains("-m")

  def peelFromRef(refJson: String, loadTagObject: String => Either[String, String]): Either[String, String] =
    gitObject(refJson).flatMap { obj =>
      obj.`type` match
        case Some("tag") =>
          obj.sha match
            case None         => Left("annotated tag git ref has no sha")
            case Some(tagSha) => loadTagObject(tagSha).flatMap(body => peelSha(refJson, Some(body)))
        case _ => peelSha(refJson, None)
    }

  def classify(from: String, to: String): BumpKind =
    VersionStrategy.npm.classify(from.stripPrefix("v"), to.stripPrefix("v"))
end GitHubActionMeta
