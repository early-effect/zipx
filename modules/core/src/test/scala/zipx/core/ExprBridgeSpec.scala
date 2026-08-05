package zipx.core

import zio.test.*
import zipx.workflow.Expr

/** The two seams that couple `zipx-core`'s condition and env models to `zipx-workflow`'s [[Expr]].
  *
  * [[zipx.core.EnvValueSpec]] and [[zipx.core.JobConditionSpec]] pin the rendered strings; this covers what the
  * delegation adds. Both bridges are total, and that is the property under test here: every case of both enums holds a
  * validated newtype, so `asExpr` / `expr` have no failure to report, and the rejection tests assert on a compile error
  * or a `Left` rather than on a caught exception.
  */
object ExprBridgeSpec extends ZIOSpecDefault:

  def spec = suite("Expr bridges")(
    suite("EnvValue.asExpr")(
      test("each case maps to its Expr counterpart") {
        assertTrue(
          EnvValue.secret("PGP_PASSPHRASE").asExpr == Expr.secret("PGP_PASSPHRASE"),
          EnvValue.env("DEPLOY_ROLE").asExpr == Expr.env("DEPLOY_ROLE"),
          EnvValue.plain("us-west-2").asExpr == Expr.lit("us-west-2"),
          EnvValue.expr("${{ github.sha }}").asExpr == Expr.raw("${{ github.sha }}"),
        )
      },
      test("render goes through asExpr, so the two never disagree") {
        val values = List(
          EnvValue.secret("PGP_SECRET"),
          EnvValue.env("TIER"),
          EnvValue.plain("staging"),
          EnvValue.expr("${{ github.run_id }}"),
        )
        assertTrue(values.forall(v => v.render == v.asExpr.render))
      },
      test("a case cannot be constructed with an invalid name, even bypassing the constructors") {
        // `EnvValue` is a public enum, so a caller can write `FromSecret(...)` directly. The case holds a `SecretName`
        // rather than a `String`, so there is no bad value to pass: the mistake is a compile error at the construction
        // site instead of a check inside `render`.
        for
          secret <- typeCheck("""zipx.core.EnvValue.FromSecret("bad name")""")
          env    <- typeCheck("""zipx.core.EnvValue.FromEnv("bad-name")""")
          raw    <- typeCheck("""zipx.core.EnvValue.Expr("${{ unbalanced")""")
        yield assertTrue(
          secret.isLeft,
          env.isLeft,
          raw.isLeft,
          // Plain is the one case with nothing to validate, deliberately: it is data, not an expression.
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
        // The rules genuinely differ: `secrets.GITHUB_TOKEN` is the documented way to read the injected token, while
        // an `env:` key in GitHub's own namespace collides with a default variable.
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
        // Worth pinning: `expr` used to re-parse `render`'s output into `Expr.Raw`, which meant a length bound applied
        // to the *joint* and a wide condition failed at plan time. Now every clause is a typed case, so a condition
        // that renders is a condition that has an `Expr`, with no second rule to satisfy.
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
