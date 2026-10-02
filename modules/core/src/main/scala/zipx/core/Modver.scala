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

/** Min-bump map after lift and after [[ModverPropagate.expand]]. */
opaque type BumpSet = Map[ShipRef, BumpKind]
object BumpSet:
  def empty: BumpSet                                       = Map.empty
  def apply(m: Map[ShipRef, BumpKind]): BumpSet            = m
  extension (b: BumpSet) def asMap: Map[ShipRef, BumpKind] = b

/** Reverse-dep bump policy on the contracted Ship graph. Intra-group `dependsOn` is not an edge. */
enum ModverPropagate:
  case Never
  case PatchPublished
  case MatchBump
  case Custom(f: (Map[ShipRef, BumpKind], ModuleGraph, ShipIndex) => Map[ShipRef, BumpKind])

  def expand(bumps: BumpSet, graph: ModuleGraph, ships: ShipIndex): BumpSet =
    this match
      case Never          => bumps
      case Custom(f)      => BumpSet(f(bumps.asMap, graph, ships))
      case PatchPublished => Modver.propagate(bumps, graph, ships, inheritTrigger = false)
      case MatchBump      => Modver.propagate(bumps, graph, ships, inheritTrigger = true)
end ModverPropagate

object ModverPropagate:
  def default: ModverPropagate = Never

  def custom(
      f: (Map[ShipRef, BumpKind], ModuleGraph, ShipIndex) => Map[ShipRef, BumpKind]
  ): ModverPropagate = Custom(f)
end ModverPropagate

enum RegistryStatus:
  case Published, Missing

/** Maven GAV as published, including the Scala-binary suffix on `artifact`. */
final case class Gav(organization: String, artifact: String, version: String)

