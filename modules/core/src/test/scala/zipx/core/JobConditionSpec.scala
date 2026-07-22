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
          try
            JobCondition.repositoryIs(""); false
          catch case _: IllegalArgumentException => true, try
            JobCondition.hasPrLabel("  "); false
          catch case _: IllegalArgumentException => true, try
            JobCondition.raw("   "); false
          catch case _: IllegalArgumentException => true,
        )
      },
      test("rejects quotes, dollars, whitespace in literals") {
        assertTrue(
          try
            JobCondition.repositoryIs("org/repo'"); false
          catch case _: IllegalArgumentException => true, try
            JobCondition.repositoryIs("org/repo with space"); false
          catch case _: IllegalArgumentException => true, try
            JobCondition.hasPrLabel("a$b"); false
          catch case _: IllegalArgumentException => true,
        )
      },
      test("rejects unicode / emoji labels") {
        assertTrue(
          try
            JobCondition.hasPrLabel("🚢"); false
          catch case _: IllegalArgumentException => true
        )
      },
      test("rejects invalid var names") {
        assertTrue(
          try
            JobCondition.varNonEmpty("bad-name"); false
          catch case _: IllegalArgumentException => true, try
            JobCondition.varNonEmpty(""); false
          catch case _: IllegalArgumentException => true,
        )
      },
      test("rejects empty All / Any") {
        assertTrue(
          try
            JobCondition.and(); false
          catch case _: IllegalArgumentException => true, try
            JobCondition.or(); false
          catch case _: IllegalArgumentException => true, try
            JobCondition.All(Nil).render; false
          catch case _: IllegalArgumentException => true,
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
        assertTrue(
          try
            JobCondition.refIs(long); false
          catch case _: IllegalArgumentException => true
        )
      },
    ),
  )
end JobConditionSpec
