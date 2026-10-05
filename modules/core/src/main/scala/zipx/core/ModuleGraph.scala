package zipx.core

import neotype.Subtype
import zipx.workflow.ExprLiteral
import zipx.workflow.Names

/** An sbt project id that zipx can put in a workflow: GitHub's ASCII identifier rule, stricter than sbt's own (`café`
  * is a legal sbt project).
  *
  * The id lands in a `jobs.<job_id>` key and single-quoted in `contains(fromJson(…), 'api')`. The id rule is a strict
  * subset of the expression-literal rule, so it covers both; `ModuleIdSpec` checks that.
  */
type ModuleId = ModuleId.Type
object ModuleId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a module id must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else
      s"invalid module id '$input': a GitHub job id must start with an ASCII letter or _ and contain only ASCII " +
        "letters, digits, - or _, which is stricter than sbt's own project-id rule"

  /** For the planner's synthetic nodes (`cache-rehydrate`). `unsafeMake` is total: both validators are
    * `Names.ActionsId` and non-empty, and must change together.
    */
  def fromJobId(id: zipx.workflow.JobId): ModuleId = unsafeMake(id)

  extension (id: ModuleId)
    /** `unsafeMake` is total: [[zipx.workflow.Names.ActionsId]] is a strict subset of
      * [[zipx.workflow.Names.ExprLiteral]] in every position (checked in `ModuleIdSpec`).
      */
    def asExprLiteral: ExprLiteral = ExprLiteral.unsafeMake(id)
end ModuleId

/** A build module as zipx sees it: the sbt-agnostic projection of an sbt project.
  *
  * @param dependsOn
  *   direct classpath dependencies (sbt `dependsOn`). Drives `needs` edges.
  * @param publishes
  *   derived from `publish / skip == false`.
  * @param crossScalaVersions
  *   drives the per-module build matrix. A single-element list means no matrix axis.
  * @param testTask
  *   command text rather than a task name, since `Compile/test` and `test:compile` are both legitimate.
  * @param baseDir
  *   relative to the build root, or "" for the root project. Maps changed files back to owning modules.
  * @param sourcePaths
  *   relative to the build root, from `unmanagedSourceDirectories`. Empty means [[baseDir]] is the whole answer.
  *   Cross-built modules need these: `ProjectMatrix` bases each platform row at a synthetic `.sbt/matrix/<id>` that no
  *   source file is under, and `core/src/main/scalajs` is on the JS row alone while `core/src/main/scala` is on both.
  * @param docker
  *   has sbt-native-packager's Docker plugin enabled. Drives the docker capability's per-module jobs.
  * @param matrixRootOpt
  *   override for [[matrixRoot]]. `None` means the root is [[id]].
  * @param libraries
  *   the libraries this module declares, so a catalog bump affects the modules that use the row. See [[CatalogChange]].
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
    matrixRootOpt: Option[ModuleId] = None,
    libraries: Set[LibCoordinate] = Set.empty,
):

  /** The sbt project id a [[Ship]] names. A `projectMatrix` JVM row `core` and its JS row `coreJS` share
    * `matrixRoot = core`.
    */
  def matrixRoot: ModuleId = matrixRootOpt.getOrElse(id)

  /** A union: `baseDir` still answers for non-source files (a README, a Dockerfile), and `sourcePaths` reaches what
    * lies outside it.
    */
  def ownedPaths: List[String] = (baseDir +: sourcePaths).distinct
end ModuleNode

object ModuleNode:
  /** A placeholder the plugin always overwrites from `zipxTasks`. [[Coverage.measuredTask]] compares against it. */
  val DefaultTestTask: SbtCommand = SbtCommand.unsafeTask("test")

  /** A placeholder the plugin always overwrites from `zipxTasks`. */
  val DefaultPublishTask: SbtCommand = SbtCommand.unsafeTask("publish")

  /** Stand-in for probing [[CommandSource.PerModule]]. Underscore-prefixed so no real project id collides. */
  private[core] val probe: ModuleNode = ModuleNode(id = ModuleId("_probe"))
