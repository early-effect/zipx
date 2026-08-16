package zipx.core

/** Parse GitHub releases/tags/git-ref JSON for [[Action]] bumps. HTTP lives in the plugin. */
object GitHubActionMeta:

  final case class Release(tag: String, sha: Option[String])

  def pickLatestRelease(json: String): Either[String, Option[Release]] =
    val items = MiniJson.objects(if json.trim.startsWith("[") then json else s"[$json]")
    val ready = items.filter { obj =>
      !MiniJson.boolField(obj, "draft").contains(true) &&
      !MiniJson.boolField(obj, "prerelease").contains(true)
    }
    Right(
      ready.view
        .flatMap(obj => MiniJson.stringField(obj, "tag_name").map(tag => Release(tag, None)))
        .headOption
    )
  end pickLatestRelease

  def pickLatestTag(json: String): Either[String, Option[Release]] =
    val items = MiniJson.objects(if json.trim.startsWith("[") then json else s"[$json]")
    Right(
      items.view.flatMap { obj =>
        MiniJson.stringField(obj, "name").filterNot(isPrereleaseTag).map { tag =>
          val sha = MiniJson.objectField(obj, "commit").flatMap(MiniJson.stringField(_, "sha"))
          Release(tag, sha)
        }
      }.headOption
    )
  end pickLatestTag

  def peelSha(refJson: String, tagObjectJson: Option[String] = None): Either[String, String] =
    val obj = MiniJson.objectField(refJson, "object").getOrElse(refJson)
    val sha = MiniJson.stringField(obj, "sha")
    val tpe = MiniJson.stringField(obj, "type")
    (sha, tpe) match
      case (Some(s), Some("commit")) if GitSha.make(s).isRight => Right(s)
      case (Some(_), Some("tag"))                              =>
        tagObjectJson match
          case None       => Left("annotated tag needs a git/tags object to peel to a commit SHA")
          case Some(body) =>
            MiniJson
              .objectField(body, "object")
              .flatMap(MiniJson.stringField(_, "sha"))
              .orElse(
                MiniJson.stringField(body, "sha")
              ) match
              case Some(s) if GitSha.make(s).isRight => Right(s)
              case Some(s)                           => Left(s"peeled tag object is not a 40-hex SHA: $s")
              case None                              => Left("git/tags object has no sha")
      case (Some(s), _) if GitSha.make(s).isRight => Right(s)
      case (Some(s), _)                           => Left(s"git ref is not a 40-hex SHA: $s")
      case _                                      => Left("git ref JSON has no object.sha")
    end match
  end peelSha

  def isPrereleaseTag(tag: String): Boolean =
    val t = tag.stripPrefix("v").toLowerCase
    t.contains("rc") || t.contains("alpha") || t.contains("beta") || t.contains("milestone") || t.contains("-m")

  def peelFromRef(refJson: String, loadTagObject: String => Either[String, String]): Either[String, String] =
    val obj = MiniJson.objectField(refJson, "object").getOrElse(refJson)
    MiniJson.stringField(obj, "type") match
      case Some("tag") =>
        MiniJson.stringField(obj, "sha") match
          case None         => Left("annotated tag git ref has no sha")
          case Some(tagSha) =>
            loadTagObject(tagSha).flatMap(body => peelSha(refJson, Some(body)))
      case _ => peelSha(refJson, None)

  def classify(from: String, to: String): BumpKind =
    VersionStrategy.npm.classify(from.stripPrefix("v"), to.stripPrefix("v"))
end GitHubActionMeta
