package zipx.core

import zio.test.*
import zipx.workflow.Expr

object ExprBridgeSpec extends ZIOSpecDefault:

  def spec = suite("Expr bridges")(
    suite("EnvValue.asExpr")(
      test("each expression case maps to its Expr counterpart") {
        assertTrue(
          EnvValue.secret("PGP_PASSPHRASE").asExpr.contains(Expr.secret("PGP_PASSPHRASE")),
          EnvValue.env("DEPLOY_ROLE").asExpr.contains(Expr.env("DEPLOY_ROLE")),
          EnvValue.expr("${{ github.sha }}").asExpr.contains(Expr.raw("${{ github.sha }}")),
        )
      },
      test("Plain has no Expr form, because its text may be more than a Lit can hold") {
        assertTrue(
          EnvValue.plain("us-west-2").asExpr.isEmpty,
          EnvValue.plain("-----BEGIN PGP-----\nline two\n").render == "-----BEGIN PGP-----\nline two\n",
        )
      },
      test("render agrees with asExpr wherever there is one") {
        val values = List(
          EnvValue.secret("PGP_SECRET"),
          EnvValue.env("TIER"),
          EnvValue.plain("staging"),
          EnvValue.expr("${{ github.run_id }}"),
        )
        assertTrue(values.forall(v => v.asExpr.forall(_.render == v.render)))
      },
      test("a case cannot be constructed with an invalid name, even bypassing the constructors") {
        for
          secret <- typeCheck("""zipx.core.EnvValue.FromSecret("bad name")""")
          env    <- typeCheck("""zipx.core.EnvValue.FromEnv("bad-name")""")
          raw    <- typeCheck("""zipx.core.EnvValue.Expr("${{ unbalanced")""")
        yield assertTrue(
          secret.isLeft,
          env.isLeft,
          raw.isLeft,
          EnvValue.Plain("anything at all ${{ }}").render == "anything at all ${{ }}",
        )
      },
      test("a runtime name goes through the Make sibling and reports the reason as a value") {
        assertTrue(
          EnvValue.secretMake("PGP_SECRET").map(_.render).contains("${{ secrets.PGP_SECRET }}"),
          EnvValue.secretMake("bad name").isLeft,
          EnvValue.envMake("bad-name").isLeft,
          EnvValue.exprMake("${{ unbalanced").isLeft,
          EnvValue.secretMake("bad name").left.exists(_.contains("secret name")),
        )
      },
      test("a secret may be GITHUB_TOKEN but an env key may not be GITHUB_-prefixed") {
        for
          pat    <- typeCheck("""zipx.core.EnvValue.secret("GITHUB_PAT")""")
          output <- typeCheck("""zipx.core.EnvValue.env("GITHUB_OUTPUT")""")
        yield assertTrue(
          EnvValue.secret("GITHUB_TOKEN").render == "${{ secrets.GITHUB_TOKEN }}",
          pat.isLeft,
          output.isLeft,
          EnvValue.env("PUBLISH_GITHUB_PACKAGES").render == "${{ env.PUBLISH_GITHUB_PACKAGES }}",
        )
      },
    ),
    suite("JobCondition.expr")(
      test("a validated condition becomes an expression with the same text") {
        val c = JobCondition.and(JobCondition.repositoryIs("early-effect/zipx"), JobCondition.onReleaseTag)
        assertTrue(c.expr.unwrapped == c.render)
      },
      test("every leaf survives the round trip") {
        val leaves = List(
          JobCondition.repositoryIs("acme/repo"),
          JobCondition.varNonEmpty("PUBLISH_PACKAGES_REPO"),
          JobCondition.refIs("refs/heads/main"),
          JobCondition.refStartsWith("refs/tags/v"),
          JobCondition.eventIs("pull_request"),
          JobCondition.hasPrLabel("clean"),
          JobCondition.raw("always()"),
        )
        assertTrue(leaves.forall(c => c.expr.unwrapped == c.render))
      },
      test("expr is structural, not a Raw wrapper, so the operator jointing has one definition") {
        val leaf = JobCondition.hasPrLabelMake("a" * 30).toOption.get
        val wide = JobCondition.allOf(List.fill(40)(leaf)).get
        assertTrue(
          wide.render.length > 1024,
          wide.expr.unwrapped == wide.render,
          !wide.expr.isInstanceOf[Expr.Raw],
        )
      },
      test("Raw is the only case that carries a RawExpr, which is what the escape hatch means") {
        assertTrue(
          JobCondition.raw("always()").expr == Expr.raw("always()"),
          JobCondition.rawMake("  always()  ").map(_.render).contains("always()"),
          JobCondition.rawMake("   ").isLeft,
        )
      },
    ),
  )
end ExprBridgeSpec
