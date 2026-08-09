package zipx.core

/** Computes which modules a set of changed files affects, for affected-only CI.
  *
  * Algorithm (mirrors the well-worn sbt approach):
  *   1. If any changed file touches the build itself (a `.sbt` file or anything under the `project` dir), treat the
  *      whole build as affected: the graph or plugins may have changed, so nothing can be safely skipped.
  *   2. Otherwise map each changed file to its owning module(s) by **longest matching prefix** over each module's
  *      source paths and its base dir. Plural because a cross-built module's shared sources belong to every platform
  *      row.
  *   3. Take the reverse-dependency closure of those seed modules (a module plus everything that transitively depends
  *      on it). That is the affected set.
  *
  * Pure and unit-testable; the sbt plugin supplies the changed-file list from `git diff`.
  */
object Affected:

  /** The sentinel module list meaning "gate nothing, run every job".
    *
    * The generated job condition tests `contains(fromJson(...), 'all')` alongside the module id, so emitting this
    * disables affected-gating for the run. [[Planner.affectedScript]] emits it for events with no usable base ref (tag
    * pushes, `workflow_dispatch`, a branch's first push).
    */
  val AllSentinel: List[String] = List("all")

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
      val seeds = changedFiles.flatMap(owningModules(graph, _)).toSet
      graph.affectedClosure(seeds)

  /** The module ids the `affected` job should publish, given a diff that may have failed.
    *
    * `changedFiles` is `None` when the diff could not be computed at all: bad base ref, missing object, force-pushed
    * base, no git binary. Then this returns [[AllSentinel]]: fail **open** and verify everything.
    *
    * Returning an empty list there would fail **closed**, and silently: the generated condition
    * `contains(fromJson(needs.affected.outputs.modules), '<id>')` is false for every module, so every Verify job skips
    * and the PR reports green without having been tested. A broken base ref must cost CI minutes, not coverage.
    *
    * `Some(Nil)` is different and stays empty: that is a successful diff finding no changed files.
    */
  def outputModules(graph: ModuleGraph, changedFiles: Option[List[String]]): List[String] =
    changedFiles match
      case None        => AllSentinel
      case Some(files) => affectedModules(graph, files).toList.sorted

  /** Every module owning a file, by longest matching prefix over [[ModuleNode.ownedPaths]].
    *
    * A `Set` rather than an `Option` because a cross-built module's shared sources belong to *every* platform row:
    * `core/src/main/scala/Foo.scala` compiles into both `core` and `coreJS`, so picking one would leave half the module
    * untested behind a green check.
    *
    * Ranking by matched-prefix length is what keeps that precise: a nested project still beats its parent, and every
    * module tied at the winning length is returned, so `core/src/main/scalajs/` resolves to the JS row alone.
    *
    * Root (`baseDir` "") never matches, so a file under no module maps to nothing. An empty result is not "everything":
    * the fail-open sentinel lives in [[outputModules]].
    */
  def owningModules(graph: ModuleGraph, path: String): Set[String] =
    val ranked = graph.nodes.flatMap { node =>
      node.ownedPaths.filter(p => p.nonEmpty && underBase(path, p)).map(p => node.id -> p.length)
    }
    if ranked.isEmpty then Set.empty
    else
      // The longest match wins, and every module matching at that length wins together: `foo/shared/` prefixes the
      // shared source dir of each platform equally, so neither is more specific than the other.
      val best = ranked.map(_._2).max
      ranked.collect { case (id, len) if len == best => id }.toSet
  end owningModules

  private def underBase(path: String, base: String): Boolean =
    // Normalize so `app` and `app/` are equivalent; a trailing slash must not break exact-dir matches.
    val normalized = if base.endsWith("/") then base.dropRight(1) else base
    if normalized.isEmpty then false
    else
      val prefix = normalized + "/"
      path == normalized || path.startsWith(prefix)

end Affected
