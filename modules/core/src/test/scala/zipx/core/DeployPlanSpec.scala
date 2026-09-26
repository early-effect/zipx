package zipx.core

import zio.test.*

import scala.collection.immutable.ListMap

object DeployPlanSpec extends ZIOSpecDefault:

  private def id(s: String): ModuleId = ModuleId.unsafeMake(s)
  private def sha(c: Char): GitSha    = GitSha.unsafeMake(c.toString * 40)

  private val svcA    = id("svcA")
  private val svcB    = id("svcB")
  private val workerA = id("workerA")
  private val workerB = id("workerB")
  private val stg     = TargetName("stg")
  private val prd     = TargetName("prd")

  private val scope = DeployScope(
    imagesEnvironment = "zipx-images",
    images = List(svcA, svcB, workerA, workerB),
    targets = List(
      TargetModules(prd, "lab-prd", List(workerA, workerB)),
      TargetModules(stg, "lab-stg", List(workerA, workerB)),
    ),
  )
  private val environments = "zipx-images" :: scope.targets.map(_.environment)
  private val head         = sha('f')

  private val genModules: Gen[Any, Set[ModuleId]]    = Gen.setOf(Gen.elements(svcA, svcB, workerA, workerB))
  private val genSelected: Gen[Any, Set[TargetName]] = Gen.setOf(Gen.elements(stg, prd))
  private val genLast: Gen[Any, List[LastDeploy]]    =
    Gen
      .listOf(
        for
          env    <- Gen.elements(environments*)
          module <- Gen.elements(svcA, svcB, workerA, workerB)
          at     <- Gen.elements(sha('a'), sha('b'), head)
        yield LastDeploy(env, module, at)
      )
      .map(_.distinctBy(d => (d.environment, d.module)))

  private def resolve(
      modules: DeployModules,
      selected: Set[TargetName],
      last: List[LastDeploy] = Nil,
      changed: GitSha => Option[Set[ModuleId]] = _ => Some(Set.empty),
  ): DeployPlan = DeployPlan.resolve(scope, selected, modules, head, last, changed)

  def spec = suite("DeployPlan")(
    suite("for any selection, deploy history and diff")(
      test("unselected targets never appear, and every deployed module has an image at this commit") {
        check(genSelected, genLast, genModules) { (selected, last, changed) =>
          val plan = resolve(DeployModules.Changed, selected, last, _ => Some(changed))
          assertTrue(
            plan.targets.keySet == selected,
            plan.targets.values.flatten.toSet.subsetOf(plan.images.toSet),
          )
        }
      },
      test("all ships every candidate, whatever was deployed before") {
        check(genSelected, genLast) { (selected, last) =>
          val plan = resolve(DeployModules.All, selected, last)
          assertTrue(
            plan.images == scope.images.sorted,
            plan.targets.values.forall(_ == List(workerA, workerB)),
          )
        }
      },
      test("one module narrows everything to that module") {
        check(Gen.elements(svcA, svcB, workerA, workerB), genSelected, genLast) { (only, selected, last) =>
          val plan = resolve(DeployModules.Only(only), selected, last)
          assertTrue((plan.images ++ plan.targets.values.flatten).forall(_ == only))
        }
      },
      test("changed never skips a module whose diff failed, or that was never deployed there") {
        check(genSelected, genLast) { (selected, last) =>
          val failed = resolve(DeployModules.Changed, selected, last.filter(_.sha != head), _ => None)
          val never  = resolve(DeployModules.Changed, selected, Nil)
          assertTrue(failed == never, never == resolve(DeployModules.All, selected))
        }
      },
    ),
    test("a module already deployed at this commit is not deployed again") {
      val last = environments.flatMap(env => scope.images.map(LastDeploy(env, _, head)))
      val plan = resolve(DeployModules.Changed, Set(stg, prd), last)
      assertTrue(plan.images.isEmpty, plan.targets == ListMap(prd -> Nil, stg -> Nil), plan.targetsJson == "{}")
    },
    test("L4: after one svcB merge, the second stg deploy ships svcB's image and no worker") {
      val first = sha('a')
      val last  =
        scope.images.map(LastDeploy("zipx-images", _, first)) ++ List(workerA, workerB).map(
          LastDeploy("lab-stg", _, first)
        )
      val plan = resolve(DeployModules.Changed, Set(stg), last, base => Option.when(base == first)(Set(svcB)))
      assertTrue(
        plan.images == List(svcB),
        plan.targets == ListMap(stg -> Nil),
        plan.imagesJson == """["svcB"]""",
        plan.targetsJson == "{}",
      )
    },
    test("a worker changed since stg's deploy needs its image even when the images record says it is current") {
      val last = List(LastDeploy("zipx-images", workerA, head), LastDeploy("lab-stg", workerA, sha('a')))
      val plan = resolve(DeployModules.Changed, Set(stg), last, _ => Some(Set(workerA)))
      assertTrue(plan.targets(stg).contains(workerA), plan.images.contains(workerA))
    },
    test("targets render as an object of non-empty lists, in target order") {
      val plan = resolve(DeployModules.All, Set(stg, prd))
      assertTrue(plan.targetsJson == """{"prd":["workerA","workerB"],"stg":["workerA","workerB"]}""")
    },
    suite("the modules input")(
      test("parses changed, all, and a known module, and rejects anything else") {
        val known = Set(svcA)
        assertTrue(
          DeployModules.parse("changed", known) == Right(DeployModules.Changed),
          DeployModules.parse(" all ", known) == Right(DeployModules.All),
          DeployModules.parse("svcA", known) == Right(DeployModules.Only(svcA)),
          DeployModules.parse("svcZ", known).left.exists(_.contains("'svcZ'")),
        )
      },
      test("wire round-trips through parse") {
        check(Gen.elements(DeployModules.Changed, DeployModules.All, DeployModules.Only(workerB))) { m =>
          assertTrue(DeployModules.parse(m.wire, Set(workerB)) == Right(m))
        }
      },
    ),
  )
end DeployPlanSpec
