package zipx.core

import neotype.Subtype
import zipx.workflow.ExprLiteral
import zipx.workflow.Names

/** An sbt project id that zipx can put in a workflow: GitHub's identifier rule, which is stricter than sbt's own.
  *
  * sbt accepts any id starting with a `Character.isLetter`, so `café` and `プロジェクト` are legal projects. GitHub job ids
  * are ASCII, so such a module would produce a workflow GitHub rejects. This is the newtype that catches it, and it
  * catches it where the graph is built rather than midway through planning.
  *
  * A module id is spliced into two positions with different rules, and satisfies both. It becomes part of a
  * `jobs.<job_id>` key, and it appears single-quoted inside an expression as the `'api'` of
  * `contains(fromJson(…), 'api')`. GitHub's id rule is the tighter of the two: its character set is a strict subset of
  * the expression-literal set, so checking the id rule establishes both at once and [[asExprLiteral]] needs no second
  * validation. That subset claim is not taken on faith; `ModuleIdSpec` checks it over both character positions.
  *
  * A `Subtype` rather than a `Newtype`, so `ModuleId <: String`. Reading an id needs no ceremony: `_.id == "service"`
  * in a `build.sbt`, `s"${node.id}/test"` in a command, and `Map[String, ModuleNode]` all keep working. Only
  * *construction* is checked, which is the only place a bad id can enter.
  */
type ModuleId = ModuleId.Type
object ModuleId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a module id must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else
      s"invalid module id '$input': a GitHub job id must start with an ASCII letter or _ and contain only ASCII " +
        "letters, digits, - or _, which is stricter than sbt's own project-id rule"

  /** A module id from a job id, for the planner's synthetic nodes: a job zipx invents (`cache-rehydrate`) needs a node
    * to carry it through [[StepContext]], and its identity is already the job id.
    *
    * `unsafeMake` because the two validators are the same rule, `Names.ActionsId` and non-empty, which is not a
    * coincidence: a module id is constrained *because* it becomes a job id. If they ever diverge, this is the one place
    * that has to change.
    */
  def fromJobId(id: zipx.workflow.JobId): ModuleId = unsafeMake(id)

  extension (id: ModuleId)
    /** The id as an expression literal, for the `'api'` of `contains(fromJson(…), 'api')`.
      *
      * `unsafeMake` because [[Names.ActionsId]]'s character set is a strict subset of [[Names.ExprLiteral]]'s in both
      * the first and subsequent positions, so this conversion is total. That is the property that lets the planner's
      * affected gate be built rather than validated, and it is checked exhaustively in `ModuleIdSpec`.
      */
    def asExprLiteral: ExprLiteral = ExprLiteral.unsafeMake(id)
end ModuleId

/** A build module as zipx sees it: the sbt-agnostic projection of an sbt project.
  *
  * The sbt plugin (`zipx.sbt`) builds these from `Project.extract(state).structure`; the pure core plans over them so
  * the whole planner is unit-testable without sbt on the classpath.
  *
  * @param id
  *   the sbt project id (e.g. "schema"); the single source of truth for a module's identity, never re-declared. A
  *   [[ModuleId]], so a project sbt allows but GitHub cannot name is rejected when the graph is built.
  * @param dependsOn
  *   direct classpath dependencies (from sbt `dependsOn` / `buildDependencies.classpathRefs`). Drives `needs` edges.
  * @param publishes
  *   whether this module publishes an artifact (derived from `publish / skip == false`).
  * @param ciRelevant
  *   whether this module participates in the CI test/build fan-out.
  * @param crossScalaVersions
  *   the module's Scala versions; drives the per-module build matrix. A single-element list means no matrix axis.
  * @param testTask
  *   sbt task used to test this module (default `test`; e.g. `testFull`). An [[SbtCommand]] rather than a task name:
  *   `Compile/test` and `test:compile` are both legitimate here, so this is command text, and typing it means
  *   [[Capability.testJoined]] builds `<id>/<task>` rather than interpolating one.
  * @param publishTask
  *   sbt task used to publish this module (default `publish`).
  * @param baseDir
  *   the module's base directory relative to the build root (e.g. "core-lib"), or "" for the root project. Used to map
  *   changed files back to owning modules for affected-only CI.
  * @param sourcePaths
  *   the module's source directories relative to the build root, from sbt's `unmanagedSourceDirectories`. Empty (the
  *   default) means [[baseDir]] is the whole answer, as it is for every ordinary project.
  *
  * A cross-built module needs these, because `baseDir` cannot answer for one: sbt's `ProjectMatrix` bases each platform
  * row at a synthetic `.sbt/matrix/<id>`, which no source file is under, so `core/` would map to no module at all. The
  * source dirs also carry the platform distinction, since `core/src/main/scalajs` is on the JS row alone while
  * `core/src/main/scala` is on both.
  * @param docker
  *   whether this module publishes a docker image (has sbt-native-packager's Docker plugin enabled / opted in). Drives
  *   the docker capability's per-module jobs.
  */
