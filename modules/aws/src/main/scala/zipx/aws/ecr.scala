package zipx.aws

import neotype.Subtype

// Every type here is a `Subtype`, so it interpolates into a registry host or an image URI without unwrapping: these
// values are read far more often than they are built.

/** An id pasted one digit short still makes a valid-looking host, and the push fails on the runner with a DNS error
  * rather than here.
  */
type AwsAccountId = AwsAccountId.Type
object AwsAccountId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.matches(AwsNames.AccountId) then true
    else s"invalid AWS account id '$input': expected exactly 12 digits"

/** Shape only: AWS adds regions, and a fixed list would refuse a valid build the week a new one opens. */
type AwsRegion = AwsRegion.Type
object AwsRegion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an AWS region must be non-empty"
    else if input.matches(AwsNames.Region) then true
    else s"invalid AWS region '$input': expected a shape like us-east-1, eu-west-2 or us-gov-west-1"

/** ECR's rule, stricter than a Docker image name's: the registry refuses an uppercase letter at push time, not build
  * time.
  */
type EcrRepository = EcrRepository.Type
object EcrRepository extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an ECR repository name must be non-empty"
    else if input.length > AwsNames.MaxRepositoryLength then
      s"an ECR repository name must be at most ${AwsNames.MaxRepositoryLength} characters"
    else if input.matches(AwsNames.Repository) then true
    else
      s"invalid ECR repository name '$input': lowercase letters, digits, and . _ - /, starting with a letter or digit"

/** A tag built from a branch name is where a wrong value is *silent*: `example:main-feat/x-abc123` is not a tag but a
  * different repository, and the image publishes somewhere nothing deploys from. See [[ImageTag.slug]].
  */
type ImageTag = ImageTag.Type
object ImageTag extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an image tag must be non-empty"
    else if input.length > AwsNames.MaxTagLength then
      s"an image tag must be at most ${AwsNames.MaxTagLength} characters"
    else if input.matches(AwsNames.Tag) then true
    else
      s"invalid image tag '$input': must start with a letter, digit or _ and contain only letters, digits, . _ -" +
        " (a branch name with a / in it needs ImageTag.slug)"

  /** The immutable tag every image gets, and the only one a deploy should ever resolve. */
  def commit(prefix: String, sha: String): Either[String, ImageTag] = make(s"$prefix-$sha")

  def branchCommit(prefix: String, branch: String, sha: String): Either[String, ImageTag] =
    make(s"$prefix-$branch-$sha")

  /** A moving tag: fine for a dev environment, never for a deploy that must be reproducible. */
  def branchLatest(prefix: String, branch: String): Either[String, ImageTag] = make(s"$prefix-$branch-latest")

  /** The moving tags only on `defaultBranch`, because a moving tag on a feature branch is a race between two PRs. */
  def forCommit(
      prefix: String,
      sha: String,
      branch: String,
      defaultBranch: String = "main",
  ): Either[String, List[ImageTag]] =
    for
      immutable <- commit(prefix, sha)
      moving    <-
        if branch != defaultBranch then Right(Nil)
        else
          for
            byBranch <- branchCommit(prefix, branch, sha)
            latest   <- branchLatest(prefix, branch)
          yield List(byBranch, latest)
    yield immutable :: moving

  /** Separate from [[make]] on purpose: mangling text into validity is the silent behaviour the type exists to prevent,
    * so it happens only when a caller asks for it by name.
    */
  def slug(text: String): Either[String, ImageTag] =
    val replaced = text.map(c => if c.isLetterOrDigit || c == '.' || c == '_' || c == '-' then c else '-')
    val headed   = if replaced.headOption.exists(c => c == '.' || c == '-') then s"_${replaced.tail}" else replaced
    make(headed.take(AwsNames.MaxTagLength))
end ImageTag

/** No constructor omits the region: without one, `configure-aws-credentials` fails on the runner with an error about
  * credentials rather than the missing field. [[host]] is derived from it, never passed in.
  */
final case class EcrRegistry(accountId: AwsAccountId, region: AwsRegion):

  def host: String = s"$accountId.dkr.ecr.$region.amazonaws.com"

  def image(repository: EcrRepository): EcrImage = EcrImage(this, repository)

final case class EcrImage(registry: EcrRegistry, repository: EcrRepository):

  /** The value sbt-native-packager's `dockerRepository` wants. */
  def uri: String = s"${registry.host}/$repository"

  def tagged(tag: ImageTag): String = s"$uri:$tag"

  /** The list `dockerAliases` enumerates. */
  def taggedAll(tags: List[ImageTag]): List[String] = tags.map(tagged)

end EcrImage

/** Patterns as `inline val` Strings so `validate` can evaluate them during compilation. */
object AwsNames:

  inline val AccountId = "[0-9]{12}"

  inline val Region = "[a-z]{2}(-[a-z]+)+-[0-9]"

  inline val Repository = "[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)*"

  inline val Tag = "[A-Za-z0-9_][A-Za-z0-9._-]*"

  inline val MaxRepositoryLength = 256
  inline val MaxTagLength        = 128

end AwsNames
