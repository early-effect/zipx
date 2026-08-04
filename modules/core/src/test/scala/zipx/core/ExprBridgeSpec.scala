package zipx.core

import zio.test.*
import zipx.workflow.Expr

/** The two one-liners that couple `zipx-core`'s condition and env models to `zipx-workflow`'s [[Expr]].
  *
  * [[zipx.core.EnvValueSpec]] and [[zipx.core.JobConditionSpec]] pin the rendered strings and pass unmodified; this
  * covers what the delegation adds: that both types can hand out an `Expr`, that the shared newtypes did not loosen or
  * tighten any rule those specs rely on, and the rules that only exist now that the names are typed.
  */
object ExprBridgeSpec extends ZIOSpecDefault:

  private def rejects(f: => Any): Boolean =
    try
      val _ = f
      false
    catch case _: IllegalArgumentException => true

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
      test("a case constructed directly, bypassing the smart constructors, is still validated") {
        // `EnvValue` is a public enum, so a caller can write `FromSecret("bad name")`. Rendering must not emit it.
        assertTrue(
          rejects(EnvValue.FromSecret("bad name").render),
          rejects(EnvValue.FromEnv("bad-name").render),
          rejects(EnvValue.Expr("${{ unbalanced").render),
          EnvValue.Plain("anything at all ${{ }}").render == "anything at all ${{ }}",
        )
      },
      test("a secret may be GITHUB_TOKEN but an env key may not be GITHUB_-prefixed") {
        // The rules genuinely differ: `secrets.GITHUB_TOKEN` is the documented way to read the injected token, while
        // an `env:` key in GitHub's own namespace collides with a default variable.
        assertTrue(
          EnvValue.secret("GITHUB_TOKEN").render == "${{ secrets.GITHUB_TOKEN }}",
          rejects(EnvValue.secret("GITHUB_PAT")),
          rejects(EnvValue.env("GITHUB_OUTPUT")),
          EnvValue.env("PUBLISH_GITHUB_PACKAGES").render == "${{ env.PUBLISH_GITHUB_PACKAGES }}",
        )
      },
      test("requireName still throws IllegalArgumentException, which is its public contract") {
        assertTrue(
          EnvValue.requireName("secret", "PGP_SECRET") == "PGP_SECRET",
          EnvValue.requireName("env", "TIER") == "TIER",
          rejects(EnvValue.requireName("secret", "bad-name")),
        )
      },
    ),
    suite("JobCondition.expr")(
      test("a validated condition becomes an expression with the same text") {
        val c = JobCondition.and(JobCondition.repositoryIs("early-effect/zipx"), JobCondition.onReleaseTag)
        // `Expr.rawMake`, not `Expr.raw`: `c.render` is not a literal, so the inline constructor rejects it at compile
        // time. That rejection is the property `ExprCompileTimeSpec` asserts directly.
        assertTrue(c.expr.render == c.render, Right(c.expr) == Expr.rawMake(c.render))
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
        assertTrue(leaves.forall(c => c.expr.render == c.render))
      },
      test("a condition too long to be a valid expression is rejected rather than emitted") {
        // RawExpr bounds the length, so an absurdly deep condition fails at plan time instead of producing an `if:`
        // line GitHub rejects. The literals are individually valid; it is the joint that overflows.
        val wide = JobCondition.and(List.fill(40)(JobCondition.hasPrLabel("a" * 30))*)
        assertTrue(wide.render.length > 1024, rejects(wide.expr))
      },
    ),
  )
end ExprBridgeSpec
