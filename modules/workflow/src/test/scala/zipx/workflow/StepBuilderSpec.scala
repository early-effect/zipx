package zipx.workflow

import zio.test.*
import zipx.shell.*

import scala.collection.immutable.ListMap

object StepBuilderSpec extends ZIOSpecDefault:

  private val script: Script = Script.strict(Exec("sbt", Word.squote("test")))

  def spec = suite("StepBuilder")(
    suite("run")(
      test("a script becomes the run body, rendered exactly as the script renders") {
        val step = Step.run(script).named("Test").build
        assertTrue(
          step.run.contains("set -euo pipefail\nsbt 'test'"),
          step.run.contains(script.render),
          step.name.contains("Test"),
          step.uses.isEmpty,
        )
      },
      test("every field lands where it belongs") {
        val step = Step
          .run(script)
          .named("Test")
          .withId("test")
          .when(Expr.github("event_name"))
          .withEnv("TIER", Expr.env("TIER"))
          .in("modules/core")
          .build
        assertTrue(
          step.name.contains("Test"),
          step.id.contains("test"),
          step.`if`.contains("github.event_name"),
          step.env == ListMap("TIER" -> "${{ env.TIER }}"),
          step.workingDirectory.contains("modules/core"),
        )
      },
      test("a builder is a value: two steps can branch off one base") {
        val base = Step.run(script).named("Test")
        val a    = base.withId("test-a").build
        val b    = base.withId("test-b").build
        assertTrue(a.id.contains("test-a"), b.id.contains("test-b"), a.run == b.run)
      },
      test("env entries accumulate in call order") {
        val step = Step
          .run(script)
          .withEnv("FIRST", Expr.lit("1"))
          .withEnv("SECOND", Expr.lit("2"))
          .withEnvs(ListMap("THIRD" -> "3"))
          .build
        assertTrue(step.env.keys.toList == List("FIRST", "SECOND", "THIRD"))
      },
      test("a raw script is carried forward as a fragment so generate can warn") {
        val raw = Script.raw("echo hand written").toOption.get
        assertTrue(
          Step.run(script).rawFragments.isEmpty,
          Step.run(raw).rawFragments == List("echo hand written"),
          Step.runRaw("echo verbatim").rawFragments == List("echo verbatim"),
          Step.runRaw("echo verbatim").build.run.contains("echo verbatim"),
        )
      },
      test("a run step cannot take with:, because GitHub would ignore it") {
        for run <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).withInput("path", "~/.sbt")""")
        yield assertTrue(run.isLeft)
      },
    ),
    suite("uses")(
      test("an action ref becomes the uses value") {
        val step = Step.uses("actions/checkout@v4").withInput("fetch-depth", "0").build
        assertTrue(
          step.uses.contains("actions/checkout@v4"),
          step.`with` == ListMap("fetch-depth" -> "0"),
          step.run.isEmpty,
        )
      },
      test("with: takes an Expr as well as a literal, so no caller hand-writes ${{ }}") {
        val step = Step
          .uses("aws-actions/configure-aws-credentials@v6")
          .withInput("role-to-assume", Expr.env("DEPLOY_ROLE"))
          .withInput("aws-region", "us-west-2")
          .build
        assertTrue(
          step.`with` == ListMap(
            "role-to-assume" -> "${{ env.DEPLOY_ROLE }}",
            "aws-region"     -> "us-west-2",
          )
        )
      },
      test("an unpinned or malformed literal ref does not compile") {
        for
          pinned   <- typeCheck("""zipx.workflow.Step.uses("actions/checkout@v4")""")
          unpinned <- typeCheck("""zipx.workflow.Step.uses("actions/checkout")""")
          bare     <- typeCheck("""zipx.workflow.Step.uses("checkout")""")
          empty    <- typeCheck("""zipx.workflow.Step.uses("")""")
        yield assertTrue(
          pinned.isRight,
          unpinned.isLeft,
          bare.isLeft,
          empty.isLeft,
          unpinned.left.exists(_.contains("@ref")),
        )
      },
      test("a runtime ref goes through usesMake, which reports the reason as a value") {
        val pin = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
        assertTrue(
          Step.usesMake(pin).map(_.build.uses).contains(Some(pin)),
          Step.usesMake("actions/checkout").isLeft,
          Step.usesMake("").isLeft,
          Step.usesMake("actions/checkout").left.exists(_.contains("@ref")),
          Step.usesMake(pin).map(_.named("Checkout").build.name).contains(Some("Checkout")),
        )
      },
    ),
    suite("Step.problem")(
      test("no problem with the two valid shapes") {
        assertTrue(
          Step.problem(Step(run = Some("echo hi"))).isEmpty,
          Step.problem(Step(uses = Some("actions/checkout@v4"))).isEmpty,
          Step.problem(Step(uses = Some("a/b@v1"), `with` = ListMap("k" -> "v"))).isEmpty,
          Step.validate(Step(run = Some("echo hi"))).isRight,
        )
      },
      test("reports a step with both uses and run") {
        val both = Step(name = Some("Confused"), uses = Some("actions/checkout@v4"), run = Some("echo hi"))
        assertTrue(
          Step.problem(both).exists(_.contains("both uses and run")),
          Step.validate(both).isLeft,
        )
      },
      test("reports a step with neither, including a bare Step()") {
        assertTrue(
          Step.problem(Step()).exists(_.contains("neither uses nor run")),
          Step.problem(Step(name = Some("Does nothing"))).isDefined,
        )
      },
      test("reports with: on a run step and names the ignored keys") {
        val step = Step(run = Some("echo hi"), `with` = ListMap("path" -> "~/.sbt", "key" -> "k"))
        assertTrue(
          Step.problem(step).exists(_.contains("key, path"))
        )
      },
      test("the message names the step, by name or by id, so a long workflow is diagnosable") {
        assertTrue(
          Step.problem(Step(name = Some("Import signing key"))).exists(_.contains("'Import signing key'")),
          Step.problem(Step(id = Some("check"))).exists(_.contains("'check'")),
          Step.problem(Step()).exists(!_.contains("''")),
        )
      },
    ),
    suite("render-time enforcement")(
      test("an invalid step is a Left when rendered as a step sequence") {
        assertTrue(Render.renderSteps(List(Step(run = Some("ok")), Step())).isLeft)
      },
      test("an invalid step is a Left when rendered inside a job") {
        val job = Job(steps = List(Step(uses = Some("a/b@v1"), run = Some("echo hi"))))
        assertTrue(
          Render.renderJob("build", job).isLeft,
          Render.renderJobs(ListMap("build" -> job)).isLeft,
        )
      },
      test("an invalid step is a Left when rendered as part of a whole workflow") {
        val wf = Workflow(
          name = "CI",
          on = Triggers(push = Some(BranchFilter(branches = List("main")))),
          jobs = ListMap("build" -> Job(steps = List(Step()))),
        )
        assertTrue(Render.render(wf).isLeft, Render.renderBody(wf).isLeft)
      },
      test("a job with no steps at all still renders: that is a reusable-workflow call") {
        val wf = Workflow(
          name = "CI",
          on = Triggers(push = Some(BranchFilter(branches = List("main")))),
          jobs = ListMap("call" -> Job(runsOn = Nil, uses = Some("org/repo/.github/workflows/w.yml@main"))),
        )
        assertTrue(Render.render(wf).exists(_.contains("uses: org/repo/.github/workflows/w.yml@main")))
      },
      test("a builder-made step round-trips through render unchanged") {
        val step = Step.run(script).named("Test").withId("test").build
        val yaml = Render.renderSteps(List(step)).toOption.get
        assertTrue(
          yaml.contains("name: Test"),
          yaml.contains("id: test"),
          yaml.contains("run: |"),
          yaml.contains("sbt 'test'"),
        )
      },
    ),
    suite("compile-time checks")(
      test("an invalid literal id does not compile, and the message is the validator's own") {
        for
          good   <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).withId("check-run")""")
          digit  <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).withId("1check")""")
          dotted <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).withId("check.run")""")
          empty  <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).withId("")""")
        yield assertTrue(
          good.isRight,
          digit.isLeft,
          dotted.isLeft,
          empty.isLeft,
          digit.left.exists(_.contains("invalid step id")),
        )
      },
      test("an invalid literal env name does not compile") {
        for
          good     <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).withEnv("TIER", Expr.lit("a"))""")
          dashed   <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).withEnv("MY-VAR", Expr.lit("a"))""")
          reserved <- typeCheck(
            """zipx.workflow.Step.run(zipx.shell.Script.empty).withEnv("GITHUB_SHA", Expr.lit("a"))"""
          )
        yield assertTrue(good.isRight, dashed.isLeft, reserved.isLeft, reserved.left.exists(_.contains("reserved")))
      },
      test("a runtime id is pushed to withStepId, which takes a validated value") {
        for
          variable <- typeCheck("""val i = "check"; zipx.workflow.Step.run(zipx.shell.Script.empty).withId(i)""")
          viaMake  <- typeCheck(
            """val i = "check"
               zipx.workflow.Step.run(zipx.shell.Script.empty).withStepId(StepId.makeOrThrow(i))"""
          )
        yield assertTrue(variable.isLeft, viaMake.isRight)
      },
      test("with: values are Expr or String, never something that stringifies to garbage") {
        for
          expr    <- typeCheck("""zipx.workflow.Step.uses("a/b@v1").withInput("k", Expr.lit("v"))""")
          string  <- typeCheck("""zipx.workflow.Step.uses("a/b@v1").withInput("k", "v")""")
          boolean <- typeCheck("""zipx.workflow.Step.uses("a/b@v1").withInput("k", true)""")
        yield assertTrue(expr.isRight, string.isRight, boolean.isLeft)
      },
      test("when takes an Expr, not a hand-written condition string") {
        for
          typed  <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).when(Expr.github("event_name"))""")
          string <- typeCheck("""zipx.workflow.Step.run(zipx.shell.Script.empty).when("${{ github.event_name }}")""")
        yield assertTrue(typed.isRight, string.isLeft)
      },
    ),
  )
end StepBuilderSpec
