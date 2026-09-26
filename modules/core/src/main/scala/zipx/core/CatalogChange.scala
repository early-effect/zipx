package zipx.core

/** One reading of a catalog file's diff, for affected gating. See [[CatalogEdit]]. */
enum CatalogChange:

  /** A `Lib` row, or a `.mod` copy of it, moved version. Affects the modules that declare it. */
  case LibMoved(coordinate: LibCoordinate)

  /** An `Action` pin moved. It changes generated workflows, which no module owns. */
  case ActionMoved(name: String)

  /** Anything else: sbt, Scala, or a plugin moved, a row came or went, or the file changed outside a version literal.
    * Every module is affected, as for any other build file.
    */
  case BuildWide(reason: String)
end CatalogChange

object CatalogChange:

  /** Adds, for each moved row, its `.mod` copies and the rows aligned to it: none of them has a version literal of its
    * own, so a source diff never names them.
    */
  def withFamilies(changes: List[CatalogChange], rows: Seq[Lib]): List[CatalogChange] =
    changes.flatMap {
      case moved @ LibMoved(c) =>
        moved :: rows
          .filter(l => l.group == c.group && (l.family.contains(c.artifact) || l.alignTo.contains(c.artifact)))
          .map(l => LibMoved(l.coordinate))
          .toList
      case other => List(other)
    }.distinct

  /** The modules `changes` seed, or `None` for build-wide.
    *
    * A moved library that no module declares is build-wide too: it is used somewhere the graph cannot see, such as the
    * meta-build or a plugin's own dependencies.
    */
  def seeds(changes: List[CatalogChange], graph: ModuleGraph): Option[Set[String]] =
    changes.foldLeft(Option(Set.empty[String])) {
      case (None, _)                         => None
      case (_, BuildWide(_))                 => None
      case (acc, ActionMoved(_))             => acc
      case (Some(acc), LibMoved(coordinate)) =>
        val users = graph.nodes.filter(_.libraries.contains(coordinate)).map(n => n.id: String)
        Option.when(users.nonEmpty)(acc ++ users)
    }
end CatalogChange

/** How a changed catalog file reads.
  *
  * @param path
  *   repo-root-relative, as `git diff --name-only` reports it.
  */
final case class CatalogEdit(path: String, changes: List[CatalogChange]):
  def reading(graph: ModuleGraph): BuildFileReading = BuildFileReading(path, CatalogChange.seeds(changes, graph))

/** What one changed build file affects, when zipx could read its diff.
  *
  * @param seeds
  *   the modules it affects directly, before the reverse closure, or `None` for every module.
  */
final case class BuildFileReading(path: String, seeds: Option[Set[String]])
