package zipx.core

import zio.test.*

object DeployRequestSpec extends ZIOSpecDefault:

  private val stg = TargetName("stg")
  private val prd = TargetName("prd")

  private val stages = Map(stg -> DeployStage.PreProduction, prd -> DeployStage.Production)

  private val staged = DeployTrigger.staged(deployLabel = "deploy-stg", skipLabel = "no-deploy")
  private val manual = DeployTrigger.Manual()

  private val main   = "refs/heads/main"
  private val branch = "refs/heads/feature"

  private def choose(choice: String): Either[String, Set[TargetName]] = choice match
    case "stg"  => Right(Set(stg))
    case "prd"  => Right(Set(prd))
    case "both" => Right(Set(stg, prd))
    case other  => Left(s"no target $other")

  private def select(
      trigger: DeployTrigger,
      event: DeployEvent,
      stages: Map[TargetName, DeployStage] = stages,
  ): Either[String, DeploySelection] =
    DeployRequest.select(trigger, event, "main", stages, choose)

  private def targets(selected: TargetName*): Either[String, DeploySelection] =
    Right(DeploySelection.Targets(selected.toSet, DeployImages.Changed))

  private val genTarget: Gen[Any, TargetName] = Gen.alphaNumericStringBounded(1, 6).map(TargetName.unsafeMake)
  private val genStage: Gen[Any, DeployStage] = Gen.elements(DeployStage.values.toSeq*)
  private val genStages                       = Gen.mapOfBounded(0, 6)(genTarget, genStage)
  private val genLabels = Gen.setOfBounded(0, 3)(Gen.elements("deploy-stg", "no-deploy", "coverage", "docs"))
  private val genRef    = Gen.elements(main, branch, "refs/pull/7/merge", "refs/tags/v1.0.0")
  private val genAutomatic: Gen[Any, DeployEvent] =
    Gen.oneOf(genRef.zip(genLabels).map(DeployEvent.Merge(_, _)), genLabels.map(DeployEvent.PullRequest(_)))

  def spec = suite("DeployRequest")(
    suite("a dispatch")(
      test("picks the target or group it names, from the default branch") {
        assertTrue(
          select(staged, DeployEvent.Dispatch(main, Some("both"))) == targets(stg, prd),
          select(manual, DeployEvent.Dispatch(main, Some("prd"))) == targets(prd),
        )
      },
      test("refuses choose and a missing target, so the form's default deploys nothing") {
        assertTrue(
          select(manual, DeployEvent.Dispatch(main, Some("choose"))).left.exists(_.contains("pick a target")),
          select(manual, DeployEvent.Dispatch(main, None)).left.exists(_.contains("pick a target")),
        )
      },
      test("with no targets at all, only images, needs no target") {
        assertTrue(select(manual, DeployEvent.Dispatch(main, None), stages = Map.empty) == targets())
      },
      test("refuses a Production target from any other branch") {
        assertTrue(
          select(manual, DeployEvent.Dispatch(branch, Some("prd"))).left
            .exists(_.contains("prd deploy only from main (DeployStage.Production)")),
          select(manual, DeployEvent.Dispatch(branch, Some("both"))).isLeft,
        )
      },
      test("counts a target with no declared stage as Production") {
        assertTrue(select(manual, DeployEvent.Dispatch(branch, Some("stg")), stages = Map.empty).isLeft)
      },
      test("under Manual, may take a branch to a PreProduction target") {
        assertTrue(select(manual, DeployEvent.Dispatch(branch, Some("stg"))) == targets(stg))
      },
      test("under Staged, never runs from a branch: the PR label is the only way a branch deploys") {
        assertTrue(
          select(staged, DeployEvent.Dispatch(branch, Some("stg"))).left
            .exists(_.contains("deploys only from a PR labeled deploy-stg"))
        )
      },
    ),
    suite("a merge under Staged")(
      test("deploys its changes to every PreProduction target") {
        assertTrue(select(staged, DeployEvent.Merge(main, Set("docs"))) == targets(stg))
      },
      test("deploys nothing when its PR carries the skip label") {
        assertTrue(
          select(staged, DeployEvent.Merge(main, Set("no-deploy"))) ==
            Right(DeploySelection.Skip("the merged PR carries no-deploy"))
        )
      },
      test("deploys nothing for a push to another branch") {
        assertTrue(select(staged, DeployEvent.Merge(branch, Set.empty)).exists(_.isInstanceOf[DeploySelection.Skip]))
      },
    ),
    suite("a PR under Staged")(
      test("carrying the deploy label deploys to every PreProduction target, building only the images it ships") {
        assertTrue(
          select(staged, DeployEvent.PullRequest(Set("deploy-stg"))) ==
            Right(DeploySelection.Targets(Set(stg), DeployImages.ForTargets))
        )
      },
      test("without the label deploys nothing") {
        assertTrue(
          select(staged, DeployEvent.PullRequest(Set("coverage"))).exists(_.isInstanceOf[DeploySelection.Skip])
        )
      },
    ),
    test("Manual refuses a push or PR event, which its workflow never subscribes to") {
      assertTrue(
        select(manual, DeployEvent.Merge(main, Set.empty)).isLeft,
        select(manual, DeployEvent.PullRequest(Set("deploy-stg"))).isLeft,
      )
    },
    test("no merge or PR event ever selects a Production target") {
      check(genStages, genAutomatic) { (stages, event) =>
        val selected = select(staged, event, stages) match
          case Right(DeploySelection.Targets(ts, _)) => ts
          case _                                     => Set.empty
        assertTrue(selected.forall(t => stages.get(t).contains(DeployStage.PreProduction)))
      }
    },
    test("PR labels arrive as a JSON array") {
      assertTrue(
        DeployRequest.labelsFromJson("""["deploy-stg","docs"]""") == Right(Set("deploy-stg", "docs")),
        DeployRequest.labelsFromJson("[]") == Right(Set.empty),
        DeployRequest.labelsFromJson("deploy-stg").isLeft,
      )
    },
  )
end DeployRequestSpec
