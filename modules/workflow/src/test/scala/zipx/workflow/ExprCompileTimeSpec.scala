package zipx.workflow

import zio.test.*

object ExprCompileTimeSpec extends ZIOSpecDefault:

  def spec = suite("compile-time validation")(
    test("a valid literal compiles") {
      for
        jobId    <- typeCheck("""JobId("build-and-test")""")
        stepId   <- typeCheck("""StepId("check")""")
        secret   <- typeCheck("""SecretName("PGP_PASSPHRASE")""")
        env      <- typeCheck("""EnvName("DEPLOY_ROLE")""")
        output   <- typeCheck("""OutputName("epoch")""")
        axis     <- typeCheck("""MatrixAxis("scala")""")
        path     <- typeCheck("""ContextPath("event.pull_request.base.sha")""")
        action   <- typeCheck("""ActionRef("actions/checkout@v4")""")
        event    <- typeCheck("""EventName("pull_request")""")
        literal  <- typeCheck("""ExprLiteral("refs/tags/v")""")
        function <- typeCheck("""FunctionName("startsWith")""")
        raw      <- typeCheck("""RawExpr("always()")""")
      yield assertTrue(
        jobId.isRight,
        stepId.isRight,
        secret.isRight,
        env.isRight,
        output.isRight,
        axis.isRight,
        path.isRight,
        action.isRight,
        event.isRight,
        literal.isRight,
        function.isRight,
        raw.isRight,
      )
    },
    test("an unknown expression function does not compile") {
      for
        unknown  <- typeCheck("""FunctionName("myHelper")""")
        typo     <- typeCheck("""FunctionName("startWith")""")
        empty    <- typeCheck("""FunctionName("")""")
        upper    <- typeCheck("""FunctionName("FROMJSON")""")
        mixed    <- typeCheck("""FunctionName("fromJson")""")
        viaCall  <- typeCheck("""Expr.call("nosuchFn")""")
        goodCall <- typeCheck("""Expr.call("contains", Expr.lit("a"), Expr.quoted("b"))""")
      yield assertTrue(
        unknown.isLeft,
        typo.isLeft,
        empty.isLeft,
        upper.isRight,
        mixed.isRight,
        viaCall.isLeft,
        goodCall.isRight,
      )
    },
    test("a digit-leading or hyphenated id does not compile") {
      for
        digitJob    <- typeCheck("""JobId("1build")""")
        digitStep   <- typeCheck("""StepId("0check")""")
        leadingDash <- typeCheck("""JobId("-build")""")
        empty       <- typeCheck("""StepId("")""")
      yield assertTrue(digitJob.isLeft, digitStep.isLeft, leadingDash.isLeft, empty.isLeft)
    },
    test("a reserved secret name does not compile") {
      for
        prefixed  <- typeCheck("""SecretName("GITHUB_PAT")""")
        lowercase <- typeCheck("""SecretName("github_pat")""")
        hyphen    <- typeCheck("""SecretName("PGP-PASSPHRASE")""")
        token     <- typeCheck("""SecretName("GITHUB_TOKEN")""")
      yield assertTrue(prefixed.isLeft, lowercase.isLeft, hyphen.isLeft, token.isRight)
    },
    test("a GITHUB_-prefixed env name does not compile") {
      for
        output    <- typeCheck("""EnvName("GITHUB_OUTPUT")""")
        dashed    <- typeCheck("""EnvName("bad-name")""")
        substring <- typeCheck("""EnvName("PUBLISH_GITHUB_PACKAGES")""")
      yield assertTrue(output.isLeft, dashed.isLeft, substring.isRight)
    },
    test("a deprecated workflow command as an output name does not compile") {
      for
        setOutput <- typeCheck("""OutputName("set-output")""")
        saveState <- typeCheck("""OutputName("save-state")""")
      yield assertTrue(setOutput.isLeft, saveState.isLeft)
    },
    test("a matrix directive as an axis does not compile") {
      for
        include <- typeCheck("""MatrixAxis("include")""")
        exclude <- typeCheck("""MatrixAxis("exclude")""")
      yield assertTrue(include.isLeft, exclude.isLeft)
    },
    test("a malformed context path does not compile") {
      for
        emptySegment <- typeCheck("""ContextPath("event..sha")""")
        trailingDot  <- typeCheck("""ContextPath("event.")""")
        openBracket  <- typeCheck("""ContextPath("event.commits[0.id")""")
        wildcard     <- typeCheck("""ContextPath("event.pull_request.labels.*.name")""")
      yield assertTrue(emptySegment.isLeft, trailingDot.isLeft, openBracket.isLeft, wildcard.isRight)
    },
    test("an unpinned uses: ref does not compile") {
      for
        unpinned <- typeCheck("""ActionRef("actions/checkout")""")
        bare     <- typeCheck("""ActionRef("checkout")""")
        local    <- typeCheck("""ActionRef("./.github/actions/setup")""")
        docker   <- typeCheck("""ActionRef("docker://alpine:3.19")""")
      yield assertTrue(unpinned.isLeft, bare.isLeft, local.isRight, docker.isRight)
    },
    test("an unbalanced raw expression does not compile") {
      for
        openOnly  <- typeCheck("""RawExpr("${{ github.sha")""")
        closeOnly <- typeCheck("""RawExpr("github.sha }}")""")
        multiline <- typeCheck("""RawExpr("a\nb")""")
        balanced  <- typeCheck("""RawExpr("${{ github.sha }}")""")
      yield assertTrue(openOnly.isLeft, closeOnly.isLeft, multiline.isLeft, balanced.isRight)
    },
    test("Expr's smart constructors forward literals into the compile-time check") {
      for
        badSecret <- typeCheck("""Expr.secret("GITHUB_PAT")""")
        badEnv    <- typeCheck("""Expr.env("bad-name")""")
        badPath   <- typeCheck("""Expr.github("event..sha")""")
        badStep   <- typeCheck("""Expr.stepOutput("1check", "run")""")
        badOutput <- typeCheck("""Expr.stepOutput("check", "set-output")""")
        badMatrix <- typeCheck("""Expr.matrix("include")""")
        badRaw    <- typeCheck("""Expr.raw("${{ unbalanced")""")
        badQuoted <- typeCheck("""Expr.quoted("two words")""")
        goodStep  <- typeCheck("""Expr.stepOutput("check", "run")""")
      yield assertTrue(
        badSecret.isLeft,
        badEnv.isLeft,
        badPath.isLeft,
        badStep.isLeft,
        badOutput.isLeft,
        badMatrix.isLeft,
        badRaw.isLeft,
        badQuoted.isLeft,
        goodStep.isRight,
      )
    },
    test("a runtime String does not reach an inline constructor") {
      for
        variable <- typeCheck("""val n = "PGP_SECRET"; Expr.secret(n)""")
        viaMake  <- typeCheck("""val n = "PGP_SECRET"; Expr.secretMake(n)""")
      yield assertTrue(variable.isLeft, viaMake.isRight)
    },
    test("the compile error carries the validator's message, not a generic type error") {
      for
        secret <- typeCheck("""SecretName("GITHUB_PAT")""")
        action <- typeCheck("""ActionRef("actions/checkout")""")
      yield assertTrue(
        secret.swap.exists(_.contains("GITHUB_ prefix")),
        action.swap.exists(_.contains("@ref")),
      )
    },
  )
end ExprCompileTimeSpec
