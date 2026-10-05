package zipx.core

import neotype.unwrap
import zio.json.*

/** What started a `zipx-deploy.yml` run, as `resolve` reads it. Refs are GitHub's full form, `refs/heads/main`. */
enum DeployEvent:

  /** Actions → Run workflow, from `ref`, with the `target` input when the form has one. */
  case Dispatch(ref: String, target: Option[String])

  /** A push to `ref`. `prLabels` are the labels of the PR it merged, empty for a direct push. */
  case Merge(ref: String, prLabels: Set[String])

  case PullRequest(labels: Set[String])
end DeployEvent

/** Which images a run builds: every image whose module changed, or only those its deploy jobs ship. A labeled PR builds
  * only the second, since nothing else it could push is ever deployed from a branch.
  */
enum DeployImages:
  case Changed
  case ForTargets

/** What a run may deploy, before [[DeployPlan.resolve]] narrows it to what changed. */
enum DeploySelection:
  case Targets(selected: Set[TargetName], images: DeployImages)

  /** Nothing, for a reason the resolve job logs. Not a failure: a `no-deploy` merge is supposed to deploy nothing. */
  case Skip(reason: String)

object DeployRequest:

  /** The dispatch form's first `target` option, so a click or a bare `gh workflow run` deploys nothing. */
  val ChooseWire: String = "choose"

  /** The rules that keep a branch off every target without its label, and anything but the default branch off a
    * [[DeployStage.Production]] target. A target missing from `stages` counts as `Production`.
    *
    * @param choose
    *   the targets a dispatch `target` choice names, see [[DeployWorkflow.selectedTargets]].
    */
  def select(
      trigger: DeployTrigger,
      event: DeployEvent,
      defaultBranch: String,
      stages: Map[TargetName, DeployStage],
      choose: String => Either[String, Set[TargetName]],
  ): Either[String, DeploySelection] =
    val defaultRef    = s"refs/heads/$defaultBranch"
    val preProduction = stages.collect { case (t, DeployStage.PreProduction) => t }.toSet
    def production(targets: Set[TargetName]): List[TargetName] =
      targets.filterNot(preProduction.contains).toList.sortBy(t => t: String)
    (trigger, event) match
      case (DeployTrigger.OnMerge, _) =>
        Left("zipx: zipx-deploy.yml runs under DeployTrigger.Manual() or DeployTrigger.Staged")
      case (_, DeployEvent.Dispatch(ref, target)) =>
        for
          selected <- target match
            case None if stages.isEmpty  => Right(Set.empty[TargetName])
            case None | Some(ChooseWire) =>
              Left(s"zipx: pick a target; the form's first option, $ChooseWire, deploys nothing")
            case Some(choice) => choose(choice)
          _ <- trigger match
            case DeployTrigger.Staged(label, _, _) if ref != defaultRef =>
              Left(s"zipx: dispatch zipx-deploy.yml from $defaultBranch; $ref deploys only from a PR labeled $label")
            case _ => Right(())
          _ <- production(selected) match
            case Nil                    => Right(())
            case _ if ref == defaultRef => Right(())
            case targets                =>
              Left(
                s"zipx: ${targets.mkString(", ")} deploy only from $defaultBranch (DeployStage.Production); " +
                  s"this dispatch ran from $ref"
              )
        yield DeploySelection.Targets(selected, DeployImages.Changed)
      case (DeployTrigger.Staged(_, skip, _), DeployEvent.Merge(ref, prLabels)) =>
        Right(
          if ref != defaultRef then DeploySelection.Skip(s"a push to $ref deploys nothing; merges to $defaultBranch do")
          else if prLabels.contains(skip.unwrap) then DeploySelection.Skip(s"the merged PR carries $skip")
          else DeploySelection.Targets(preProduction, DeployImages.Changed)
        )
      case (DeployTrigger.Staged(label, _, _), DeployEvent.PullRequest(labels)) =>
        Right(
          if labels.contains(label.unwrap) then DeploySelection.Targets(preProduction, DeployImages.ForTargets)
          else DeploySelection.Skip(s"the PR does not carry $label")
        )
      case (DeployTrigger.Manual(_), _) =>
        Left("zipx: DeployTrigger.Manual deploys only from a dispatch; a push or PR event never runs zipx-deploy.yml")
    end match
  end select

  /** The PR's labels, as the resolve job receives them from `toJSON(github.event.pull_request.labels.*.name)`. */
  def labelsFromJson(json: String): Either[String, Set[String]] =
    json.fromJson[List[String]].left.map(e => s"zipx: PR labels are not a JSON array of names: $e").map(_.toSet)

end DeployRequest