/** Identity is a Ship project id or a ShipGroup name. */
final case class ShipBump(identity: String, from: ReleaseVersion, to: ReleaseVersion)

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

  /** 16-char SHA-256 hex of sorted `identity<TAB>version` lines. LocalDir [[CacheEpoch.ShipCatalog]] key. */
  def epochHash(ships: Seq[PublishedRow]): String =
    val lines = ships.map(r => s"${r.identity}\t${r.version: String}").sorted.mkString("\n")
    val md    = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(lines.getBytes(java.nio.charset.StandardCharsets.UTF_8)).take(8).map("%02x".format(_)).mkString

  /** Whether a registry response means the GAV is already published. 304 counts: `HttpLookup` revalidates a POM it
    * already fetched, and the registry answers Not Modified. That POM is there.
    */
  def registryStatus(httpStatus: Int): Either[String, RegistryStatus] =
    httpStatus match
      case 200 | 304 => Right(RegistryStatus.Published)
      case 404 | 410 => Right(RegistryStatus.Missing)
      case n         => Left(s"HTTP $n")

  def publishingRoots(graph: ModuleGraph): Set[ModuleId] =
    graph.nodes.filter(_.publishes).map(_.matrixRoot).toSet

  def rowFor(projectId: ModuleId, ships: Seq[PublishedRow]): Option[PublishedRow] =
    ships.find(_.memberRoots.contains(projectId))

  /** Exact member root, then a JS/Native platform suffix of a root that has a row. */
  def rowForProject(projectId: String, ships: Seq[PublishedRow]): Option[PublishedRow] =
    ModuleId.make(projectId).toOption.flatMap { id =>
      rowFor(id, ships).orElse {
        val parent =
          if projectId.endsWith("JS") && projectId.length > 2 then Some(projectId.dropRight(2))
          else if projectId.endsWith("Native") && projectId.length > 6 then Some(projectId.dropRight(6))
          else None
        parent.flatMap(ModuleId.make(_).toOption).flatMap(rowFor(_, ships))
      }
    }

  /** sbt overwrites only a `-SNAPSHOT` on republish; any other version is written once and then skipped. */
  val UnreleasedSuffix = "-SNAPSHOT"

  /** Walk published reverse-deps after MiMa kinds exist. Never is identity so MatchBump cannot see Patch placeholders.
    */
  def expand(bumps: BumpSet, graph: ModuleGraph, ships: ShipIndex, policy: ModverPropagate): BumpSet =
    policy.expand(bumps, graph, ships)

  /** Direct contracted dependents: each [[PublishedRow]] is a node; A depends on B when a member of A `dependsOn` a
    * module whose [[ModuleNode.matrixRoot]] is in B. Intra-group edges are dropped. Values are reverse-deps of the key.
    */
  private[core] def contractedDependents(graph: ModuleGraph, ships: ShipIndex): Map[ShipRef, Set[ShipRef]] =
    val empty = ships.byIdentity.keys.map(_ -> Set.empty[ShipRef]).toMap
    graph.nodes.foldLeft(empty) { (acc, node) =>
      ships.rowFor(node.matrixRoot) match
        case None          => acc
        case Some(fromRow) =>
          val fromRef = ships.refOf(fromRow)
          node.dependsOn.foldLeft(acc) { (m, depId) =>
            graph.get(depId).flatMap(dep => ships.rowFor(dep.matrixRoot)) match
              case Some(toRow) =>
                val toRef = ships.refOf(toRow)
                if fromRef == toRef then m
                else m.updated(toRef, m.getOrElse(toRef, Set.empty) + fromRef)
              case None => m
          }
    }
  end contractedDependents

  private[core] def propagate(
      bumps: BumpSet,
      graph: ModuleGraph,
      ships: ShipIndex,
      inheritTrigger: Boolean,
  ): BumpSet =
    val dependents = contractedDependents(graph, ships)
    val seed       = bumps.asMap
    val start      = seed.iterator.collect { case (ref, kind) if isMinBump(kind) => ref }.toList
    BumpSet(walk(seed, start, dependents, inheritTrigger))
  end propagate

  private def isMinBump(kind: BumpKind): Boolean =
    kind == BumpKind.Patch || kind == BumpKind.Minor || kind == BumpKind.Major

  @annotation.tailrec
  private def walk(
      out: Map[ShipRef, BumpKind],
      queue: List[ShipRef],
      dependents: Map[ShipRef, Set[ShipRef]],
      inheritTrigger: Boolean,
  ): Map[ShipRef, BumpKind] =
    queue match
      case Nil       => out
      case src :: qs =>
        val inherited     = if inheritTrigger then out.getOrElse(src, BumpKind.Patch) else BumpKind.Patch
        val (next, extra) =
          dependents.getOrElse(src, Set.empty).foldLeft((out, List.empty[ShipRef])) { case ((m, acc), dep) =>
            val proposed = m.get(dep).fold(inherited)(existing => minBumpOrd.max(existing, inherited))
            m.get(dep) match
              case Some(prev) if !minBumpOrd.lt(prev, proposed) => (m, acc)
              case _                                            => (m.updated(dep, proposed), dep :: acc)
          }
        walk(next, qs ++ extra, dependents, inheritTrigger)
  end walk

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

  def membership(graph: ModuleGraph, ships: Seq[PublishedRow]): Either[String, ShipIndex] =
    for
      _ <- firstError(ships.flatMap(emptyGroupError))
      _ <- firstError(duplicateIdentityErrors(ships))
      _ <- firstError(ships.flatMap(memberErrors(graph, _)))
      _ <- overlapError(ships)
      _ <- uncoveredError(graph, ships)
    yield ShipIndex.from(ships)

  private def firstError(errs: Seq[String]): Either[String, Unit] =
    errs.headOption.toLeft(())

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

  def isJsOnly(graph: ModuleGraph, root: ModuleId): Boolean =
    val rows = graph.nodes.filter(n => n.matrixRoot == root && n.publishes)
    rows.nonEmpty && rows.forall(n => (n.id: String).endsWith("JS"))

  def minBumpKind(version: ReleaseVersion, scheme: LibraryVersionScheme, probe: MemberProbe): BumpKind =
    probe match
      case MemberProbe.FirstPublish => BumpKind.None
      case MemberProbe.JsOnly       => BumpKind.Patch
      case MemberProbe.Clean        => BumpKind.Patch
      case MemberProbe.BinaryBreak  => scheme.binaryBreak(version)

  def maxKind(kinds: Iterable[BumpKind]): BumpKind =
    val counted = kinds.filter(k => k != BumpKind.None && k != BumpKind.PreRelease)
    counted.maxOption(using minBumpOrd).getOrElse(BumpKind.None)

  def suggestedVersion(from: ReleaseVersion, kind: BumpKind): ReleaseVersion =
    ReleaseBump.of(kind).fold(from)(from.bump)

  def writtenStatus(base: String, written: String, floor: BumpKind): BumpStatus =
    if floor == BumpKind.None || floor == BumpKind.PreRelease then BumpStatus.Ok
    else if written == base then BumpStatus.Missing
    else
      val got = VersionStrategy.npm.classify(base, written)
      val cmp = minBumpOrd.compare(got, floor)
      if cmp < 0 then BumpStatus.Undersized
      else if cmp > 0 then BumpStatus.OverBump
      else BumpStatus.Ok

  def checkFails(status: BumpStatus): Boolean =
    status == BumpStatus.Missing || status == BumpStatus.Undersized

  def suggestedCtor(row: PublishedRow, to: String): String =
    row match
      case s: Ship      => s"""Ship("${s.id}", "$to")"""
      case g: ShipGroup =>
        val mem = g.members.map(m => s""""$m"""").mkString(", ")
        s"""ShipGroup("${g.name}", "$to")($mem)"""

  def minBumps(
      lifted: Set[ShipRef],
      index: ShipIndex,
      graph: ModuleGraph,
      lastReleases: ShipIndex,
      schemeOf: ModuleId => Either[String, LibraryVersionScheme],
      probeOf: ModuleId => Either[String, MemberProbe],
  ): Either[String, Map[ShipRef, BumpKind]] =
    def probe(root: ModuleId): Either[String, MemberProbe] =
      if lastReleases.rowFor(root).isEmpty then Right(MemberProbe.FirstPublish)
      else if isJsOnly(graph, root) then Right(MemberProbe.JsOnly)
      else probeOf(root)
    lifted.toList.foldLeft[Either[String, Map[ShipRef, BumpKind]]](Right(Map.empty)) { (acc, ref) =>
      acc.flatMap { kinds =>
        index.byIdentity.get(ref) match
          case None      => Right(kinds + (ref -> BumpKind.None))
          case Some(row) =>
            row.memberRoots
              .foldLeft[Either[String, List[BumpKind]]](Right(Nil)) { (found, root) =>
                found.flatMap { ks =>
                  probe(root).flatMap(p => schemeOf(root).map(scheme => minBumpKind(row.version, scheme, p) :: ks))
                }
              }
              .map(ks => kinds + (ref -> maxKind(ks)))
      }
    }
  end minBumps

  def report(
      current: ShipIndex,
      lastReleases: ShipIndex,
      kinds: Map[ShipRef, BumpKind],
      mimaRan: Set[ShipRef],
  ): Either[String, ModverReport] =
    val refs = kinds.keySet.toList.sortBy {
      case ShipRef.One(id)     => id: String
      case ShipRef.Group(name) => name: String
    }
    refs
      .foldLeft[Either[String, List[ModverReportRow]]](Right(Nil)) { (acc, ref) =>
        acc.flatMap { rows =>
          current.byIdentity.get(ref) match
            case None      => Left(s"no catalog row for $ref")
            case Some(row) =>
              val written   = row.version
              val from      = lastReleases.byIdentity.get(ref).fold(written)(_.version)
              val floor     = kinds.getOrElse(ref, BumpKind.None)
              val suggested = suggestedVersion(from, floor)
              val status    = writtenStatus(from, written, floor)
              Right(
                rows :+ ModverReportRow(
                  identity = row.identity,
                  label = row.label,
                  from = from,
                  written = written,
                  suggested = suggested,
                  constructor = suggestedCtor(row, suggested),
                  kind = floor,
                  mimaRan = mimaRan.contains(ref),
                  status = status,
                )
              )
        }
      }
      .map(ModverReport(_))
  end report
end Modver
