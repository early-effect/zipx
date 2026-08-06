package zipx.core

import zio.test.*

object JobConditionSpec extends ZIOSpecDefault:

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
        assertTrue(JobCondition.rawMake(expr).map(_.render).contains(expr))
      },
    ),
    suite("validation")(
      test("a bad literal written as a literal does not compile") {
        for
          empty <- typeCheck("""zipx.core.JobCondition.repositoryIs("")""")
          blank <- typeCheck("""zipx.core.JobCondition.hasPrLabel("  ")""")
          raw   <- typeCheck("""zipx.core.JobCondition.raw("   ")""")
          quote <- typeCheck("""zipx.core.JobCondition.repositoryIs("org/repo'")""")
          space <- typeCheck("""zipx.core.JobCondition.repositoryIs("org/repo with space")""")
          money <- typeCheck("""zipx.core.JobCondition.hasPrLabel("a$b")""")
          emoji <- typeCheck("""zipx.core.JobCondition.hasPrLabel("🚢")""")
          varn  <- typeCheck("""zipx.core.JobCondition.varNonEmpty("bad-name")""")
        yield assertTrue(
          empty.isLeft,
          blank.isLeft,
          raw.isLeft,
          quote.isLeft,
          space.isLeft,
          money.isLeft,
          emoji.isLeft,
          varn.isLeft,
        )
      },
      test("a bad literal read at runtime comes back as a Left, naming the reason") {
        assertTrue(
          JobCondition.repositoryIsMake("").isLeft,
          JobCondition.hasPrLabelMake("  ").isLeft,
          JobCondition.rawMake("   ").isLeft,
          JobCondition.repositoryIsMake("org/repo'").isLeft,
          JobCondition.varNonEmptyMake("bad-name").isLeft,
          JobCondition.varNonEmptyMake("").isLeft,
          JobCondition.hasPrLabelMake("a$b").left.exists(_.nonEmpty),
        )
      },
      test("an empty conjunction is not a value that exists") {
        for
          and <- typeCheck("""zipx.core.JobCondition.and()""")
          or  <- typeCheck("""zipx.core.JobCondition.or()""")
          all <- typeCheck("""zipx.core.JobCondition.All(Nil)""")
        yield assertTrue(
          and.isLeft,
          or.isLeft,
          all.isLeft,
          JobCondition.allOf(Nil).isEmpty,
          JobCondition.anyOf(Nil).isEmpty,
          JobCondition.allOf(List(JobCondition.onReleaseTag)).isDefined,
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
        assertTrue(JobCondition.refIsMake(long).isLeft)
      },
      test("repositoryIs succeeds for strings of only allowed characters") {
        val validInputs = List(
          "org/repo",
          "my-org/my-repo_v2",
          "a/b+c-d:e.f_g@h:i",
          "A-Z_0-9.test/ok",
        )
        assertTrue(validInputs.forall(s => JobCondition.repositoryIsMake(s).isRight))
      },

      test("repositoryIs rejects any string containing a disallowed character") {
        val disallowedChars =
          List("'", "\"", "$", " ", "\t", "\n", "(", ")", "{", "}", "|", "&", ";", "`", "!", "?", "#")
        assertTrue(disallowedChars.forall(c => JobCondition.repositoryIsMake(s"org/repo${c}name").isLeft))
      },

      test("hasPrLabel succeeds for strings of only allowed characters") {
        val validLabels =
          List("deploy-stg", "release/v2", "ship:now", "a_b.c+d@e:f-g:h:i:j:k:l:m:n:o:p:q:r:s:t:u:v:w:x:y:z")
        assertTrue(validLabels.forall(s => JobCondition.hasPrLabelMake(s).isRight))
      },

      test("hasPrLabel rejects any string containing a disallowed character") {
        val disallowedChars =
          List("'", "\"", "$", " ", "\t", "\n", "(", ")", "{", "}", "|", "&", ";", "`", "!", "?", "#")
        assertTrue(disallowedChars.forall(c => JobCondition.hasPrLabelMake(s"label${c}name").isLeft))
      },

      test("refIs and refStartsWith accept valid GitHub ref patterns") {
        val validRefs = List(
          "refs/heads/main",
          "refs/tags/v1.0.0",
          "refs/pull/42/head",
          "feature/my-branch_v2",
        )
        assertTrue(
          validRefs.forall(s => JobCondition.refIsMake(s).isRight),
          validRefs.forall(s => JobCondition.refStartsWithMake(s).isRight),
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
