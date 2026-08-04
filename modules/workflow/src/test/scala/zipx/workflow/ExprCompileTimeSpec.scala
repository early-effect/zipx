package zipx.workflow

import zio.test.*

/** The compile-time half of the Actions-syntax rules: a bad *literal* must fail the build, not just `make`.
  *
  * Same contract and same technique as `zipx.shell.CompileTimeSpec`. [[NamesSpec]] proves `validate` rejects bad values
  * at runtime; this proves a name written into a build file is rejected before the build finishes, which is what makes
  * a mistyped secret name a compile error rather than a workflow that silently injects an empty string.
  */
object ExprCompileTimeSpec extends ZIOSpecDefault:

  def spec = suite("compile-time validation")(
    test("a valid literal compiles") {
      for
        jobId   <- typeCheck("""JobId("build-and-test")""")
        stepId  <- typeCheck("""StepId("check")""")
        secret  <- typeCheck("""SecretName("PGP_PASSPHRASE")""")
        env     <- typeCheck("""EnvName("DEPLOY_ROLE")""")
        output  <- typeCheck("""OutputName("epoch")""")
        axis    <- typeCheck("""MatrixAxis("scala")""")
        path    <- typeCheck("""ContextPath("event.pull_request.base.sha")""")
        action  <- typeCheck("""ActionRef("actions/checkout@v4")""")
        event   <- typeCheck("""EventName("pull_request")""")
        literal <- typeCheck("""ExprLiteral("refs/tags/v")""")
        raw     <- typeCheck("""RawExpr("always()")""")
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
        raw.isRight,
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
      // The same laundering hazard as `Word.lit`: an inline constructor must pass the literal through to `validate`
      // rather than accept a String and validate at runtime, or the compile-time guarantee is only on the newtypes.
      for
        badSecret <- typeCheck("""Expr.secret("GITHUB_PAT")""")
        badEnv    <- typeCheck("""Expr.env("bad-name")""")
        badPath   <- typeCheck("""Expr.github("event..sha")""")
        badStep   <- typeCheck("""Expr.stepOutput("1check", "run")""")
        badOutput <- typeCheck("""Expr.stepOutput("check", "set-output")""")
        badMatrix <- typeCheck("""Expr.matrix("include")""")
        badRaw    <- typeCheck("""Expr.raw("${{ unbalanced")""")
        goodStep  <- typeCheck("""Expr.stepOutput("check", "run")""")
      yield assertTrue(
        badSecret.isLeft,
        badEnv.isLeft,
        badPath.isLeft,
        badStep.isLeft,
        badOutput.isLeft,
        badMatrix.isLeft,
        badRaw.isLeft,
        goodStep.isRight,
      )
    },
    test("a runtime String does not reach an inline constructor") {
      // `inline def secret(inline name: String)` accepts only a literal, so a variable is a compile error and the
      // caller is pushed to `secretMake`, which returns an Either. That is the fork the design depends on.
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