end ModuleNode

/** The module dependency graph. Edges are `dependsOn` (child to its dependencies).
  *
  * Acyclic by construction: [[ModuleGraph.make]] is the only constructor, so every ordering query is total. A
  * `dependsOn` id absent from the nodes is an external library dependency, not an error; [[directDeps]] drops it.
  *
  * @param topologicalLayers
  *   layer 0 has no in-graph dependencies; each later layer depends only on earlier ones. Ids within a layer are
  *   sorted, which the generate/check round-trip requires.
  */
final case class ModuleGraph private (nodes: List[ModuleNode], topologicalLayers: List[List[String]]):
  private val byId: Map[String, ModuleNode] = nodes.map(n => n.id -> n).toMap

  val ids: List[String] = nodes.map(_.id).sorted

  def get(id: String): Option[ModuleNode] = byId.get(id)

  def directDeps(id: String): List[String] =
    byId.get(id).toList.flatMap(_.dependsOn).filter(byId.contains).distinct

  def transitiveDeps(id: String): Set[String] =
    def go(frontier: List[String], seen: Set[String]): Set[String] =
      frontier match
        case Nil    => seen
        case h :: t =>
          val next = directDeps(h).filterNot(seen)
          go(next ++ t, seen ++ next)
    go(List(id), Set.empty) - id

  def directDependents(id: String): List[String] =
    ids.filter(other => directDeps(other).contains(id))

  /** The seeds present in the graph plus everything that transitively depends on one. */
  def affectedClosure(seeds: Set[String]): Set[String] =
    def go(frontier: List[String], seen: Set[String]): Set[String] =
      frontier match
        case Nil    => seen
        case h :: t =>
          val next = directDependents(h).filterNot(seen)
          go(next ++ t, seen ++ next)
    go(seeds.toList, seeds.filter(byId.contains))

  def topologicalSort: List[String] = topologicalLayers.flatten

  /** `f`'s changes to `id` and `dependsOn` are ignored, so the computed layers stay valid. To change edges, build a new
    * graph with [[ModuleGraph.make]].
    */
  def mapNodes(f: ModuleNode => ModuleNode): ModuleGraph =
    new ModuleGraph(nodes.map(n => f(n).copy(id = n.id, dependsOn = n.dependsOn)), topologicalLayers)

  /** Layers over the modules matching `include`, with edges contracted through excluded intermediates: the
    * publish-ordering view. Each included node sits one past its deepest included ancestor, which already precedes it
    * in [[topologicalSort]].
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

  /** `Left` carries the ids in a cycle. sbt forbids cycles, so only a hand-built graph gets one. */
  def make(nodes: List[ModuleNode]): Either[String, ModuleGraph] =
    layers(nodes) match
      case Right(layers)  => Right(new ModuleGraph(nodes, layers))
      case Left(involved) => Left(s"dependency cycle among modules: ${involved.mkString(", ")}")

  /** Takes raw edges because [[Planner]] checks `needsCapabilities` with it, and capability names are not module ids.
    * Dependencies absent from the keys are ignored, as externals are.
    */
  def cycle(edges: Map[String, List[String]]): Option[List[String]] =
    val present = edges.keySet
    layersOrCycle(edges.keys.toList, id => edges.getOrElse(id, Nil).toSet.intersect(present)).left.toOption

  private def layers(nodes: List[ModuleNode]): Either[List[String], List[List[String]]] =
    // A duplicated id is one node to order (`byId` keeps the last), while `ModuleGraph.ids` keeps every occurrence.
    val present: Set[String]           = nodes.map(n => n.id: String).toSet
    val deps: Map[String, Set[String]] =
      nodes.groupMapReduce(n => n.id: String)(_.dependsOn.toSet.intersect(present))(_ ++ _)
    layersOrCycle(nodes.map(_.id).distinct, id => deps.getOrElse(id, Set.empty))

  /** Kahn's algorithm, ties broken by sorted id. `Left` carries the ids still holding unmet dependencies. */
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
