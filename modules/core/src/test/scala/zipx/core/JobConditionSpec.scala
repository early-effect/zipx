package zipx.core

import zio.test.*

object JobConditionSpec extends ZIOSpecDefault:

  /** True when `f` rejects its input with `IllegalArgumentException`. */
  private def rejects(f: => Any): Boolean =
    try
      val _ = f
      false
    catch case _: IllegalArgumentException => true

  def spec = suite("JobCondition")(
    suite("leaf render")(
      test("RepositoryIs") {
        assertTrue(
          JobCondition.repositoryIs("acme/repo").render == "github.repository == 'acme/repo'"
        )
      },
      test("VarNonEmpty") {
        assertTrue(JobCondition.varNonEmpty("PUBLISH_PACKAGES_REPO").render == "vars.PUBLISH_PACKAGES_REPO != ''")
      },
      test("RefIs") {
        assertTrue(JobCondition.refIs("refs/heads/main").render == "github.ref == 'refs/heads/main'")
      },
      test("RefStartsWith") {
        assertTrue(
          JobCondition.refStartsWith("refs/tags/v").render == "startsWith(github.ref, 'refs/tags/v')"
        )
      },
      test("EventIs / onWorkflowDispatch / onReleaseTag") {
        assertTrue(
          JobCondition.eventIs("workflow_dispatch").render == "github.event_name == 'workflow_dispatch'",
          JobCondition.onWorkflowDispatch.render == "github.event_name == 'workflow_dispatch'",
          JobCondition.onReleaseTag.render == "startsWith(github.ref, 'refs/tags/v')",
        )
      },
      test("HasPrLabel") {
        assertTrue(
          JobCondition
            .hasPrLabel("deploy-stg")
            .render == "contains(github.event.pull_request.labels.*.name, 'deploy-stg')"
        )
      },
    ),
    suite("algebra")(
      test("All joins with && and parentheses") {
        val c = JobCondition.and(JobCondition.repositoryIs("a/b"), JobCondition.hasPrLabel("x"))
        assertTrue(
          c.render == "(github.repository == 'a/b') && (contains(github.event.pull_request.labels.*.name, 'x'))"
        )
      },
      test("Any joins with || and parentheses") {
        val c = JobCondition.or(JobCondition.refIs("refs/heads/main"), JobCondition.refStartsWith("refs/tags/v"))
        assertTrue(
          c.render == "(github.ref == 'refs/heads/main') || (startsWith(github.ref, 'refs/tags/v'))"
        )
      },
      test("infix && / || / unary_! match and/or/not") {
        val a = JobCondition.repositoryIs("a/b")
        val b = JobCondition.onWorkflowDispatch
        val c = JobCondition.onReleaseTag
        assertTrue(
          (a && b).render == JobCondition.and(a, b).render,
          (b || c).render == JobCondition.or(b, c).render,
          (!a).render == JobCondition.not(a).render,
        )
      },
      test("infix && binds tighter than || (Boolean precedence)") {
        val a = JobCondition.repositoryIs("a/b")
        val b = JobCondition.onWorkflowDispatch
        val c = JobCondition.onReleaseTag
        // a || b && c ≡ a || (b && c), not (a || b) && c
        assertTrue(
          (a || b && c) == (a || (b && c)),
          (a || b && c) != ((a || b) && c),
          (a || b && c) == JobCondition.or(a, JobCondition.and(b, c)),
        )
      },
      test("infix && and || are left-associative") {
        val a = JobCondition.repositoryIs("a/b")
        val b = JobCondition.onWorkflowDispatch
        val c = JobCondition.onReleaseTag
        assertTrue(
          (a && b && c) == ((a && b) && c),
          (a && b && c) == JobCondition.and(JobCondition.and(a, b), c),
          (a || b || c) == ((a || b) || c),
          (a || b || c) == JobCondition.or(JobCondition.or(a, b), c),
        )
      },
      test("prefix ! binds tighter than && / ||") {
        val a = JobCondition.repositoryIs("a/b")
        val b = JobCondition.onWorkflowDispatch
        assertTrue(
          (!a && b) == ((!a) && b),
          (!a || b) == ((!a) || b),
          (!a && b) == JobCondition.and(JobCondition.not(a), b),
        )
      },
      test("Not wraps inner") {
        assertTrue(JobCondition.not(JobCondition.varNonEmpty("X")).render == "!(vars.X != '')")
      },
      test("nested All/Any/Not keeps parentheses") {
        val inner = JobCondition.or(JobCondition.refIs("refs/heads/main"), JobCondition.hasPrLabel("ship"))
        val c     = JobCondition.and(JobCondition.not(inner), JobCondition.repositoryIs("org/r"))
        assertTrue(
          c.render.contains("!(("),
          c.render.contains("||"),
          c.render.contains("github.repository == 'org/r'"),
        )
      },
      test("Not(Not(...))") {
        val c = JobCondition.not(JobCondition.not(JobCondition.refIs("refs/heads/main")))
        assertTrue(c.render == "!(!(github.ref == 'refs/heads/main'))")
      },
    ),
    suite("Raw")(
      test("trims and passes through") {
        assertTrue(JobCondition.raw("  always()  ").render == "always()")
      },
      test("operator-heavy expression preserved") {
        val expr = "(github.event_name == 'pull_request') && (github.base_ref == 'main')"
        assertTrue(JobCondition.raw(expr).render == expr)
      },
    ),
    suite("validation")(
      test("rejects empty / blank literals") {
        assertTrue(
          rejects(JobCondition.repositoryIs("")),
          rejects(JobCondition.hasPrLabel("  ")),
          rejects(JobCondition.raw("   ")),
        )
      },
      test("rejects quotes, dollars, whitespace in literals") {
        assertTrue(
          rejects(JobCondition.repositoryIs("org/repo'")),
          rejects(JobCondition.repositoryIs("org/repo with space")),
          rejects(JobCondition.hasPrLabel("a$b")),
        )
      },
      test("rejects unicode / emoji labels") {
        assertTrue(rejects(JobCondition.hasPrLabel("🚢")))
      },
      test("rejects invalid var names") {
        assertTrue(
          rejects(JobCondition.varNonEmpty("bad-name")),
          rejects(JobCondition.varNonEmpty("")),
        )
      },
      test("rejects empty All / Any") {
        assertTrue(
          rejects(JobCondition.and()),
          rejects(JobCondition.or()),
          rejects(JobCondition.All(Nil).render),
        )
      },
      test("accepts owner/repo and dotted labels") {
        assertTrue(
          JobCondition.repositoryIs("early-effect/zipx").render.contains("early-effect/zipx"),
          JobCondition.hasPrLabel("deploy.stg").render.contains("deploy.stg"),
        )
      },
      test("rejects overlong literals") {
        val long = "a" * 300
        assertTrue(rejects(JobCondition.refIs(long)))
      },

      // --- Property-based: exhaustive character set checks ---
      test("repositoryIs succeeds for strings of only allowed characters") {
        val validInputs = List(
          "org/repo",
          "my-org/my-repo_v2",
          "a/b+c-d:e.f_g@h:i",
          "A-Z_0-9.test/ok",
        )
        assertTrue(validInputs.map(s => scala.util.Try(JobCondition.repositoryIs(s)).isSuccess).forall(identity))
      },

      test("repositoryIs rejects any string containing a disallowed character") {
        // requireLiteral trims leading/trailing whitespace first, so embed chars in the middle.
        val disallowedChars =
          List("'", "\"", "$", " ", "\t", "\n", "(", ")", "{", "}", "|", "&", ";", "`", "!", "?", "#")
        assertTrue(
          disallowedChars.map(c => rejects(JobCondition.repositoryIs(s"org/repo${c}name"))).forall(identity)
        )
      },

      test("hasPrLabel succeeds for strings of only allowed characters") {
        val validLabels =
          List("deploy-stg", "release/v2", "ship:now", "a_b.c+d@e:f-g:h:i:j:k:l:m:n:o:p:q:r:s:t:u:v:w:x:y:z")
        assertTrue(validLabels.map(s => scala.util.Try(JobCondition.hasPrLabel(s)).isSuccess).forall(identity))
      },

      test("hasPrLabel rejects any string containing a disallowed character") {
        // requireLiteral trims leading/trailing whitespace first, so embed chars in the middle.
        val disallowedChars =
          List("'", "\"", "$", " ", "\t", "\n", "(", ")", "{", "}", "|", "&", ";", "`", "!", "?", "#")
        assertTrue(
          disallowedChars.map(c => rejects(JobCondition.hasPrLabel(s"label${c}name"))).forall(identity)
        )
      },

      test("refIs and refStartsWith accept valid GitHub ref patterns") {
        val validRefs = List(
          "refs/heads/main",
          "refs/tags/v1.0.0",
          "refs/pull/42/head",
          "feature/my-branch_v2",
        )
        assertTrue(
          validRefs.map(s => scala.util.Try(JobCondition.refIs(s)).isSuccess).forall(identity),
          validRefs.map(s => scala.util.Try(JobCondition.refStartsWith(s)).isSuccess).forall(identity),
        )
      },

      test("render is deterministic for complex conditions") {
        val cond = JobCondition.and(
          JobCondition.repositoryIs("org/repo"),
          JobCondition.or(
            JobCondition.onWorkflowDispatch,
            JobCondition.hasPrLabel("deploy-stg"),
          ),
        )
        val renders = List.fill(50)(cond.render)
        assertTrue(renders.distinct.size == 1)
      },
    ),
  )
end JobConditionSpec
