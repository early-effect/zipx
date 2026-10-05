package zipx.core

import scala.annotation.tailrec

/** The scope a POM gives a dependency. A consumer inherits compile and runtime dependencies, and nothing else. */
enum PomScope:
  case Compile, Runtime, Provided, Optional, Test, Tool

  def inherited: Boolean = this match
    case Compile | Runtime                 => true
    case Provided | Optional | Test | Tool => false

/** A dependency of one in-repo project on another, in the scope its POM records. */
final case class PomEdge(scope: PomScope, to: ResolvedModule)

/** A library a project declares, in the scope its POM records, at the revision it states. */
final case class PomDependency(scope: PomScope, module: ResolvedModule, revision: DepRevision)

/** What a published POM keeps its libraries from bringing: the modules the project states itself.
  *
  * A consumer resolves every POM it meets together. A library built against an older commit of a module this project
  * builds, or pins, names that commit, and nothing orders two commits for the consumer. Excluding the module from each
  * library leaves the consumer one revision: the one this project was built with. A release is left to the consumer's
  * own order, which is meaningful for releases.
  */
object PomExclusions:

  /** The in-repo modules `project` reaches through edges a consumer inherits, and the commit pins it declares in such a
    * scope. Never `project` itself.
    */
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
