package zipx.aws

import zio.test.*

object EcrSpec extends ZIOSpecDefault:

  private val account  = AwsAccountId("111122223333")
  private val region   = AwsRegion("us-east-1")
  private val registry = EcrRegistry(account, region)

  def spec = suite("ECR types")(
    suite("a registry host is derived, never passed in")(
      test("host is <account>.dkr.ecr.<region>.amazonaws.com") {
        assertTrue(registry.host == "111122223333.dkr.ecr.us-east-1.amazonaws.com")
      },
      test("there is no way to construct a registry without a region") {
        for missing <- typeCheck("""EcrRegistry(AwsAccountId("111122223333"))""")
        yield assertTrue(missing.isLeft)
      },
      test("an image uri joins host and repository, and a tag joins onto that") {
        val image = registry.image(EcrRepository("team/example-service"))
        assertTrue(
          image.uri == "111122223333.dkr.ecr.us-east-1.amazonaws.com/team/example-service",
          image.tagged(ImageTag("main-abc1234")) ==
            "111122223333.dkr.ecr.us-east-1.amazonaws.com/team/example-service:main-abc1234",
        )
      },
      test("taggedAll preserves the order dockerAliases will enumerate") {
        val image = registry.image(EcrRepository("example"))
        val tags  = List(ImageTag("v1"), ImageTag("main-abc1234"), ImageTag("main-latest"))
        assertTrue(image.taggedAll(tags).map(_.split(':').last) == List("v1", "main-abc1234", "main-latest"))
      },
    ),
    suite("account id and region")(
      test("a 12-digit account id is accepted") {
        assertTrue(AwsAccountId.make("111122223333").isRight)
      },
      test("an account id of the wrong length is refused") {
        assertTrue(
          AwsAccountId.make("11112222333").isLeft,
          AwsAccountId.make("1111222233334").isLeft,
          AwsAccountId.make("").isLeft,
          AwsAccountId.make("11112222333a").isLeft,
        )
      },
      test("regions from every partition are accepted, since a fixed list would go stale") {
        assertTrue(
          AwsRegion.make("us-east-1").isRight,
          AwsRegion.make("eu-west-2").isRight,
          AwsRegion.make("ap-southeast-4").isRight,
          AwsRegion.make("us-gov-west-1").isRight,
          AwsRegion.make("cn-north-1").isRight,
        )
      },
      test("a misspelled region is refused") {
        assertTrue(
          AwsRegion.make("us-east1").isLeft,
          AwsRegion.make("useast-1").isLeft,
          AwsRegion.make("").isLeft,
          AwsRegion.make("US-EAST-1").isLeft,
        )
      },
      test("an invalid literal does not compile") {
        for
          shortId    <- typeCheck("""AwsAccountId("123")""")
          badRegion  <- typeCheck("""AwsRegion("us-east1")""")
          goodId     <- typeCheck("""AwsAccountId("111122223333")""")
          goodRegion <- typeCheck("""AwsRegion("eu-central-1")""")
        yield assertTrue(shortId.isLeft, badRegion.isLeft, goodId.isRight, goodRegion.isRight)
      },
    ),
    suite("repository names follow ECR's rule, not Docker's")(
      test("lowercase, with / as a namespace separator") {
        assertTrue(
          EcrRepository.make("example").isRight,
          EcrRepository.make("team/example-service").isRight,
          EcrRepository.make("a.b_c-d").isRight,
        )
      },
      test("uppercase, a leading separator, or an empty name is refused") {
        assertTrue(
          EcrRepository.make("Example").isLeft,
          EcrRepository.make("-example").isLeft,
          EcrRepository.make("/example").isLeft,
          EcrRepository.make("example/").isLeft,
          EcrRepository.make("").isLeft,
        )
      },
    ),
    suite("image tags")(
      test("commit and branch tags render as <prefix>-<...>") {
        assertTrue(
          ImageTag.commit("1.4.2", "abc1234") == Right("1.4.2-abc1234"),
          ImageTag.branchCommit("1.4.2", "main", "abc1234") == Right("1.4.2-main-abc1234"),
          ImageTag.branchLatest("1.4.2", "main") == Right("1.4.2-main-latest"),
        )
      },
      test("forCommit adds the moving tags only on the default branch") {
        assertTrue(
          ImageTag.forCommit("1.4.2", "abc1234", "main") ==
            Right(List("1.4.2-abc1234", "1.4.2-main-abc1234", "1.4.2-main-latest")),
          ImageTag.forCommit("1.4.2", "abc1234", "feat-x") == Right(List("1.4.2-abc1234")),
        )
      },
      test("a branch name with a slash in it is refused rather than mangled") {
        assertTrue(
          ImageTag.make("main-feat/x").isLeft,
          ImageTag.branchCommit("1.4.2", "feat/x", "abc1234").isLeft,
        )
      },
      test("slug is the opt-in mangle, so making text valid is never implicit") {
        assertTrue(
          ImageTag.slug("feat/x") == Right("feat-x"),
          ImageTag.slug("release/v1.2") == Right("release-v1.2"),
          ImageTag.slug("-leading") == Right("_leading"),
          ImageTag.slug(".leading") == Right("_leading"),
        )
      },
      test("a tag may not start with . or -, and is length-bounded") {
        assertTrue(
          ImageTag.make(".dotted").isLeft,
          ImageTag.make("-dashed").isLeft,
          ImageTag.make("").isLeft,
          ImageTag.make("a" * 129).isLeft,
          ImageTag.make("a" * 128).isRight,
        )
      },
      test("slug truncates to the tag limit rather than producing something the registry refuses") {
        assertTrue(ImageTag.slug("x" * 200).map(_.length) == Right(128))
      },
    ),
  )
end EcrSpec
