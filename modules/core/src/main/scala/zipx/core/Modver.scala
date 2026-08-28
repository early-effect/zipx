package zipx.core

/** Identity of a catalog row: a lone [[Ship]]'s project id, or a [[ShipGroup]]'s name. Not a platform row id. */
enum ShipRef:
  case One(id: ModuleId)
  case Group(name: ShipGroupName)

final case class ShipIndex(
    byIdentity: Map[ShipRef, PublishedRow],
    byRoot: Map[ModuleId, PublishedRow],
):
  def refOf(row: PublishedRow): ShipRef = ShipIndex.refOf(row)

  def rowFor(id: ModuleId): Option[PublishedRow] = byRoot.get(id)

  def liftGroups(dirtyRoots: Set[ModuleId]): Set[ShipRef] =
    dirtyRoots.flatMap(byRoot.get).map(refOf).toSet
end ShipIndex

object ShipIndex:
  val empty: ShipIndex = ShipIndex(Map.empty, Map.empty)

  def refOf(row: PublishedRow): ShipRef = row match
    case s: Ship      => ShipRef.One(s.id)
    case g: ShipGroup => ShipRef.Group(g.name)

  def from(rows: Seq[PublishedRow]): ShipIndex =
    val byIdentity = rows.map(r => refOf(r) -> r).toMap
    val byRoot     = rows.flatMap(r => r.memberRoots.map(_ -> r)).toMap
    ShipIndex(byIdentity, byRoot)
end ShipIndex

/** Min-bump map after lift (and, later, after propagate). */
opaque type BumpSet = Map[ShipRef, BumpKind]
object BumpSet:
  def empty: BumpSet                                       = Map.empty
  def apply(m: Map[ShipRef, BumpKind]): BumpSet            = m
  extension (b: BumpSet) def asMap: Map[ShipRef, BumpKind] = b

enum RegistryStatus:
  case Published, Missing

/** Maven GAV as published, including the Scala-binary suffix on `artifact`. */
final case class Gav(organization: String, artifact: String, version: String)

final case class MovedRows(
    versionChanged: Set[ShipRef],
    added: Set[ShipRef],
    newMembers: Set[ModuleId],
)

object MovedRows:
  val empty: MovedRows = MovedRows(Set.empty, Set.empty, Set.empty)

