package zipx.core

/** Computes which modules a set of changed files affects, for affected-only CI.
  *
  * Algorithm (mirrors the well-worn sbt approach):
  *   1. If any changed file touches the build itself (a `.sbt` file or anything under the `project` dir), treat the
  *      whole build as affected — the graph or plugins may have changed, so nothing can be safely skipped.
  *   2. Otherwise map each changed file to its owning module by **longest matching base-dir prefix**.
  *   3. Take the reverse-dependency closure of those seed modules (a module plus everything that transitively depends on
  *      it) — that is the affected set.
  *
  * Pure and unit-testable; the sbt plugin supplies the changed-file list from `git diff`.
  */
object Affected:

  /** Paths that force a full build when changed: build definition and meta-build. */
  private def isBuildFile(path: String): Boolean =
    path.endsWith(".sbt") || path == "project" || path.startsWith("project/") || path.contains("/project/")

  /** The set of module ids affected by `changedFiles` (repo-root-relative, forward slashes).
    *
    * Returns all module ids when a build file changed. Files under no module are ignored (unless a build file). Seeds
    * are expanded via the reverse-dependency closure.
    */
  def affectedModules(graph: ModuleGraph, changedFiles: List[String]): Set[String] =
    if changedFiles.exists(isBuildFile) then graph.ids.toSet
    else
      val seeds = changedFiles.flatMap(owningModule(graph, _)).toSet
      graph.affectedClosure(seeds)

  /** The module owning a file, by longest base-dir prefix. Root (baseDir "") only matches files not owned by any deeper
    * module, and even then only if root is a real module — but since root is typically an excluded aggregator, such
    * files effectively map to nothing. Returns None when no module's base dir prefixes the path.
    */
  def owningModule(graph: ModuleGraph, path: String): Option[String] =
    val candidates =
      graph.nodes
        .filter(n => n.baseDir.nonEmpty && underBase(path, n.baseDir))
        .sortBy(-_.baseDir.length) // longest (most specific) base dir first
    candidates.headOption.map(_.id)

  private def underBase(path: String, base: String): Boolean =
    val b = if base.endsWith("/") then base else base + "/"
    path == base || path.startsWith(b)

end Affected
