package zipx.workflow

import neotype.unwrap
import zio.test.*

/** Every Actions-syntax newtype's accepting cases, rejecting cases, and boundaries.
  *
  * `make` runs the same `validate` the compile-time path does, so testing through `make` covers both rules; that a bad
  * *literal* also fails the build is asserted separately in [[WorkflowCompileTimeSpec]].
  *
  * The rules here are GitHub's, not zipx's, so each suite says which one it is enforcing. Where a rule is a character
  * class rather than a fixed list, a generator covers it so the suite is not limited to the cases we thought of.
  */
object NamesSpec extends ZIOSpecDefault:

  private val gIdentStart: Gen[Any, Char] = Gen.elements((('A' to 'Z') ++ ('a' to 'z') :+ '_')*)
  private val gIdentRest: Gen[Any, Char]  = Gen.elements((('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') :+ '_')*)

  /** An identifier that every one of these newtypes must accept, other than the reserved-name rules. */
  private val gIdent: Gen[Any, String] =
    for
      head <- gIdentStart
      tail <- Gen.listOf(gIdentRest)
    yield (head :: tail).mkString

  /** Characters that appear in a hand-written expression and must not survive into a name. */
  private val expressionChars = List("$", "{", "}", " ", "'", "\"", ".", "/", "(", ")", "*", "[", "]", "!", "&", "|")

  def spec = suite("names")(
    suite("JobId / StepId")(
      test("accepts GitHub's id shape: letter or _ first, then letters, digits, - or _") {
        assertTrue(
          JobId.make("build").isRight,
          JobId.make("build-and-test").isRight,
          JobId.make("_internal").isRight,
          JobId.make("a").isRight,
          JobId.make("_").isRight,
          JobId.make("j2").isRight,
          JobId.make("verify_clean-full2").isRight,
          StepId.make("check").isRight,
          StepId.make("resolve-epoch").isRight,
        )
      },
      test("rejects a digit-leading id, which is a hard workflow parse error") {
        assertTrue(
          JobId.make("1build").isLeft,
          JobId.make("2").isLeft,
          StepId.make("0check").isLeft,
        )
      },
      test("rejects empty and every expression-shaped character") {
        assertTrue(
          JobId.make("").isLeft,
          StepId.make("").isLeft,
          expressionChars.forall(c => JobId.make(s"job${c}id").isLeft),
          expressionChars.forall(c => StepId.make(s"step${c}id").isLeft),
        )
      },
      test("rejects a leading hyphen, which is legal mid-id but not at the start") {
        assertTrue(JobId.make("-build").isLeft, StepId.make("-check").isLeft)
      },
      test("accepts any identifier") {
        check(gIdent)(s => assertTrue(JobId.make(s).isRight, StepId.make(s).isRight))
      },
      test("make round-trips the input unchanged") {
        assertTrue(JobId.make("build-1").map(_.unwrap) == Right("build-1"))
      },
    ),
    suite("SecretName")(
      test("accepts the underscored-uppercase shape secrets actually use") {
        assertTrue(
          List("PGP_KEY_HEX", "PGP_SECRET", "PGP_PASSPHRASE", "SONATYPE_USERNAME", "GH_PACKAGES_TOKEN")
            .forall(n => SecretName.make(n).isRight),
          SecretName.make("_PRIVATE").isRight,
          SecretName.make("lowercase_is_stored_uppercase").isRight,
        )
      },
      test("rejects hyphens and digit-leading names") {
        assertTrue(
          SecretName.make("PGP-PASSPHRASE").isLeft,
          SecretName.make("1PASSWORD").isLeft,
          SecretName.make("").isLeft,
        )
      },
      test("rejects an expression-shaped name") {
        assertTrue(
          SecretName.make("${{ secrets.X }}").isLeft,
          SecretName.make("secrets.X").isLeft,
          SecretName.make("PGP PASSPHRASE").isLeft,
        )
      },
      test("rejects the reserved GITHUB_ prefix, case-insensitively") {
        // GitHub stores secret names uppercase and matches them case-insensitively, so `github_pat` is the same
        // reserved name as `GITHUB_PAT`. A validator that only checked the uppercase spelling would be bypassable.
        assertTrue(
          SecretName.make("GITHUB_PAT").isLeft,
          SecretName.make("github_pat").isLeft,
          SecretName.make("GitHub_Pat").isLeft,
          SecretName.make("GITHUB_").isLeft,
        )
      },
      test("accepts GITHUB_TOKEN itself, and only it") {
        // Not a secret you create: GitHub injects it, and `secrets.GITHUB_TOKEN` is the documented way to read it.
        assertTrue(
          SecretName.make("GITHUB_TOKEN").isRight,
          SecretName.make("github_token").isRight,
          SecretName.make("GITHUB_TOKEN_2").isLeft,
          SecretName.make("MY_GITHUB_TOKEN").isRight, // the prefix rule is about the start, not a substring
        )
      },
    ),
    suite("EnvName")(
      test("accepts POSIX-shaped names, agreeing with zipx-shell's VarName") {
        // The agreement is the point: an `env:` key becomes a shell variable in every `run:` step.
        check(gIdent) { s =>
          assertTrue(EnvName.make(s).isRight == zipx.shell.VarName.make(s).isRight)
        }
      },
      test("rejects hyphens, digit-leading, empty, and expression shapes") {
        assertTrue(
          EnvName.make("bad-name").isLeft,
          EnvName.make("1BAD").isLeft,
          EnvName.make("").isLeft,
          EnvName.make("${{ env.X }}").isLeft,
          EnvName.make("env.X").isLeft,
          EnvName.make("_PRIVATE").isRight,
        )
      },
      test("rejects the GITHUB_ prefix, which is GitHub's own default-variable namespace") {
        assertTrue(
          EnvName.make("GITHUB_OUTPUT").isLeft,
          EnvName.make("GITHUB_TOKEN").isLeft, // unlike a secret: setting this as an env *key* is not the same thing
          EnvName.make("github_output").isLeft,
          EnvName.make("PUBLISH_GITHUB_PACKAGES").isRight,
        )
      },
    ),
    suite("OutputName")(
      test("accepts identifier-shaped output names") {
        assertTrue(
          OutputName.make("epoch").isRight,
          OutputName.make("release").isRight,
          OutputName.make("run").isRight,
          OutputName.make("modules").isRight,
          OutputName.make("image-tag").isRight,
        )
      },
      test("rejects the disabled workflow commands, whatever their case") {
        assertTrue(
          OutputName.make("set-output").isLeft,
          OutputName.make("SET-OUTPUT").isLeft,
          OutputName.make("save-state").isLeft,
          OutputName.make("set-output-2").isRight, // only the exact command names are reserved
        )
      },
      test("rejects empty and expression shapes") {
        assertTrue(
          OutputName.make("").isLeft,
          OutputName.make("steps.check.outputs.run").isLeft,
          OutputName.make("1st").isLeft,
        )
      },
    ),
    suite("MatrixAxis")(
      test("accepts axis names") {
        assertTrue(
          MatrixAxis.make("scala").isRight,
          MatrixAxis.make("os").isRight,
          MatrixAxis.make("java-version").isRight,
        )
      },
      test("rejects the include / exclude directives, which are not axes") {
        assertTrue(
          MatrixAxis.make("include").isLeft,
          MatrixAxis.make("exclude").isLeft,
          MatrixAxis.make("includes").isRight, // only the exact directive names
          MatrixAxis.make("Include").isRight,  // matrix keys are case-sensitive, unlike secrets
        )
      },
      test("rejects empty and dotted paths") {
        assertTrue(MatrixAxis.make("").isLeft, MatrixAxis.make("matrix.scala").isLeft)
      },
    ),
    suite("ContextPath")(
      test("accepts the paths the planner uses") {
        assertTrue(
          ContextPath.make("sha").isRight,
          ContextPath.make("os").isRight,
          ContextPath.make("token").isRight,
          ContextPath.make("event_name").isRight,
          ContextPath.make("head_ref").isRight,
          ContextPath.make("event.pull_request.base.sha").isRight,
          ContextPath.make("event.pull_request.number").isRight,
        )
      },
      test("accepts a wildcard segment and an array index") {
        assertTrue(
          ContextPath.make("event.pull_request.labels.*.name").isRight,
          ContextPath.make("event.commits[0].id").isRight,
          ContextPath.make("event.commits[0][1].id").isRight,
        )
      },
      test("rejects an empty segment, which GitHub fails to parse") {
        assertTrue(
          ContextPath.make("").isLeft,
          ContextPath.make("event..sha").isLeft,
          ContextPath.make(".sha").isLeft,
          ContextPath.make("event.").isLeft,
        )
      },
      test("rejects an unbalanced or non-numeric bracket") {
        assertTrue(
          ContextPath.make("event.commits[0.id").isLeft,
          ContextPath.make("event.commits0].id").isLeft,
          ContextPath.make("event.commits[].id").isLeft,
          ContextPath.make("event.commits[x].id").isLeft,
        )
      },
      test("rejects whitespace, quotes, and a nested expression") {
        assertTrue(
          ContextPath.make("event name").isLeft,
          ContextPath.make("event['x']").isLeft,
          ContextPath.make("${{ github.sha }}").isLeft,
          ContextPath.make("github.sha }}").isLeft,
        )
      },
      test("rejects a leading wildcard, which has no collection to range over") {
        assertTrue(ContextPath.make("*.name").isLeft, ContextPath.make("*").isLeft)
      },
    ),
    suite("ActionRef")(
      test("accepts owner/repo@ref, with or without a subpath") {
        assertTrue(
          ActionRef.make("actions/checkout@v4").isRight,
          ActionRef.make("actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683").isRight,
          ActionRef.make("actions/cache/restore@v4").isRight,
          ActionRef.make("owner/repo/deep/path@main").isRight,
          ActionRef.make("sbt/setup-sbt@v1").isRight,
        )
      },
      test("accepts a local action and a docker image") {
        assertTrue(
          ActionRef.make("./.github/actions/setup").isRight,
          ActionRef.make("docker://alpine:3.19").isRight,
          ActionRef.make("docker://ghcr.io/owner/image:tag").isRight,
        )
      },
      test("rejects an unpinned owner/repo, with an error that says to add the ref") {
        // Unpinned actions are exactly what ActionPinFile exists to prevent, so the type refuses to express one.
        assertTrue(
          ActionRef.make("actions/checkout").isLeft,
          ActionRef.make("actions/checkout").swap.exists(_.contains("@ref")),
          ActionRef.make("actions/checkout@").isLeft,
        )
      },
      test("rejects a bare name, an absolute path, and expression shapes") {
        assertTrue(
          ActionRef.make("").isLeft,
          ActionRef.make("checkout").isLeft,
          ActionRef.make("/abs/path").isLeft,
          ActionRef.make("../escape/upward").isLeft,
          ActionRef.make("actions/checkout@v4 ").isLeft,
          ActionRef.make("${{ env.ACTION }}").isLeft,
        )
      },
    ),
    suite("EventName")(
      test("accepts webhook event names") {
        assertTrue(
          EventName.make("push").isRight,
          EventName.make("pull_request").isRight,
          EventName.make("workflow_dispatch").isRight,
          EventName.make("schedule").isRight,
          EventName.make("merge_group").isRight, // shape only, so a newer event is not rejected
        )
      },
      test("rejects empty, hyphens, and expression shapes") {
        assertTrue(
          EventName.make("").isLeft,
          EventName.make("pull-request").isLeft,
          EventName.make("'push'").isLeft,
          EventName.make("github.event_name").isLeft,
        )
      },
    ),
    suite("FunctionName")(
      test("accepts every function GitHub documents, in any casing") {
        assertTrue(
          List(
            "contains",
            "startsWith",
            "endsWith",
            "format",
            "join",
            "toJSON",
            "fromJSON",
            "hashFiles",
            "success",
            "always",
            "cancelled",
            "failure",
          ).forall(f => FunctionName.make(f).isRight)
        )
      },
      test("matching is case-insensitive, because the expression language is") {
        // `fromJson` and `fromJSON` are one function, so accepting one spelling and rejecting the other would reject
        // valid workflows over a detail GitHub does not care about.
        assertTrue(
          List("fromJson", "fromJSON", "FROMJSON", "fromjson", "StartsWith", "TOJSON")
            .forall(f => FunctionName.make(f).isRight)
        )
      },
      test("rejects an unknown name: there are no user-defined functions") {
        assertTrue(
          FunctionName.make("").isLeft,
          FunctionName.make("myHelper").isLeft,
          FunctionName.make("startWith").isLeft,  // a real typo, one character off
          FunctionName.make("contains(").isLeft,  // the call syntax, not the name
          FunctionName.make("fromJson ").isLeft,  // trailing space
          FunctionName.make("not").isLeft,        // an operator, not a function
          FunctionName.make("containsAll").isLeft, // a prefix match must not pass
        )
      },
      test("the error says why, since the rule is a list rather than a shape") {
        assertTrue(
          FunctionName.make("myHelper").swap.exists(_.contains("no user-defined functions")),
          FunctionName.make("").swap.exists(_.contains("non-empty")),
        )
      },
    ),
    suite("ExprLiteral")(
      test("accepts refs, repo slugs, and labels") {
        assertTrue(
          List(
            "refs/heads/main",
            "refs/tags/v1.0.0",
            "refs/pull/42/head",
            "early-effect/zipx",
            "a/b+c-d:e.f_g@h:i",
            "deploy-stg",
            "deploy.stg",
            "clean",
          ).forall(s => ExprLiteral.make(s).isRight)
        )
      },
      test("rejects a quote, a dollar, or whitespace, none of which can be escaped inside '…'") {
        assertTrue(
          List("'", "\"", "$", " ", "\t", "\n", "(", ")", "{", "}", "|", "&", ";", "`", "!", "?", "#", "\\", "%", "^")
            .forall(c => ExprLiteral.make(s"org/repo${c}name").isLeft)
        )
      },
      test("rejects empty, and unicode outside the ASCII set") {
        assertTrue(
          ExprLiteral.make("").isLeft,
          ExprLiteral.make("🚢").isLeft,
          ExprLiteral.make("café").isLeft,
        )
      },
      test("bounds the length at 256") {
        assertTrue(
          ExprLiteral.make("a" * 256).isRight,
          ExprLiteral.make("a" * 257).isLeft,
          ExprLiteral.make("a" * 300).isLeft,
        )
      },
    ),
    suite("RawExpr")(
      test("accepts a bare expression and a fully wrapped one") {
        assertTrue(
          RawExpr.make("always()").isRight,
          RawExpr.make("github.event_name == 'push'").isRight,
          RawExpr.make("(github.event_name == 'pull_request') && (github.base_ref == 'main')").isRight,
          RawExpr.make("${{ github.sha }}").isRight,
          RawExpr.make("${{ github.sha }}-${{ github.run_id }}").isRight,
          RawExpr.make("sbt-${{ runner.os }}-key").isRight,
        )
      },
      test("rejects empty, blank, and multi-line") {
        assertTrue(
          RawExpr.make("").isLeft,
          RawExpr.make("   ").isLeft,
          RawExpr.make("always()\n&& true").isLeft,
          RawExpr.make("always()\r").isLeft,
        )
      },
      test("rejects unbalanced ${{ }} delimiters, in either direction") {
        assertTrue(
          RawExpr.make("${{ github.sha").isLeft,
          RawExpr.make("github.sha }}").isLeft,
          RawExpr.make("${{ ${{ github.sha }}").isLeft,
          RawExpr.make("${{ github.sha }} }}").isLeft,
        )
      },
      test("bounds the length at 1024") {
        assertTrue(
          RawExpr.make("a" * 1024).isRight,
          RawExpr.make("a" * 1025).isLeft,
        )
      },
      test("does not trim: an expression keeps the spacing the caller chose") {
        // Trimming belongs to the caller (JobCondition.raw trims before delegating), so `render` stays byte-faithful.
        assertTrue(RawExpr.make(" always() ").map(_.unwrap) == Right(" always() "))
      },
    ),
  )
end NamesSpec
