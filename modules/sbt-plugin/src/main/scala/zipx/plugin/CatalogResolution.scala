package zipx.plugin

import coursier.version.Version
import lmcoursier.CoursierConfiguration
import sbt.librarymanagement.{CrossVersion, ModuleID, ScalaModuleInfo, UpdateReport}
import sbt.util.Logger
import zipx.core.*

/** The catalog's authority at resolution: the modules it forces, and the conflicts `update` refuses. */
private[plugin] object CatalogResolution:

  /** Coursier's own order, for revisions zipx has no line for. */
  given RevisionOrder = (a, b) => Version(a).compare(Version(b))

  /** Each catalog `Lib` row, named as a project with `scala` resolves it. A row aligned to the graph has no revision of
    * its own to force.
    */
  def forced(coords: Seq[ZipxCoord], scala: Option[ScalaModuleInfo]): List[(ResolvedModule, Lib)] =
    coords.toList
      .collect {
        case lib: Lib if !lib.isAligned =>
          val module = ZipxDeps.moduleID(lib)
          ResolvedModule(module.organization, PublishedModule.artifactId(module, scala)) -> lib
      }
      .distinctBy((module, _) => module)

  /** Already crossed: lm-coursier crosses an override with no platform, so a `%%` override in a Scala.js project would
    * force `x_3` and leave `x_sjs1_3` alone.
    */
  def overrides(forced: List[(ResolvedModule, Lib)]): Seq[ModuleID] =
    forced.map((module, lib) =>
      ModuleID(module.group, module.name, lib.version).withCrossVersion(CrossVersion.disabled)
    )

  def of(module: ModuleID): ResolvedModule = ResolvedModule(module.organization, module.name)

  /** What one project's resolution saw. `excluded` are the modules zipx keeps every library from bringing, so the
    * report has no edge from a library to one.
    */
  final case class Seen(report: UpdateReport, inRepo: Set[ResolvedModule], excluded: Set[ResolvedModule])

  /** Stale catalog rows and unpinned commits. In-repo modules are never judged. */
  def conflicts(
      seen: Seen,
      forced: List[(ResolvedModule, Lib)],
      conf: CoursierConfiguration,
      log: Logger,
  ): List[CatalogConflict] =
    val Seen(report, inRepo, _) = seen
    val rows                    = forced.toMap
    val stale                   = CatalogProbe.wanted(seen, rows.keySet, conf) match
      case Left(err) =>
        log.warn(s"zipx: ${err.message}")
        Nil
      case Right(wanted) =>
        wanted.toList.sortBy((module, _) => module.render).flatMap { (module, wants) =>
          rows.get(module).flatMap(CatalogConflict.stale(module, _, wants))
        }
    val unpinned = revisionsOf(report).toList.sortBy((module, _) => module.render).flatMap { (module, revisions) =>
      if rows.contains(module) || inRepo.contains(module) then None
      else CatalogConflict.unpinned(module, revisions.map(DepRevision.of))
    }
    stale ++ unpinned
  end conflicts

  /** Every revision each module was met at, selected or evicted, across configurations. Forced modules have no
    * evictions here; [[CatalogProbe]] reads theirs.
    */
  private def revisionsOf(report: UpdateReport): Map[ResolvedModule, List[String]] =
    report.configurations.toList
      .flatMap(_.details)
      .flatMap(_.modules)
      .groupMap(module => of(module.module))(_.module.revision)
      .view
      .mapValues(_.distinct)
      .toMap
end CatalogResolution
