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
      test("Lit, Quoted and Raw are the bare cases: everything else wraps itself") {
        // The asymmetry is deliberate. It is what lets Concat interleave fixed text with expressions, and what lets a
        // Quoted sit inside a Call's argument list without a nested wrapper.
        assertTrue(
          Expr.lit("sbt-").render == "sbt-",
          Expr.quoted("refs/tags/v").render == "'refs/tags/v'",
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
    suite("Call")(
      test("arguments render unwrapped, since a call is already an expression context") {
        // The whole point of the case. `contains(${{ needs.x.outputs.y }}, 'id')` is a template string GitHub evaluates
        // to neither operand, so an argument must render bare even though the call itself wraps.
        val call = Expr.contains(Expr.jobOutput("affected", "modules"), Expr.quoted("api"))
        assertTrue(
          call.unwrapped == "contains(needs.affected.outputs.modules, 'api')",
          call.render == "${{ contains(needs.affected.outputs.modules, 'api') }}",
        )
      },
      test("nested calls nest their arguments, not their wrappers") {
        assertTrue(
          Expr
            .contains(Expr.fromJson(Expr.jobOutput("affected", "modules")), Expr.quoted("all"))
            .unwrapped == "contains(fromJson(needs.affected.outputs.modules), 'all')"
        )
      },
      test("a nullary call renders empty parens") {
        assertTrue(
          Expr.cancelled.unwrapped == "cancelled()",
          (!Expr.cancelled).unwrapped == "!cancelled()",
        )
      },
      test("startsWith takes a quoted prefix, which is the planner's tag gate") {
        assertTrue(
          Expr.startsWith(Expr.github("ref"), Expr.quoted("refs/tags/v")).unwrapped ==
            "startsWith(github.ref, 'refs/tags/v')"
        )
      },
      test("call rejects an unknown function name at runtime, and accepts either JSON spelling") {
        // GitHub's expression language is case-insensitive and has no user-defined functions, so `fromJSON` and
        // `fromJson` are one function and anything off the list is a workflow parse error rather than a false value.
        assertTrue(
          Expr.callMake("nosuchFunction").isLeft,
          Expr.callMake("nosuchFunction").swap.exists(_.contains("no user-defined functions")),
          Expr.callMake("fromJSON", Expr.lit("x")).map(_.unwrapped) == Right("fromJSON(x)"),
          Expr.callMake("fromJson", Expr.lit("x")).map(_.unwrapped) == Right("fromJson(x)"),
        )
      },
      test("quotedMake rejects what cannot be single-quoted") {
        // The literal is emitted between '…' with no escaping, so a quote, `$` or whitespace would either close the
        // quote early or turn the argument into a nested expression.
        assertTrue(
          Expr.quotedMake("it's").isLeft,
          Expr.quotedMake("$HOME").isLeft,
          Expr.quotedMake("two words").isLeft,
          Expr.quotedMake("").isLeft,
          Expr.quotedMake("refs/tags/v1.0.0") == Right(Expr.quoted("refs/tags/v1.0.0")),
        )
      },
    ),
    suite("operators")(
      test("&& and || join bare, and only Group emits a paren") {
        // Unlike JobCondition, which parenthesizes every clause defensively because its operands are user-supplied.
        // Here the operands are assembled in this codebase, so the rendered bytes are the author's to control.
        val a = Expr.github("event_name") === Expr.quoted("push")
        val b = Expr.jobResult("gate") !== Expr.quoted("success")
        assertTrue(
          (a && b).unwrapped == "github.event_name == 'push' && needs.gate.result != 'success'",
          (a || b).unwrapped == "github.event_name == 'push' || needs.gate.result != 'success'",
          Expr.group(a).unwrapped == "(github.event_name == 'push')",
        )
      },
      test("! negates without parens, so !( … ) is written explicitly") {
        val call = Expr.cancelled
        assertTrue(
          (!call).unwrapped == "!cancelled()",
          (!Expr.group(call)).unwrapped == "!(cancelled())",
        )
      },
      test("the planner's verify gate composes to the exact string it has always emitted") {
        // The byte-for-byte target, lifted from .github/workflows/ci.yml. `Group` placement is what makes the `||` bind
        // its two clauses only, and `!cancelled()` is what keeps the job reachable after a *skipped* gate.
        val gate =
          !Expr.cancelled &&
            !Expr.startsWith(Expr.github("ref"), Expr.quoted("refs/tags/")) &&
            (Expr.github("event_name") !== Expr.quoted("workflow_dispatch")) &&
            Expr.group(
              Expr.group(Expr.jobResult("verify-gate") !== Expr.quoted("success")) ||
                Expr.group(Expr.jobOutput("verify-gate", "run") === Expr.quoted("true"))
            )
        assertTrue(
          gate.unwrapped ==
            "!cancelled() && !startsWith(github.ref, 'refs/tags/') && github.event_name != 'workflow_dispatch' && " +
            "((needs.verify-gate.result != 'success') || (needs.verify-gate.outputs.run == 'true'))"
        )
      },
      test("operators are left-associative, matching Scala's own precedence") {
        val a = Expr.lit("a")
        val b = Expr.lit("b")
        val c = Expr.lit("c")
        assertTrue(
          (a && b && c) == Expr.Join(Expr.Join(a, JoinOp.And, b), JoinOp.And, c),
          // `&&` binds tighter than `||`, so this is `a || (b && c)` with no parens rendered either way.
          (a || b && c).unwrapped == "a || b && c",
          (a || b && c) == Expr.Join(a, JoinOp.Or, Expr.Join(b, JoinOp.And, c)),
        )
      },
      test("=== builds a comparison where == would compare the Scala values") {
        // Why the operator is spelled `===`: `==` is `Any`'s, so it can only ever answer "are these the same AST", not
        // "emit a GHA equality test". Both are useful and neither can be the other.
        val e = Expr.lit("x")
        assertTrue(
          (e === e) == Expr.Compare(e, CompareOp.Eq, e),
          (e === e).unwrapped == "x == x",
          e == Expr.lit("x"),
        )
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
