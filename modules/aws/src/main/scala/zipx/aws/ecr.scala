package zipx.aws

import neotype.Subtype

// AWS naming rules as types, in the same shape as `zipx.workflow.names`: an `inline apply` validates a literal while
// the consumer's build compiles, and `make` takes a value computed at runtime and returns an `Either` the caller
// carries. Validators use only what neotype can fold at compile time, so every character class is a `matches` against
// an `inline val` pattern rather than a lambda or a compiled `Regex`.
//
// Every type here is a `Subtype`, so it is a `String` and interpolates into a registry host or an image URI without
// unwrapping. These values are read far more often than they are built, which is the same argument `zipx.core.ModuleId`
// and `zipx.workflow.JobId` make.

/** A 12-digit AWS account id, the first label of an ECR registry host.
  *
  * Length is the whole rule, and it is worth having: an account id pasted one digit short still produces a
  * syntactically fine host, and the push fails on the runner with a DNS error rather than here.
  */
type AwsAccountId = AwsAccountId.Type
object AwsAccountId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.matches(AwsNames.AccountId) then true
    else s"invalid AWS account id '$input': expected exactly 12 digits"

/** An AWS region, as in `us-east-1` or `us-gov-west-1`.
  *
  * Shape only, deliberately: AWS adds regions, and a fixed list would refuse a valid build the week a new one opens.
  * The shape check catches the mistake that actually happens, which is a region left empty or spelled `us-east1`.
  */
type AwsRegion = AwsRegion.Type
object AwsRegion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "an AWS region must be non-empty"
    else if input.matches(AwsNames.Region) then true
    else s"invalid AWS region '$input': expected a shape like us-east-1, eu-west-2 or us-gov-west-1"

/** An ECR repository name: lowercase, starting with a letter or digit, with `/` allowed as a namespace separator.
  *
  * ECR's own rule, which is stricter than a Docker image name's: an uppercase letter is refused by the registry at push
  * time, not at build time, so it is worth refusing here.
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

/** A Docker image tag: the part after the `:`.
  *
  * This is the type that earns its keep. A tag assembled from a branch name is the one place in a deploy pipeline where
  * a wrong value is *silent*: `refs/heads/feat/x` contains a `/`, so `example:main-feat/x-abc123` is not a tag but a
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

  /** `<prefix>-<sha>`: the immutable tag every image gets, and the only one a deploy should ever resolve. */
  def commit(prefix: String, sha: String): Either[String, ImageTag] = make(s"$prefix-$sha")

  /** `<prefix>-<branch>-<sha>`: the same image, findable by the branch it came from. */
  def branchCommit(prefix: String, branch: String, sha: String): Either[String, ImageTag] =
    make(s"$prefix-$branch-$sha")

  /** `<prefix>-<branch>-latest`: a moving tag. Useful for a dev environment, never for a deploy that must be
    * reproducible.
    */
  def branchLatest(prefix: String, branch: String): Either[String, ImageTag] = make(s"$prefix-$branch-latest")

  /** The tag set for one commit: [[commit]] always, plus [[branchCommit]] and [[branchLatest]] only when `branch` is
    * `defaultBranch`.
    *
    * This is the shape a consumer's `dockerAliases` has to enumerate, and enumerating it by hand is how an image ends
    * up pushed under a tag nothing deploys. The moving tags are conditional rather than always emitted because a moving
    * tag on a feature branch is a race between two PRs.
    */
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

  /** Replaces every character a tag may not contain with `-`, for turning a ref name into a tag.
    *
    * Separate from [[make]] on purpose: mangling text into validity is exactly the silent behaviour the type exists to
    * prevent, so it happens only when a caller asks for it by name. `feat/x` becomes `feat-x`; a leading `.` or `-`
    * becomes `_`, since a tag may not start with one.
    */
  def slug(text: String): Either[String, ImageTag] =
    val replaced = text.map(c => if c.isLetterOrDigit || c == '.' || c == '_' || c == '-' then c else '-')
    val headed   = if replaced.headOption.exists(c => c == '.' || c == '-') then s"_${replaced.tail}" else replaced
    make(headed.take(AwsNames.MaxTagLength))
end ImageTag

/** An ECR registry, which is one AWS account in one region.
  *
  * **There is no constructor that omits the region**, and that is the entire point of the type. The `Registry` case
  * class this replaces (in `examples/monorepo`) held a hand-written `host` and no region, so the generated
  * `configure-aws-credentials` step had no region to pass and the action failed on the runner with a message about
  * credentials rather than about the missing field (#65). Here the region is a constructor parameter and [[host]] is
  * derived from it, so the bug is unrepresentable rather than fixed once.
  */
final case class EcrRegistry(accountId: AwsAccountId, region: AwsRegion):

  /** `<account>.dkr.ecr.<region>.amazonaws.com`. Derived, never passed in. */
  def host: String = s"$accountId.dkr.ecr.$region.amazonaws.com"

  def image(repository: EcrRepository): EcrImage = EcrImage(this, repository)

end EcrRegistry

/** One repository in one registry: everything but the tag. */
final case class EcrImage(registry: EcrRegistry, repository: EcrRepository):

  /** `<host>/<repository>`, the value sbt-native-packager's `dockerRepository` wants. */
  def uri: String = s"${registry.host}/$repository"

  def tagged(tag: ImageTag): String = s"$uri:$tag"

  /** Every tag this image is pushed under, in the order given: the list `dockerAliases` enumerates. */
  def taggedAll(tags: List[ImageTag]): List[String] = tags.map(tagged)

end EcrImage

/** Patterns as `inline val` Strings so `validate` can evaluate them during compilation. */
object AwsNames:

  inline val AccountId = "[0-9]{12}"

  /** Two letters, one or more `-`-separated words, then a digit: `us-east-1`, `ap-southeast-2`, `us-gov-west-1`. */
  inline val Region = "[a-z]{2}(-[a-z]+)+-[0-9]"

  /** ECR's rule: lowercase, starting alphanumeric, with `/` as a namespace separator. */
  inline val Repository = "[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)*"

  /** Docker's rule: a tag may not start with `.` or `-`. */
  inline val Tag = "[A-Za-z0-9_][A-Za-z0-9._-]*"

  inline val MaxRepositoryLength = 256
  inline val MaxTagLength        = 128

end AwsNames