final case class ModuleNode(
    id: ModuleId,
    dependsOn: List[String] = Nil,
    publishes: Boolean = false,
    ciRelevant: Boolean = true,
    crossScalaVersions: List[String] = Nil,
    testTask: SbtCommand = ModuleNode.DefaultTestTask,
    publishTask: SbtCommand = ModuleNode.DefaultPublishTask,
    baseDir: String = "",
    sourcePaths: List[String] = Nil,
    docker: Boolean = false,
):

  /** Every path this module owns for affected-gating: [[baseDir]] and its [[sourcePaths]].
    *
    * A union, so recording source paths only ever *adds* ownership. `baseDir` still answers for a module's non-source
    * files (a README, a Dockerfile, a test fixture), and `sourcePaths` reaches what lies outside it.
    */
  def ownedPaths: List[String] = (baseDir +: sourcePaths).distinct
end ModuleNode

object ModuleNode:
  val DefaultTestTask: SbtCommand    = SbtCommand("test")
  val DefaultPublishTask: SbtCommand = SbtCommand("publish")

/** The module dependency graph. Nodes are keyed by id; edges are `dependsOn` (child → its dependencies).
  *
  * Acyclic by construction: [[ModuleGraph.make]] runs the toposort, reports a cycle as a `Left`, and passes the layers
  * it computed to the private constructor. So [[topologicalLayers]] is a field rather than a computation that could
  * fail, and every ordering query below is total with no cycle case to handle. Validating once at the boundary removes
  * the failure from four public methods at once, and the sbt plugin (the only place a graph comes from user input)
  * already has a seam for reporting it.
  *
  * `make` is deliberately the *only* constructor, with no throwing `apply` beside it: a graph is either checked or it
  * does not exist. Tests that want a fixture from a literal node list use `GraphFixture` in test scope, which is where
  * an unchecked one belongs.
  *
  * A `dependsOn` id absent from the node list is *not* an error: it is how an external library dependency appears, and
  * [[directDeps]] drops it.
  *
  * @param topologicalLayers
  *   layer 0 has no in-graph dependencies; each subsequent layer depends only on earlier layers. Within a layer, ids
  *   are sorted, so the result is stable across runs, which the generate/check round-trip requires. Derived from
  *   `nodes`, never passed by a caller.
  */
final case class ModuleGraph private (nodes: List[ModuleNode], topologicalLayers: List[List[String]]):
  private val byId: Map[String, ModuleNode] = nodes.map(n => n.id -> n).toMap

  /** All node ids, sorted: the canonical deterministic ordering used everywhere planning must be stable. */
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

  /** Direct reverse dependencies: modules that directly depend on `id`. */
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
    * stable across runs (required for the generate/check round-trip).
    */
  def topologicalSort: List[String] = topologicalLayers.flatten

  /** Rewrite each node's attributes, keeping the graph structure. `id` and `dependsOn` are taken from the original
    * node, so whatever `f` does to those two is ignored: that is what makes this total, since the edges are unchanged
    * and the layers already computed for them stay valid. To change edges, build a new graph with [[ModuleGraph.make]].
    */
  def mapNodes(f: ModuleNode => ModuleNode): ModuleGraph =
    new ModuleGraph(nodes.map(n => f(n).copy(id = n.id, dependsOn = n.dependsOn)), topologicalLayers)

  /** Topological layers over the subset of modules matching `include`, with edges *contracted* through excluded
    * intermediates: an included node depends on the nearest included ancestors reachable through any chain of excluded
    * nodes. This is the publish-ordering view, e.g. layers of just the publishing modules. Empty when nothing matches.
    *
    * Derived by walking [[topologicalSort]] and giving each included node a depth one past its deepest included
    * ancestor. That reuses the acyclicity already established at construction instead of re-deriving it: every included
    * ancestor of a node is one of its dependencies, so it precedes the node in topological order and its depth is
    * already known. Contraction cannot introduce a cycle either, since it preserves reachability and reachability in a
    * DAG is a strict partial order.
    */
  def subsetLayers(include: ModuleNode => Boolean): List[List[String]] =
    val included: Set[String] = nodes.filter(include).map(_.id).toSet
    val depths                =
      topologicalSort.filter(included).foldLeft(Map.empty[String, Int]) { (acc, id) =>
        val ancestors = nearestAncestors(id, included)
        acc + (id -> (if ancestors.isEmpty then 0 else ancestors.map(acc).max + 1))
      }
    depths.groupBy(_._2).toList.sortBy(_._1).map((_, group) => group.keys.toList.sorted)
  end subsetLayers

  /** The nearest ids in `included` reachable from `id` through any chain of excluded nodes. */
  private def nearestAncestors(id: String, included: Set[String]): Set[String] =
    def go(frontier: List[String], found: Set[String], seen: Set[String]): Set[String] =
      frontier match
        case Nil    => found
        case h :: t =>
          val deps               = directDeps(h).filterNot(seen)
          val (inc, passthrough) = deps.partition(included.contains)
          go(passthrough ++ t, found ++ inc, seen ++ deps)
    go(List(id), Set.empty, Set.empty)

