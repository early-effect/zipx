package zipx.core

import zio.json.*

import scala.collection.immutable.ListMap

/** The `modules` input of `zipx-deploy.yml`. */
enum DeployModules:

  /** Per environment, what differs from its last successful deploy of each module. A module never deployed there is
    * included, and so is every module when the diff cannot run: an over-deploy costs minutes, an under-deploy ships a
    * stale service.
    */
  case Changed

  case All

  case Only(module: ModuleId)

  def wire: String = this match
    case Changed      => DeployModules.ChangedWire
    case All          => DeployModules.AllWire
    case Only(module) => module
end DeployModules

object DeployModules:

  val ChangedWire: String = "changed"
  val AllWire: String     = "all"

  def parse(raw: String, known: Set[ModuleId]): Either[String, DeployModules] =
    raw.trim match
      case ChangedWire => Right(Changed)
      case AllWire     => Right(All)
      case other       =>
        known
          .find(_ == other)
          .map(Only(_))
          .toRight(s"zipx: modules '$other' is not $ChangedWire, $AllWire, or a module this deploy can ship")
end DeployModules

/** A module's last successful deploy to one environment, as GitHub recorded it. */
final case class LastDeploy(environment: String, module: ModuleId, sha: GitSha)

/** The modules one target deploys, and the Environment that records them. */
final case class TargetModules(target: TargetName, environment: String, modules: List[ModuleId])

/** Everything a dispatch could ship: every image module, and every target with the modules it deploys. */
final case class DeployScope(imagesEnvironment: String, images: List[ModuleId], targets: List[TargetModules])

/** What one `zipx-deploy.yml` run ships at `sha`: the images it builds or finds, and each target's modules. */
final case class DeployPlan(sha: GitSha, images: List[ModuleId], targets: ListMap[TargetName, List[ModuleId]]):

  def imagesJson: String = images.map(id => id: String).toJson

  /** Targets with nothing to deploy are left out, so `'{}'` means there is no deploy job to run. */
  def targetsJson: String =
    val nonEmpty: Map[String, List[String]] =
      ListMap.from(targets.collect { case (t, ms) if ms.nonEmpty => (t: String) -> ms.map(id => id: String) })
    nonEmpty.toJson

object DeployPlan:

  /** @param changedSince
    *   the modules whose content differs between a base commit and `sha`, or `None` when the diff failed.
    */
  def resolve(
      scope: DeployScope,
      selected: Set[TargetName],
      modules: DeployModules,
      sha: GitSha,
      lastDeploys: List[LastDeploy],
      changedSince: GitSha => Option[Set[ModuleId]],
  ): DeployPlan =
    val last = lastDeploys.map(d => (d.environment, d.module) -> d.sha).toMap

    def pick(environment: String, candidates: List[ModuleId]): List[ModuleId] =
      modules match
        case DeployModules.All          => candidates
        case DeployModules.Only(module) => candidates.filter(_ == module)
        case DeployModules.Changed      =>
          candidates.filter { module =>
            last.get((environment, module)) match
              case None                      => true
              case Some(base) if base == sha => false
              case Some(base)                => changedSince(base).forall(_.contains(module))
          }

    val targets = ListMap.from(
      scope.targets
        .filter(t => selected.contains(t.target))
        .sortBy(t => t.target: String)
        .map(t => t.target -> pick(t.environment, t.modules).sorted)
    )
    // Tags are per commit, so every module a target deploys needs an image at `sha` whether or not its content moved.
    val deployed = targets.values.flatten.toSet
    val images   = (pick(scope.imagesEnvironment, scope.images) ++ scope.images.filter(deployed)).distinct.sorted
    DeployPlan(sha, images, targets)
  end resolve

end DeployPlan