/** Fail-closed bump and publish sets. Verify's [[Affected]] stays a sibling; do not call it from here. */
object Modver:

  /** `None < Patch < Minor < Major`. [[BumpKind.PreRelease]] is not a min-bump. */
  val minBumpOrd: _root_.scala.math.Ordering[BumpKind] =
    _root_.scala.math.Ordering.by {
      case BumpKind.None       => 0
      case BumpKind.Patch      => 1
      case BumpKind.Minor      => 2
      case BumpKind.Major      => 3
      case BumpKind.PreRelease => 0
    }

  def describe(row: PublishedRow): String = row match
    case s: Ship      => s"""Ship("${s.id}")"""
    case g: ShipGroup => s"""ShipGroup("${g.name}")"""

  def publishingRoots(graph: ModuleGraph): Set[ModuleId] =
    graph.nodes.filter(_.publishes).map(_.matrixRoot).toSet

  def rowFor(projectId: ModuleId, ships: Seq[PublishedRow]): Option[PublishedRow] =
    ships.find(_.memberRoots.contains(projectId))

  /** PR 1 stub: later rounds run MiMa then propagate. Identity so MatchBump cannot see Patch placeholders. */
  def expand(bumps: BumpSet, graph: ModuleGraph, ships: ShipIndex): BumpSet =
    val _ = (graph, ships)
    bumps

  /** Owning published matrix roots. Empty file list is empty set, not all. `.sbt` / `project/` do not expand. */
  def dirtyRoots(graph: ModuleGraph, changedFiles: List[String]): Set[ModuleId] =
    changedFiles
      .flatMap(path => Affected.owningModules(graph, path))
      .flatMap(id => graph.get(id).toList)
      .filter(_.publishes)
      .map(_.matrixRoot)
      .toSet

  /** Fail closed: None files => Left. Kinds are not Patch placeholders. */
  def liftedBumpSet(
      graph: ModuleGraph,
      ships: ShipIndex,
      changedFiles: Option[List[String]],
  ): Either[String, Set[ShipRef]] =
    changedFiles match
      case None        => Left("could not diff changed files for modver; refusing to guess the bump set")
      case Some(files) => Right(ships.liftGroups(dirtyRoots(graph, files)))

  /** Missing catalog at `before` is empty (first adoption). Failed `git show` / parse is Left. */
  def previousIndex(
      shown: Either[String, Option[String]],
      parse: String => Either[String, ShipIndex],
  ): Either[String, ShipIndex] =
    shown match
      case Left(err)        => Left(err)
      case Right(None)      => Right(ShipIndex.empty)
      case Right(Some(src)) => parse(src)

  /** Catalog diff. `previous` Left is git/parse failure. Right(empty) is first adoption, not a refusal. */
  def movedRows(
      current: ShipIndex,
      previous: Either[String, ShipIndex],
  ): Either[String, MovedRows] =
    previous.map(prev => diff(current, prev))

  def thisCommitReleases(row: PublishedRow, moved: MovedRows, index: ShipIndex): Boolean =
    val ref = index.refOf(row)
    moved.versionChanged.contains(ref) || moved.added.contains(ref) ||
    row.memberRoots.exists(moved.newMembers.contains)

  /** Every platform row of a moved row, then drop GAVs already on the registry. Fail closed on lookup errors. */
  def filterUnpublished(
      moved: MovedRows,
      index: ShipIndex,
      graph: ModuleGraph,
      gavs: ModuleId => List[Gav],
      registry: Gav => Either[String, RegistryStatus],
  ): Either[String, Map[ModuleId, List[Gav]]] =
    val roots   = candidateRoots(moved, index)
    val modules = graph.nodes.filter(n => roots.contains(n.matrixRoot))
    modules.foldLeft[Either[String, Map[ModuleId, List[Gav]]]](Right(Map.empty)) { (acc, node) =>
      acc.flatMap { m =>
        missingGavs(gavs(node.id), registry).map { missing =>
          if missing.isEmpty then m else m + (node.id -> missing)
        }
      }
    }
  end filterUnpublished

  def membership(graph: ModuleGraph, ships: Seq[PublishedRow]): Either[String, ShipIndex] =
    for
      _ <- firstError(ships.flatMap(ciVersionError))
      _ <- firstError(ships.flatMap(emptyGroupError))
      _ <- firstError(duplicateIdentityErrors(ships))
      _ <- firstError(ships.flatMap(memberErrors(graph, _)))
      _ <- overlapError(ships)
      _ <- uncoveredError(graph, ships)
    yield ShipIndex.from(ships)

  private def firstError(errs: Seq[String]): Either[String, Unit] =
    errs.headOption.toLeft(())

  private def ciVersionError(row: PublishedRow): Option[String] =
    val ver = row.version: String
    Option.when(ver.endsWith("-ci")) {
      s"${describe(row)} version '$ver' must be the release number, not a -ci suffix."
    }

  private def emptyGroupError(row: PublishedRow): Option[String] = row match
    case g: ShipGroup if g.members.isEmpty => Some(s"""ShipGroup("${g.name}") has no members.""")
    case _                                 => None

  private def duplicateIdentityErrors(ships: Seq[PublishedRow]): List[String] =
    val shipsById = ships.collect { case s: Ship => s }.groupBy(_.id)
    val groupsByN = ships.collect { case g: ShipGroup => g }.groupBy(_.name)
    shipsById.collect {
      case (id, copies) if copies.size > 1 => s"""Ship("$id") appears twice."""
    }.toList ++ groupsByN.collect {
      case (name, copies) if copies.size > 1 => s"""ShipGroup name '$name' appears twice."""
    }.toList

  private def memberErrors(graph: ModuleGraph, row: PublishedRow): List[String] =
    row.memberRoots.flatMap { root =>
      graph.nodes.find(_.id == (root: String)) match
        case None =>
          row match
            case _: Ship      => List(s"""Ship("$root") is not an sbt project id.""")
            case g: ShipGroup => List(s"""ShipGroup("${g.name}") member '$root' is not an sbt project id.""")
        case Some(node) if node.matrixRoot != root =>
          List(
            s"""Ship("$root") names a platform row; use Ship("${node.matrixRoot}", …) for the matrix root."""
          )
        case Some(node) if !node.publishes =>
          row match
            case _: Ship =>
              List(s"""Ship("$root") does not publish. Drop it or set publish / skip := false.""")
            case g: ShipGroup =>
              List(
                s"""ShipGroup("${g.name}") member '$root' does not publish. Drop it or set publish / skip := false."""
              )
        case Some(_) => Nil
    }

  private def overlapError(ships: Seq[PublishedRow]): Either[String, Unit] =
    val byRoot = ships.flatMap(r => r.memberRoots.map(_ -> r)).groupMap(_._1)(_._2)
    val clash  = byRoot.collect {
      case (root, rows) if rows.distinct.size > 1 =>
        val listed = rows.distinct.map(describe).sorted.mkString(" and ")
        s"published module '$root' is in $listed. Each publishes=true module must be in exactly one row."
    }.headOption
    clash.toLeft(())

  private def uncoveredError(graph: ModuleGraph, ships: Seq[PublishedRow]): Either[String, Unit] =
    val covered = ships.flatMap(_.memberRoots).toSet
    val missing = publishingRoots(graph).toList
      .sortBy(id => id: String)
      .collect {
        case root if !covered.contains(root) =>
          s"""published module '$root' is not in a Ship or ShipGroup. Add Ship("$root", "…") or a ShipGroup member."""
      }
      .headOption
    missing.toLeft(())
  end uncoveredError

  private def diff(current: ShipIndex, previous: ShipIndex): MovedRows =
    val currentRefs    = current.byIdentity.keySet
    val prevRefs       = previous.byIdentity.keySet
    val added          = currentRefs -- prevRefs
    val versionChanged = (currentRefs intersect prevRefs).filter { ref =>
      val now = current.byIdentity(ref).version: String
      val was = previous.byIdentity(ref).version: String
      now != was
    }
    val newMembers = current.byIdentity.flatMap { (ref, row) =>
      previous.byIdentity.get(ref) match
        case None       => Set.empty[ModuleId]
        case Some(prev) => row.memberRoots.toSet -- prev.memberRoots.toSet
    }.toSet
    MovedRows(versionChanged, added, newMembers)
  end diff

  private def candidateRoots(moved: MovedRows, index: ShipIndex): Set[ModuleId] =
    val fromRows = (moved.versionChanged ++ moved.added).flatMap { ref =>
      index.byIdentity.get(ref).toList.flatMap(_.memberRoots)
    }
    val fromNew = moved.newMembers.flatMap(id => index.byRoot.get(id).toList.flatMap(_.memberRoots))
    fromRows ++ fromNew ++ moved.newMembers

  private def missingGavs(
      gavs: List[Gav],
      registry: Gav => Either[String, RegistryStatus],
  ): Either[String, List[Gav]] =
    gavs.foldLeft[Either[String, List[Gav]]](Right(Nil)) { (acc, gav) =>
      acc.flatMap { missing =>
        registry(gav).map {
          case RegistryStatus.Missing   => missing :+ gav
          case RegistryStatus.Published => missing
        }
      }
    }
end Modver
