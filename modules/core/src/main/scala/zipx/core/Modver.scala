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

final case class MovedRows(
    versionChanged: Set[ShipRef],
    added: Set[ShipRef],
    newMembers: Set[ModuleId],
)

object MovedRows:
  val empty: MovedRows = MovedRows(Set.empty, Set.empty, Set.empty)

/** One catalog rewrite: identity is a Ship project id or a ShipGroup name. `to` is the release number, never `-ci`. */
final case class ShipBump(identity: String, from: String, to: String)

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

  def registryStatus(httpStatus: Int): Either[String, RegistryStatus] =
    httpStatus match
      case 200       => Right(RegistryStatus.Published)
      case 404 | 410 => Right(RegistryStatus.Missing)
      case n         => Left(s"HTTP $n")

  def isZeroSha(sha: String): Boolean =
    sha.isEmpty || sha.forall(_ == '0')

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

  def bumpVersion(from: String, kind: BumpKind): Either[String, String] =
    if from.endsWith("-ci") then Left(s"version '$from' must be the release number, not a -ci suffix")
    else
      parseSemver(from) match
        case None                  => Left(s"not a major.minor.patch version: '$from'")
        case Some((maj, min, pat)) =>
          kind match
            case BumpKind.None | BumpKind.PreRelease =>
              Left(s"$kind is not a min-bump")
            case BumpKind.Patch => Right(s"$maj.$min.${pat + 1}")
            case BumpKind.Minor => Right(s"$maj.${min + 1}.0")
            case BumpKind.Major => Right(s"${maj + 1}.0.0")

  private def parseSemver(raw: String): Option[(Int, Int, Int)] =
    val core  = raw.stripPrefix("v").takeWhile(_ != '-')
    val parts = core.split("\\.", -1)
    if parts.length != 3 then None
    else
      for
        major <- parts(0).toIntOption
        minor <- parts(1).toIntOption
        patch <- parts(2).toIntOption
      yield (major, minor, patch)
  end parseSemver

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
        val inherited     = if inheritTrigger then out(src) else BumpKind.Patch
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

  def isJsOnly(graph: ModuleGraph, root: ModuleId): Boolean =
    val rows = graph.nodes.filter(n => n.matrixRoot == root && n.publishes)
    rows.nonEmpty && rows.forall(n => (n.id: String).endsWith("JS"))

  def minBumpKind(version: String, scheme: String, probe: MemberProbe): BumpKind =
    probe match
      case MemberProbe.FirstPublish => BumpKind.None
      case MemberProbe.JsOnly       => BumpKind.Patch
      case MemberProbe.Clean        => BumpKind.Patch
      case MemberProbe.BinaryBreak  =>
        val earlyZero = parseSemver(version).exists(_._1 == 0) && isEarlySemver(scheme)
        if earlyZero then BumpKind.Minor else BumpKind.Major

  def isEarlySemver(scheme: String): Boolean =
    scheme.trim.toLowerCase match
      case "semver-spec" => false
      case _             => true

  def maxKind(kinds: Iterable[BumpKind]): BumpKind =
    val counted = kinds.filter(k => k != BumpKind.None && k != BumpKind.PreRelease)
    counted.maxOption(using minBumpOrd).getOrElse(BumpKind.None)

  def suggestedVersion(from: String, kind: BumpKind): Either[String, String] =
    kind match
      case BumpKind.None | BumpKind.PreRelease => Right(from)
      case other                               => bumpVersion(from, other)

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
    status == BumpStatus.Missing || status == BumpStatus.Undersized || status == BumpStatus.NewMemberDirty

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
      previous: ShipIndex,
      schemeOf: ModuleId => String,
      probeOf: ModuleId => MemberProbe,
  ): Map[ShipRef, BumpKind] =
    lifted.iterator.map { ref =>
      index.byIdentity.get(ref) match
        case None      => ref -> BumpKind.None
        case Some(row) =>
          val kinds = row.memberRoots.map { root =>
            val probe =
              if previous.rowFor(root).isEmpty then MemberProbe.FirstPublish
              else if isJsOnly(graph, root) then MemberProbe.JsOnly
              else probeOf(root)
            minBumpKind(row.version, schemeOf(root), probe)
          }
          ref -> maxKind(kinds)
    }.toMap

  def newMemberDirtyRefs(moved: MovedRows, lifted: Set[ShipRef], index: ShipIndex): Set[ShipRef] =
    moved.newMembers.flatMap { id =>
      index.byRoot.get(id).map(index.refOf).filter { ref =>
        lifted.contains(ref) && !moved.versionChanged.contains(ref)
      }
    }

  def report(
      current: ShipIndex,
      previous: ShipIndex,
      lifted: Set[ShipRef],
      moved: MovedRows,
      kinds: Map[ShipRef, BumpKind],
      mimaRan: Set[ShipRef],
  ): Either[String, ModverReport] =
    val dirty = newMemberDirtyRefs(moved, lifted, current)
    val refs  = (kinds.keySet ++ dirty).toList.sortBy {
      case ShipRef.One(id)     => id: String
      case ShipRef.Group(name) => name: String
    }
    refs
      .foldLeft[Either[String, List[ModverReportRow]]](Right(Nil)) { (acc, ref) =>
        acc.flatMap { rows =>
          current.byIdentity.get(ref) match
            case None      => Left(s"no catalog row for $ref")
            case Some(row) =>
              val written = row.version: String
              val from    = previous.byIdentity.get(ref).map(r => r.version: String).getOrElse(written)
              val floor   = kinds.getOrElse(ref, BumpKind.None)
              suggestedVersion(from, floor).map { suggested =>
                val status0 = writtenStatus(from, written, floor)
                val status  =
                  if dirty.contains(ref) && written == from then BumpStatus.NewMemberDirty else status0
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
              }
        }
      }
      .map(ModverReport(_))
  end report
end Modver
