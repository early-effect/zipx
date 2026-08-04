package zipx.workflow

import zio.test.*

/** What [[Expr]] renders, which is the string that lands in the YAML.
  *
  * [[NamesSpec]] covers the name rules; this covers the jointing: which cases wrap themselves in `${{ }}`, how `Concat`
  * flattens, and that [[Expr.asWord]] hands the shell layer something it will not escape.
  */
object ExprSpec extends ZIOSpecDefault:

  def spec = suite("Expr")(
    suite("render")(
      test("each context renders its documented expression") {
        assertTrue(
          Expr.secret("PGP_PASSPHRASE").render == "${{ secrets.PGP_PASSPHRASE }}",
          Expr.env("DEPLOY_ROLE").render == "${{ env.DEPLOY_ROLE }}",
          Expr.vars("PUBLISH_PACKAGES_REPO").render == "${{ vars.PUBLISH_PACKAGES_REPO }}",
          Expr.github("sha").render == "${{ github.sha }}",
          Expr.github("event.pull_request.base.sha").render == "${{ github.event.pull_request.base.sha }}",
          Expr.runner("os").render == "${{ runner.os }}",
          Expr.stepOutput("check", "run").render == "${{ steps.check.outputs.run }}",
          Expr.jobOutput("verify-gate", "run").render == "${{ needs.verify-gate.outputs.run }}",
          Expr.jobResult("test").render == "${{ needs.test.result }}",
          Expr.matrix("scala").render == "${{ matrix.scala }}",
        )
      },
      test("githubToken is the short documented spelling") {
        assertTrue(Expr.githubToken.render == "${{ github.token }}")
      },
      test("Lit and Raw are the two bare cases: everything else wraps itself") {
        // The asymmetry is deliberate. It is what lets Concat interleave fixed text with expressions.
        assertTrue(
          Expr.lit("sbt-").render == "sbt-",
          Expr.raw("always()").render == "always()",
          Expr.raw("${{ github.sha }}").render == "${{ github.sha }}",
        )
      },
      test("render is a pure function of the value") {
        val e = Expr.concat(Expr.lit("sbt-"), Expr.runner("os"), Expr.lit("-"), Expr.github("sha"))
        assertTrue(List.fill(50)(e.render).distinct.size == 1)
      },
    ),
    suite("Concat")(
      test("assembles a cache key without interpolation") {
        assertTrue(
          Expr
            .concat(Expr.lit("sbt-"), Expr.runner("os"), Expr.lit("-"), Expr.matrix("scala"))
            .render == "sbt-${{ runner.os }}-${{ matrix.scala }}"
        )
      },
      test("++ flattens, so a chain does not nest") {
        val a = Expr.lit("a")
        val b = Expr.lit("b")
        val c = Expr.lit("c")
        assertTrue(
          (a ++ b ++ c) == Expr.Concat(List(a, b, c)),
          (a ++ (b ++ c)) == Expr.Concat(List(a, b, c)),
          (Expr.concat(a, b) ++ Expr.concat(b, c)) == Expr.Concat(List(a, b, b, c)),
          (a ++ b ++ c).render == "abc",
        )
      },
      test("++ is associative in its rendering") {
        val a = Expr.github("sha")
        val b = Expr.lit("-")
        val c = Expr.matrix("scala")
        assertTrue(((a ++ b) ++ c).render == (a ++ (b ++ c)).render)
      },
      test("concat of one part is that part, not a wrapper") {
        assertTrue(
          Expr.concat(Expr.lit("solo")) == Expr.lit("solo"),
          Expr.concat().render == "",
        )
      },
      test("concat flattens nested Concats passed in as arguments") {
        assertTrue(
          Expr.concat(Expr.concat(Expr.lit("a"), Expr.lit("b")), Expr.lit("c")) ==
            Expr.Concat(List(Expr.lit("a"), Expr.lit("b"), Expr.lit("c")))
        )
      },
    ),
    suite("unwrapped")(
      test("drops the ${{ }} wrapper, which is what an if: wants") {
        // An `if:` is evaluated as an expression whether or not it is wrapped, and bare is the form that composes:
        // `${{ a }} && ${{ b }}` is a template string that evaluates to neither operand.
        assertTrue(
          Expr.github("event_name").unwrapped == "github.event_name",
          Expr.secret("PGP_SECRET").unwrapped == "secrets.PGP_SECRET",
          Expr.stepOutput("check", "run").unwrapped == "steps.check.outputs.run",
          Expr.jobResult("verify-gate").unwrapped == "needs.verify-gate.result",
          Expr.matrix("scala").unwrapped == "matrix.scala",
        )
      },
      test("a literal and a raw expression are already bare, so they are unchanged") {
        assertTrue(
          Expr.lit("refs/tags/v1.0.0").unwrapped == "refs/tags/v1.0.0",
          Expr.raw("!cancelled()").unwrapped == "!cancelled()",
          // A raw expression that *does* carry a wrapper keeps it: unwrapped strips Expr's own wrapper, not the
          // caller's text, which nothing here is entitled to rewrite.
          Expr.raw("${{ github.sha }}").unwrapped == "${{ github.sha }}",
        )
      },
      test("a concat unwraps part by part, so a composed condition is a single expression") {
        val cond = Expr.raw("!cancelled() && ") ++ Expr.jobResult("verify-gate")
        assertTrue(
          cond.unwrapped == "!cancelled() && needs.verify-gate.result",
          // render would produce the broken template form; that contrast is the reason unwrapped exists.
          cond.render == "!cancelled() && ${{ needs.verify-gate.result }}",
        )
      },
      test("render and unwrapped differ by exactly the wrapper") {
        val e = Expr.env("DEPLOY_ROLE")
        assertTrue(e.render == s"$${{ ${e.unwrapped} }}")
      },
    ),
    suite("asWord")(
      test("an expression survives into a shell script unescaped") {
        // Word.Opaque specifically: the `$` and `{` of an expression must reach the YAML intact, so this is the one
        // word kind the shell renderer never quotes or escapes.
        val word = Expr.github("event_name").asWord
        assertTrue(
          word == zipx.shell.Word.Opaque(zipx.shell.ShText.makeOrThrow("${{ github.event_name }}")),
          word.render == "${{ github.event_name }}",
        )
      },
      test("an expression works inside a shell test and a command") {
        import zipx.shell.*
        val eventName = Expr.github("event_name").asWord
        assertTrue(
          Exec("echo", eventName).inlineRender == "echo ${{ github.event_name }}",
          ShTest.StrEq(eventName, Word.squote("push")).render == "[ ${{ github.event_name }} = 'push' ]",
        )
      },
      test("a concatenated expression also becomes one opaque word") {
        val word = (Expr.lit("v") ++ Expr.github("sha")).asWord
        assertTrue(word.render == "v${{ github.sha }}")
      },
    ),
    suite("smart constructors")(
      test("the *Make siblings return Left for a bad runtime name") {
        assertTrue(
          Expr.secretMake("GITHUB_PAT").isLeft,
          Expr.secretMake("bad-name").isLeft,
          Expr.envMake("1BAD").isLeft,
          Expr.varsMake("").isLeft,
          Expr.githubMake("event..sha").isLeft,
          Expr.matrixMake("include").isLeft,
          Expr.rawMake("${{ unbalanced").isLeft,
        )
      },
      test("the *Make siblings return Right and render the same as the literal form") {
        assertTrue(
          Expr.secretMake("PGP_SECRET") == Right(Expr.secret("PGP_SECRET")),
          Expr.envMake("DEPLOY_ROLE") == Right(Expr.env("DEPLOY_ROLE")),
          Expr.githubMake("sha") == Right(Expr.github("sha")),
          Expr.matrixMake("scala") == Right(Expr.matrix("scala")),
          Expr.rawMake("always()") == Right(Expr.raw("always()")),
        )
      },
      test("a two-name constructor reports the first failing name") {
        assertTrue(
          Expr.stepOutputMake("1check", "run").isLeft,
          Expr.stepOutputMake("check", "set-output").isLeft,
          Expr.stepOutputMake("check", "run") == Right(Expr.stepOutput("check", "run")),
          Expr.jobOutputMake("gate", "modules") == Right(Expr.jobOutput("gate", "modules")),
        )
      },
      test("a *Make error carries the newtype's own message, not a generic one") {
        assertTrue(
          Expr.secretMake("GITHUB_PAT").swap.exists(_.contains("GITHUB_ prefix")),
          Expr.matrixMake("include").swap.exists(_.contains("directive")),
        )
      },
    ),
  )
end ExprSpec
