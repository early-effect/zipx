package zipx.core

/** A build module as zipx sees it — the sbt-agnostic projection of an sbt project.
  *
  * The sbt plugin ([[zipx.sbt]]) builds these from `Project.extract(state).structure`; the pure core plans over them so
  * the whole planner is unit-testable without sbt on the classpath.
  *
  * @param id
  *   the sbt project id (e.g. "schema"); the single source of truth for a module's identity — never re-declared.
  * @param dependsOn
  *   direct classpath dependencies (from sbt `dependsOn` / `buildDependencies.classpathRefs`). Drives `needs` edges.
  * @param publishes
  *   whether this module publishes an artifact (derived from `publish / skip == false`).
  * @param ciRelevant
  *   whether this module participates in the CI test/build fan-out.
  * @param crossScalaVersions
  *   the module's Scala versions; drives the per-module build matrix. A single-element list means no matrix axis.
  * @param testTask
  *   sbt task name used to test this module (default "test"; e.g. "testFull").
  * @param publishTask
  *   sbt task name used to publish this module (default "publish").
  * @param baseDir
  *   the module's base directory relative to the build root (e.g. "core-lib"), or "" for the root project. Used to map
  *   changed files back to owning modules for affected-only CI.
  * @param docker
  *   whether this module publishes a docker image (has sbt-native-packager's Docker plugin enabled / opted in). Drives
  *   the docker capability's per-module jobs.
  */
final case class ModuleNode(
    id: String,
    dependsOn: List[String] = Nil,
    publishes: Boolean = false,
    ciRelevant: Boolean = true,
    crossScalaVersions: List[String] = Nil,
    testTask: String = "test",
    publishTask: String = "publish",
    baseDir: String = "",
    docker: Boolean = false,
)

/** The module dependency graph. Nodes are keyed by id; edges are `dependsOn` (child → its dependencies). */
final case class ModuleGraph(nodes: List[ModuleNode]):
  private val byId: Map[String, ModuleNode] = nodes.map(n => n.id -> n).toMap

  /** All node ids, sorted — the canonical deterministic ordering used everywhere planning must be stable. */
  val ids: List[String] = nodes.map(_.id).sorted

  def get(id: String): Option[ModuleNode] = byId.get(id)

  /** Direct dependencies of `id`, restricted to ids present in this graph (external deps are dropped). */
  def directDeps(id: String): List[String] =
    byId.get(id).toList.flatMap(_.dependsOn).filter(byId.contains).distinct

  /** Transitive dependency closure of `id` (its dependencies, their dependencies, ...), excluding `id` itself. */
  def transitiveDeps(id: String): Set[String] =
    def go(frontier: List[String], seen: Set[String]): Set[String] =
      frontier match
        case Nil    => seen
        case h :: t =>
          val next = directDeps(h).filterNot(seen)
          go(next ++ t, seen ++ next)
    go(List(id), Set.empty) - id

  /** Direct reverse dependencies — modules that directly depend on `id`. */
  def directDependents(id: String): List[String] =
    ids.filter(other => directDeps(other).contains(id))

  /** Transitive reverse-dependency closure of a set of ids (the "affected" set): the seeds plus everything that
    * transitively depends on any seed. Used for affected-only CI.
    */
  def affectedClosure(seeds: Set[String]): Set[String] =
    def go(frontier: List[String], seen: Set[String]): Set[String] =
      frontier match
        case Nil    => seen
        case h :: t =>
          val next = directDependents(h).filterNot(seen)
          go(next ++ t, seen ++ next)
    go(seeds.toList, seeds.filter(byId.contains))

  /** A deterministic topological sort: dependencies before dependents. Ties are broken by sorted id so the result is
    * stable across runs (required for the generate/check round-trip). Throws [[CyclicGraphError]] on a cycle.
    */
  def topologicalSort: List[String] = topologicalLayers.flatten

  /** Topological *layers*: layer 0 has no in-graph dependencies; each subsequent layer depends only on earlier layers.
    * Within a layer, ids are sorted. This is the basis for wave-based scheduling and for reasoning about publish order.
    */
  def topologicalLayers: List[List[String]] =
    kahnLayers(ids, id => directDeps(id).toSet)

  /** Topological layers over the subset of modules matching `include`, with edges *contracted* through excluded
    * intermediates: an included node depends on the nearest included ancestors reachable through any chain of excluded
    * nodes. This is the publish-ordering view — e.g. layers of just the publishing modules. Empty when nothing matches.
    */
  def subsetLayers(include: ModuleNode => Boolean): List[List[String]] =
    val included: Set[String]                             = nodes.filter(include).map(_.id).toSet
    def nearestIncludedAncestors(id: String): Set[String] =
      def go(frontier: List[String], found: Set[String], seen: Set[String]): Set[String] =
        frontier match
          case Nil    => found
          case h :: t =>
            val deps               = directDeps(h).filterNot(seen)
            val (inc, passthrough) = deps.partition(included.contains)
            go(passthrough ++ t, found ++ inc, seen ++ deps)
      go(List(id), Set.empty, Set.empty)
    kahnLayers(included.toList.sorted, nearestIncludedAncestors)
  end subsetLayers

  /** Kahn's algorithm producing deterministic layers over `nodeIds`, using `depsOf` for in-edges (restricted to
    * `nodeIds`). Ties broken by sorted id. Throws [[CyclicGraphError]] on a cycle.
    */
  private def kahnLayers(nodeIds: List[String], depsOf: String => Set[String]): List[List[String]] =
    val present                                                          = nodeIds.toSet
    val remainingDeps: scala.collection.mutable.Map[String, Set[String]] =
      scala.collection.mutable.Map.from(nodeIds.map(id => id -> depsOf(id).intersect(present)))
    val layers = scala.collection.mutable.ListBuffer.empty[List[String]]
    while remainingDeps.nonEmpty do
      val ready = remainingDeps.collect { case (id, deps) if deps.isEmpty => id }.toList.sorted
      if ready.isEmpty then throw CyclicGraphError(remainingDeps.keys.toList.sorted)
      layers += ready
      ready.foreach(remainingDeps.remove)
      remainingDeps.mapValuesInPlace((_, deps) => deps -- ready.toSet)
    layers.toList
  end kahnLayers

end ModuleGraph

/** Raised when the module graph contains a dependency cycle (which sbt itself forbids, but we guard anyway). */
final case class CyclicGraphError(involved: List[String])
    extends RuntimeException(s"Dependency cycle among modules: ${involved.mkString(", ")}")