end ModuleGraph

object ModuleGraph:

  /** A graph, or the ids involved in a dependency cycle. sbt itself forbids a cycle, so this fires only for a graph
    * built by hand; the planner's capability-ordering check is the other caller.
    */
  def make(nodes: List[ModuleNode]): Either[String, ModuleGraph] =
    layers(nodes) match
      case Right(layers)  => Right(new ModuleGraph(nodes, layers))
      case Left(involved) => Left(s"dependency cycle among modules: ${involved.mkString(", ")}")

  /** The names involved in a cycle in an arbitrary `name -> dependencies` graph, if there is one. Dependencies naming
    * something absent from the keys are ignored, as an external dependency is here too.
    *
    * Takes the edges rather than a `List[ModuleNode]` because the caller that needs it is not ordering modules:
    * [[Planner]] uses it for `needsCapabilities`, and has to word the error in terms of capabilities. Passing
    * capability names as module ids would be a lie the [[ModuleId]] rule is entitled to reject.
    */
  def cycle(edges: Map[String, List[String]]): Option[List[String]] =
    val present = edges.keySet
    layersOrCycle(edges.keys.toList, id => edges.getOrElse(id, Nil).toSet.intersect(present)).left.toOption

  private def layers(nodes: List[ModuleNode]): Either[List[String], List[List[String]]] =
    // The sort runs over distinct ids: a duplicated id is one node to order, and `byId` resolves it
    // last-definition-wins. `ModuleGraph.ids` keeps every occurrence, which is a separate, tested behaviour.
    // `id: String` widens the ModuleId subtype, so `intersect` compares two `Set[String]`s.
    val present: Set[String]           = nodes.map(n => n.id: String).toSet
    val deps: Map[String, Set[String]] =
      nodes.groupMapReduce(n => n.id: String)(_.dependsOn.toSet.intersect(present))(_ ++ _)
    layersOrCycle(nodes.map(_.id).distinct, id => deps.getOrElse(id, Set.empty))

  /** Kahn's algorithm producing deterministic layers over `nodeIds`, using `depsOf` for in-edges (restricted to
    * `nodeIds`). Ties broken by sorted id. `Left` carries the ids still holding unmet dependencies, which is the cycle.
    */
  private def layersOrCycle(
      nodeIds: List[String],
      depsOf: String => Set[String],
  ): Either[List[String], List[List[String]]] =
    val present                                                          = nodeIds.toSet
    val remainingDeps: scala.collection.mutable.Map[String, Set[String]] =
      scala.collection.mutable.Map.from(nodeIds.map(id => id -> depsOf(id).intersect(present)))
    val layers = scala.collection.mutable.ListBuffer.empty[List[String]]
    while remainingDeps.nonEmpty do
      val ready = remainingDeps.collect { case (id, deps) if deps.isEmpty => id }.toList.sorted
      if ready.isEmpty then return Left(remainingDeps.keys.toList.sorted)
      layers += ready
      ready.foreach(remainingDeps.remove)
      remainingDeps.mapValuesInPlace((_, deps) => deps -- ready.toSet)
    Right(layers.toList)
  end layersOrCycle

end ModuleGraph
