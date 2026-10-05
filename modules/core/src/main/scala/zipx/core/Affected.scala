package zipx.core

/** Which modules a set of changed files affects, for affected-only CI.
  *
  *   1. A changed build file (`.sbt`, `project/`) affects everything, unless a [[BuildFileReading]] narrows it (see
  *      [[CatalogChange]] and `BuildSbtDiff`).
  *   2. Otherwise each file seeds its owning modules by longest matching prefix ([[owningModules]]).
  *   3. The affected set is the reverse-dependency closure of those seeds.
  */
object Affected:

  /** Gates nothing: the generated job condition tests `contains(fromJson(...), 'all')` beside the module id. Emitted
    * for events with no usable base ref (tag pushes, `workflow_dispatch`, a branch's first push).
    */
  val AllSentinel: List[String] = List("all")

  private def isBuildFile(path: String): Boolean =
    path.endsWith(".sbt") || path == "project" || path.startsWith("project/") || path.contains("/project/")

  /** `changedFiles` are repo-root-relative with forward slashes. */
  def affectedModules(
      graph: ModuleGraph,
      changedFiles: List[String],
      readings: List[BuildFileReading] = Nil,
  ): Set[String] =
    val byPath              = readings.map(r => r.path -> r.seeds).toMap
    val (readFiles, others) = changedFiles.partition(byPath.contains)
    val readSeeds           = readFiles.map(byPath)
    if others.exists(isBuildFile) || readSeeds.exists(_.isEmpty) then graph.ids.toSet
    else graph.affectedClosure(readSeeds.flatten.flatten.toSet ++ others.flatMap(owningModules(graph, _)))
  end affectedModules

  /** `None` means the diff failed (bad base ref, no git), which fails open to [[AllSentinel]]: an empty list would skip
    * every Verify job and report green untested. `Some(Nil)` is a successful empty diff and stays empty.
    */
  def outputModules(
      graph: ModuleGraph,
      changedFiles: Option[List[String]],
      readings: List[BuildFileReading] = Nil,
  ): List[String] =
    changedFiles match
      case None        => AllSentinel
      case Some(files) => affectedModules(graph, files, readings).toList.sorted

  /** A `Set` because a cross-built module's shared sources belong to every platform row. The longest prefix over
    * [[ModuleNode.ownedPaths]] wins and ties win together, so a nested project beats its parent while
    * `core/src/main/scalajs/` still resolves to the JS row alone.
    */
  def owningModules(graph: ModuleGraph, path: String): Set[String] =
    val ranked = graph.nodes.flatMap { node =>
      node.ownedPaths.filter(p => p.nonEmpty && underBase(path, p)).map(p => node.id -> p.length)
    }
    if ranked.isEmpty then Set.empty
    else
      val best = ranked.map(_._2).max
      ranked.collect { case (id, len) if len == best => id }.toSet
  end owningModules

  private def underBase(path: String, base: String): Boolean =
    val normalized = if base.endsWith("/") then base.dropRight(1) else base
    if normalized.isEmpty then false
    else
      val prefix = normalized + "/"
      path == normalized || path.startsWith(prefix)

end Affected
