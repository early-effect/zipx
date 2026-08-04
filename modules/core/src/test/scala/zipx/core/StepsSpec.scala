package zipx.core

import zio.test.*
import zipx.shell.*
import zipx.workflow.{Expr, Step}

import scala.collection.immutable.ListMap

/** [[Steps]]: the composable step bundle, and the property that makes it adoptable without breaking a consumer.
  *
  * The design claim under test is that `Steps` *is* a `StepContext => List[Step]`, so every field that took a lambda
  * accepts one with no signature change. That claim is only worth as much as the compile-time evidence for it, which is
  * why the assignment tests here name the declared lambda types explicitly rather than relying on inference.
  */
object StepsSpec extends ZIOSpecDefault:

  private val node = ModuleNode(id = "core", publishes = true)
  private val ctx  = StepContext(node, None, matrixed = false)

  private def ctxFor(id: String, target: Option[Target] = None): StepContext =
    StepContext(ModuleNode(id = id), target, matrixed = false)

  private def named(name: String): Step = Step(name = Some(name), run = Some(s"echo $name"))

  private val first  = Steps.of("first")(named("one"))
  private val second = Steps.of("second")(named("two"))

  def spec = suite("Steps")(
    suite("is a function, which is what keeps it non-breaking")(
      test("a Steps satisfies every field that declares a lambda") {
        // No `.build`, no adapter, no signature change: the whole migration story in four assignments.
        val capability: Capability                      = Capability.test.copy(extraSteps = first, postSteps = second)
        val config: PlanConfig                          = PlanConfig(cacheRehydrateExtraSteps = first)
        val asLambda: StepContext => List[Step]         = first
        val composedAsLambda: StepContext => List[Step] = first ++ second
        assertTrue(
          capability.extraSteps(ctx) == List(named("one")),
          capability.postSteps(ctx) == List(named("two")),
          config.cacheRehydrateExtraSteps(ctx) == List(named("one")),
          asLambda(ctx) == List(named("one")),
          composedAsLambda(ctx).length == 2,
        )
      },
      test("apply and build agree, so calling it either way is the same") {
        assertTrue(first(ctx) == first.build(ctx))
      },
      test("the planner needs no edit: a bundle reaches the rendered job") {
        val capability =
          Capability.once("check", "scalafmtCheckAll").copy(extraSteps = Steps.of("marker")(named("mark")))
        val plan = Planner.plan(ModuleGraph(List(node)), List(capability), PlanConfig())
        assertTrue(plan.jobs("check").steps.exists(_.name.contains("mark")))
      },
    ),
    suite("composition")(
      test("++ concatenates in order and joins names") {
        val both = first ++ second
        assertTrue(
          both(ctx) == List(named("one"), named("two")),
          both.name == "first+second",
          (second ++ first)(ctx) == List(named("two"), named("one")),
        )
      },
      test("++ is associative, which is what makes a shared prefix reusable") {
        val third = Steps.of("third")(named("three"))
        assertTrue(
          ((first ++ second) ++ third)(ctx) == (first ++ (second ++ third))(ctx),
          ((first ++ second) ++ third).name == (first ++ (second ++ third)).name,
        )
      },
      test("empty is the identity, both sides") {
        assertTrue(
          (Steps.empty ++ first)(ctx) == first(ctx),
          (first ++ Steps.empty)(ctx) == first(ctx),
          Steps.empty(ctx).isEmpty,
        )
      },
      test("all folds a list of bundles, and an empty list is empty") {
        assertTrue(
          Steps.all(first, second)(ctx) == List(named("one"), named("two")),
          Steps.all()(ctx).isEmpty,
        )
      },
      test("++ accepts a bare lambda, so a half-migrated build still composes") {
        val legacy: StepContext => List[Step] = _ => List(named("legacy"))
        val mixed                             = first ++ legacy
        assertTrue(mixed(ctx) == List(named("one"), named("legacy")), mixed.name == "first")
      },
      test("named overrides a joined name") {
        assertTrue((first ++ second).named("release-prep").name == "release-prep")
      },
      test("mapSteps applies a cross-cutting tweak to every step") {
        val tweaked = (first ++ second).mapSteps(s => s.copy(workingDirectory = Some("modules/core")))
        assertTrue(tweaked(ctx).forall(_.workingDirectory.contains("modules/core")), tweaked(ctx).length == 2)
      },
    ),
    suite("when")(
      test("gates every step in the bundle") {
        val gated = (first ++ second).when(JobCondition.onReleaseTag)
        assertTrue(
          gated(ctx).forall(_.`if`.contains("startsWith(github.ref, 'refs/tags/v')")),
          gated(ctx).length == 2,
        )
      },
      test("renders the condition bare, because an if: is already an expression context") {
        // Wrapped would be `${{ a }} && ${{ b }}`, a template string that evaluates to neither operand.
        val gated = first.when(JobCondition.eventIs("push"))
        assertTrue(
          gated(ctx).head.`if`.contains("github.event_name == 'push'"),
          !gated(ctx).head.`if`.exists(_.contains("${{")),
        )
      },
      test("ANDs onto an existing if: rather than replacing it") {
        val preGated = Steps.of("pre")(Step(run = Some("echo hi"), `if` = Some("github.event_name == 'push'")))
        val both     = preGated.when(JobCondition.onReleaseTag).apply(ctx).head.`if`.get
        assertTrue(
          both.contains("github.event_name == 'push'"),
          both.contains("startsWith(github.ref, 'refs/tags/v')"),
          both == "(github.event_name == 'push') && (startsWith(github.ref, 'refs/tags/v'))",
        )
      },
      test("stacks: two whens are both applied") {
        val twice = first.when(JobCondition.eventIs("push")).when(JobCondition.onReleaseTag)
        val cond  = twice(ctx).head.`if`.get
        assertTrue(cond.contains("github.event_name == 'push'"), cond.contains("refs/tags/v"))
      },
      test("gating an empty bundle is a no-op rather than an error") {
        assertTrue(Steps.empty.when(JobCondition.onReleaseTag).apply(ctx).isEmpty)
      },
      test("the builder extension takes a JobCondition, not just an Expr") {
        val step = Step.run(Script.strict(Exec("sbt", Word.squote("test")))).when(JobCondition.onReleaseTag).build
        assertTrue(step.`if`.contains("startsWith(github.ref, 'refs/tags/v')"))
      },
    ),
    suite("StepContext threading")(
      test("the context reaches the bundle, so a step can name the module") {
        val perModule = Steps.one("upload")(c => Step(name = Some(s"upload-${c.node.id}"), run = Some("true")))
        assertTrue(
          perModule(ctxFor("core")).head.name.contains("upload-core"),
          perModule(ctxFor("docs")).head.name.contains("upload-docs"),
        )
      },
      test("both halves of a composition see the same context") {
        val a     = Steps.one("a")(c => Step(name = Some(s"a-${c.node.id}"), run = Some("true")))
        val b     = Steps.one("b")(c => Step(name = Some(s"b-${c.node.id}"), run = Some("true")))
        val names = (a ++ b)(ctxFor("shell")).flatMap(_.name)
        assertTrue(names == List("a-shell", "b-shell"))
      },
      test("the target and action pins are visible too") {
        val bundle = Steps.one("deploy") { c =>
          Step(name = Some(c.target.fold("no-target")(_.name)), uses = Some(c.actions.checkout))
        }
        assertTrue(
          bundle(ctxFor("core")).head.name.contains("no-target"),
          bundle(ctxFor("core", Some(Target("staging")))).head.name.contains("staging"),
          bundle(ctxFor("core")).head.uses.contains(ActionPins.Defaults.checkout),
        )
      },
      test("a bundle is re-evaluated per context, not memoized on first call") {
        val bundle = Steps.one("id")(c => Step(name = Some(c.node.id), run = Some("true")))
        val first  = bundle(ctxFor("one")).head.name
        val second = bundle(ctxFor("two")).head.name
        assertTrue(first.contains("one"), second.contains("two"))
      },
    ),
    suite("constructors")(
      test("of takes finished steps, one takes a single context-dependent step") {
        assertTrue(
          Steps.of("two")(named("a"), named("b"))(ctx).length == 2,
          Steps.of("none")()(ctx).isEmpty,
          Steps.one("single")(_ => named("a"))(ctx).length == 1,
        )
      },
      test("built takes builders, so a definition site never calls .build") {
        val bundle = Steps.built("verify")(
          Step.uses(ActionPins.Defaults.checkout),
          Step.run(Script.strict(Exec("sbt", Word.squote("test")))).named("Test"),
        )
        assertTrue(
          bundle(ctx).length == 2,
          bundle(ctx).head.uses.contains(ActionPins.Defaults.checkout),
          bundle(ctx)(1).name.contains("Test"),
        )
      },
      test("built validates: an invalid builder fails at the definition site") {
        assertTrue(
          try
            val _ = Steps.built("bad")(Step.uses("actions/checkout")) // unpinned
            false
          catch case _: IllegalArgumentException => true
        )
      },
      test("buildingWith is built's context-dependent sibling") {
        // The module id is runtime data, so the command word goes through `squoteMake` rather than the inline
        // constructor. That fork is the point of the design, and it shows up the moment a bundle reads its context.
        val bundle = Steps.buildingWith("per-module") { c =>
          val task = Word.squoteMake(s"${c.node.id}/test").fold(e => throw IllegalArgumentException(e), identity)
          List(Step.run(Script.strict(Exec("sbt", task))).named(s"Test ${c.node.id}"))
        }
        assertTrue(
          bundle(ctxFor("shell")).head.name.contains("Test shell"),
          bundle(ctxFor("shell")).head.run.contains("set -euo pipefail\nsbt 'shell/test'"),
        )
      },
      test("the curried apply and the case-class apply agree") {
        val curried    = Steps("x")(_ => List(named("a")))
        val positional = Steps("x", _ => List(named("a")))
        assertTrue(curried(ctx) == positional(ctx), curried.name == positional.name)
      },
    ),
    suite("raw fragments and the generate-time warning")(
      test("built collects the builders' fragments, so escape-hatch use is not silent") {
        val bundle = Steps.built("legacy")(Step.runRaw("echo hand written"))
        assertTrue(bundle.rawFragments == List("echo hand written"))
      },
      test("a typed script reports nothing") {
        assertTrue(Steps.built("typed")(Step.run(Script.strict(Exec("sbt", Word.squote("test"))))).rawFragments.isEmpty)
      },
      test("fragments survive composition, from either side") {
        val raw   = Steps.built("legacy")(Step.runRaw("echo raw"))
        val clean = Steps.of("clean")(named("a"))
        assertTrue(
          (clean ++ raw).rawFragments == List("echo raw"),
          (raw ++ clean).rawFragments == List("echo raw"),
          (raw ++ raw).rawFragments.length == 2,
        )
      },
      test("when / named / mapSteps preserve fragments: gating does not launder a warning away") {
        val raw = Steps.built("legacy")(Step.runRaw("echo raw"))
        assertTrue(
          raw.when(JobCondition.onReleaseTag).rawFragments == List("echo raw"),
          raw.named("other").rawFragments == List("echo raw"),
          raw.mapSteps(identity).rawFragments == List("echo raw"),
        )
      },
      test("withRawFragments declares them for a hand-built step") {
        assertTrue(Steps.of("manual")(named("a")).withRawFragments(List("echo x")).rawFragments == List("echo x"))
      },
      test("rawWarnings finds fragments on extraSteps, postSteps and the rehydrate field, and names the bundle") {
        val raw        = Steps.built("legacy")(Step.runRaw("echo raw"))
        val capability = Capability.test.copy(extraSteps = raw)
        val post       = Capability.publish.copy(postSteps = Steps.built("post")(Step.runRaw("echo post")))
        val config     = PlanConfig(cacheRehydrateExtraSteps = Steps.built("rehydrate")(Step.runRaw("echo rehy")))
        val warnings   = Steps.rawWarnings(List(capability, post), config)
        assertTrue(
          warnings.length == 3,
          warnings.exists(w => w.contains("'legacy'") && w.contains("echo raw")),
          warnings.exists(w => w.contains("'post'") && w.contains("echo post")),
          warnings.exists(w => w.contains("'rehydrate'") && w.contains("echo rehy")),
        )
      },
      test("a clean plan warns about nothing") {
        assertTrue(Steps.rawWarnings(List(Capability.test, Capability.publish), PlanConfig()).isEmpty)
      },
      test("a bare lambda reports nothing, which is the incentive to use Steps") {
        // Not a gap being papered over: a lambda has nowhere to carry the information, so the type is the only place it
        // can live. Worth pinning, because a reader could otherwise expect this to be caught.
        val legacy: StepContext => List[Step] = _ => List(Step(run = Some("echo raw")))
        assertTrue(Steps.rawWarnings(List(Capability.test.copy(extraSteps = legacy)), PlanConfig()).isEmpty)
      },
      test("the same bundle used twice warns once") {
        val raw = Steps.built("legacy")(Step.runRaw("echo raw"))
        val cap = Capability.test.copy(extraSteps = raw, postSteps = raw)
        assertTrue(Steps.rawWarnings(List(cap), PlanConfig()).length == 1)
      },
    ),
    suite("interaction with Expr and env")(
      test("a bundle's steps can carry typed env and with: values") {
        val bundle = Steps.built("aws")(
          Step
            .uses("aws-actions/configure-aws-credentials@v6")
            .withInput("role-to-assume", Expr.env("DEPLOY_ROLE"))
            .withEnv("TIER", Expr.lit("staging"))
        )
        assertTrue(
          bundle(ctx).head.`with` == ListMap("role-to-assume" -> "${{ env.DEPLOY_ROLE }}"),
          bundle(ctx).head.env == ListMap("TIER" -> "staging"),
        )
      },
      test("a bundle renders through Render like any other step list") {
        val yaml = zipx.workflow.Render.renderSteps(first(ctx))
        assertTrue(yaml.contains("name: one"), yaml.contains("run: echo one"))
      },
    ),
  )
end StepsSpec
