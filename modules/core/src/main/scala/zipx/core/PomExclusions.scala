package zipx.core

import scala.annotation.tailrec

enum PomScope:
  case Compile, Runtime, Provided, Optional, Test, Tool

  def inherited: Boolean = this match
    case Compile | Runtime                 => true
    case Provided | Optional | Test | Tool => false

/** A dependency of one in-repo project on another. */
final case class PomEdge(scope: PomScope, to: ResolvedModule)

final case class PomDependency(scope: PomScope, module: ResolvedModule, revision: DepRevision)

/** Two projects in one build resolved as the same module. Keeping either edge list would under-exclude. */
final case class DuplicateModule(module: ResolvedModule):
  def message: String = s"${module.render} is more than one project"

/** The modules this project builds or commit-pins, excluded from each library its POM names. Nothing orders two commits
  * of one module for a consumer, so this leaves the one the project was built with. Release pins are left to the
  * consumer's own ordering.
  */
object PomExclusions:

  def graph(
      edges: List[(ResolvedModule, List[PomEdge])]
  ): Either[DuplicateModule, Map[ResolvedModule, List[PomEdge]]] =
    @tailrec
    def loop(
        rest: List[(ResolvedModule, List[PomEdge])],
        acc: Map[ResolvedModule, List[PomEdge]],
    ): Either[DuplicateModule, Map[ResolvedModule, List[PomEdge]]] =
      rest match
        case Nil                    => Right(acc)
        case (module, next) :: tail =>
          acc.get(module) match
            case Some(_) => Left(DuplicateModule(module))
            case None    => loop(tail, acc.updated(module, next))
    loop(edges, Map.empty)
  end graph

  def of(
      project: ResolvedModule,
      inRepo: Map[ResolvedModule, List[PomEdge]],
      declared: List[PomDependency],
  ): List[ResolvedModule] =
    val pinned = declared.collect {
      case PomDependency(scope, module, DepRevision.Commit(_)) if scope.inherited => module
    }
    (reached(project, inRepo) ++ pinned).filterNot(_ == project).distinct.sortBy(_.render)

  private def reached(project: ResolvedModule, inRepo: Map[ResolvedModule, List[PomEdge]]): List[ResolvedModule] =
    @tailrec
    def walk(frontier: List[ResolvedModule], seen: Set[ResolvedModule]): Set[ResolvedModule] =
      frontier match
        case Nil          => seen
        case next :: rest =>
          val fresh = inRepo
            .getOrElse(next, Nil)
            .collect {
              case PomEdge(scope, to) if scope.inherited && !seen.contains(to) => to
            }
            .distinct
          walk(fresh ++ rest, seen ++ fresh)
    walk(List(project), Set.empty).toList
  end reached
end PomExclusions
