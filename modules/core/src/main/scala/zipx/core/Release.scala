package zipx.core

/** How a row stands on its registry at its catalog number. */
enum RowStatus:
  case Released
  case Unreleased
  case Partial(missing: ::[Gav])

object RowStatus:
  def of(binaries: List[(Gav, RegistryStatus)]): RowStatus =
    val missing = binaries.collect { case (gav, RegistryStatus.Missing) => gav }
    missing match
      case Nil          => if binaries.isEmpty then Unreleased else Released
      case head :: tail => if missing.sizeIs == binaries.size then Unreleased else Partial(::(head, tail))

/** While this JVM property is set, every row member builds at its catalog number. A property rather than session
  * settings because sbt drops session settings when `++` / `+` switch Scala versions.
  */
object ReleaseSession:
  val Property: String = "zipx.release"

  def active(props: collection.Map[String, String]): Boolean = props.contains(Property)

/** `v1.4.2` in a single-row catalog, `<identity>/v1.4.2` otherwise. */
object ReleaseTag:
  def of(row: PublishedRow, catalog: ShipIndex): String =
    if catalog.byIdentity.sizeIs == 1 then s"v${row.version}" else s"${row.identity}/v${row.version}"

/** What started a `zipx-release.yml` run: a pushed tag, or a dispatch that releases every unreleased row. */
enum ReleaseRequest:
  case Tagged(tag: String)
  case AllUnreleased

object ReleaseRequest:
  def fromRef(ref: String): Either[ReleaseError, ReleaseRequest] = ref match
    case s"refs/tags/$tag" if tag.nonEmpty        => Right(Tagged(tag))
    case s"refs/heads/$branch" if branch.nonEmpty => Right(AllUnreleased)
    case other                                    => Left(ReleaseError.UnknownRef(other))

enum ReleaseError:
  case NoRows
  case UnknownRef(ref: String)
  case UnknownTag(tag: String, tags: List[String])
  case TagMismatch(tag: String, row: PublishedRow, expected: String)
  case AlreadyReleased(row: PublishedRow)
  case PartiallyReleased(row: PublishedRow, missing: ::[Gav])
  case RegistryUnreachable(row: PublishedRow, detail: String)
  case NothingToRelease

  def message: String = this match
    case NoRows                => "zipx-release.yml releases Ship / ShipGroup rows, and the catalog has none"
    case UnknownRef(ref)       => s"'$ref' is neither a tag nor a branch"
    case UnknownTag(tag, tags) =>
      s"tag $tag names no catalog row; this catalog releases ${tags.mkString(", ")}"
    case TagMismatch(tag, row, expected) =>
      s"tag $tag does not match ${Modver.describe(row)} ${row.version}; tag $expected, or move the row first"
    case AlreadyReleased(row) =>
      s"${Modver.describe(row)} ${row.version} is already released; move the row to release again"
    case PartiallyReleased(row, missing) =>
      val gavs = missing.map(g => s"${g.organization}:${g.artifact}:${g.version}").mkString(", ")
      s"${Modver.describe(row)} ${row.version} is partly released; missing $gavs"
    case RegistryUnreachable(row, detail) =>
      s"cannot tell whether ${Modver.describe(row)} ${row.version} is released: $detail"
    case NothingToRelease => "every row's catalog number is already released"
end ReleaseError

final case class ReleaseEntry(row: PublishedRow, tag: String)

/** The rows one run releases, in dependency order: what was asked for plus its unreleased in-repo upstream rows. */
final case class ReleasePlan(entries: ::[ReleaseEntry]):

  /** The publishing projects the plan's rows own, JS / Native platform rows included, in build order. */
  def projects(graph: ModuleGraph, catalog: ShipIndex): List[ModuleId] =
    val rows = entries.map(_.row).toSet
    graph.topologicalSort.flatMap(graph.get).collect {
      case node if node.publishes && catalog.rowFor(node.matrixRoot).exists(rows.contains) => node.id
    }

object ReleasePlan:

  def plan(
      request: ReleaseRequest,
      catalog: ShipIndex,
      graph: ModuleGraph,
      status: PublishedRow => Either[ReleaseError, RowStatus],
  ): Either[ReleaseError, ReleasePlan] =
    val rows                     = inBuildOrder(catalog, graph)
    def tagOf(row: PublishedRow) = ReleaseTag.of(row, catalog)
    for
      _        <- Either.cond(rows.nonEmpty, (), ReleaseError.NoRows)
      statuses <- rows.foldLeft[Either[ReleaseError, Map[PublishedRow, RowStatus]]](Right(Map.empty)) { (acc, row) =>
        acc.flatMap(known => status(row).map(s => known + (row -> s)))
      }
      statusOf = statuses.withDefaultValue(RowStatus.Unreleased)
      requested <- request match
        case ReleaseRequest.Tagged(tag)   => byTag(tag, rows, tagOf).flatMap(releasable(_, statusOf))
        case ReleaseRequest.AllUnreleased => unreleased(rows, statusOf)
      closure <- upstream(requested, catalog, graph, statusOf)
      entries <- rows.filter(closure.contains).map(r => ReleaseEntry(r, tagOf(r))) match
        case head :: tail => Right(::(head, tail))
        case Nil          => Left(ReleaseError.NothingToRelease)
    yield ReleasePlan(entries)
    end for
  end plan

  private def inBuildOrder(catalog: ShipIndex, graph: ModuleGraph): List[PublishedRow] =
    val byOrder = graph.topologicalSort.flatMap(graph.get).flatMap(n => catalog.rowFor(n.matrixRoot)).distinct
    byOrder ++ catalog.byIdentity.values.filterNot(byOrder.contains).toList.sortBy(_.identity)

  private def byTag(
      tag: String,
      rows: List[PublishedRow],
      tagOf: PublishedRow => String,
  ): Either[ReleaseError, PublishedRow] =
    rows.find(tagOf(_) == tag).toRight {
      val named = tag match
        case s"$identity/v$_" => rows.find(_.identity == identity)
        case s"v$_"           =>
          rows match
            case only :: Nil => Some(only)
            case _           => None
        case _ => None
      named.fold(ReleaseError.UnknownTag(tag, rows.map(tagOf)))(row => ReleaseError.TagMismatch(tag, row, tagOf(row)))
    }

  private def releasable(
      row: PublishedRow,
      statusOf: PublishedRow => RowStatus,
  ): Either[ReleaseError, List[PublishedRow]] =
    statusOf(row) match
      case RowStatus.Unreleased       => Right(List(row))
      case RowStatus.Released         => Left(ReleaseError.AlreadyReleased(row))
      case RowStatus.Partial(missing) => Left(ReleaseError.PartiallyReleased(row, missing))

  private def unreleased(
      rows: List[PublishedRow],
      statusOf: PublishedRow => RowStatus,
  ): Either[ReleaseError, List[PublishedRow]] =
    rows.foldLeft[Either[ReleaseError, List[PublishedRow]]](Right(Nil)) { (acc, row) =>
      acc.flatMap { picked =>
        statusOf(row) match
          case RowStatus.Unreleased       => Right(picked :+ row)
          case RowStatus.Released         => Right(picked)
          case RowStatus.Partial(missing) => Left(ReleaseError.PartiallyReleased(row, missing))
      }
    }

  /** A released artifact's POM names its in-repo dependencies at their catalog numbers, so every unreleased row the
    * requested rows reach must release with them.
    */
  private def upstream(
      requested: List[PublishedRow],
      catalog: ShipIndex,
      graph: ModuleGraph,
      statusOf: PublishedRow => RowStatus,
  ): Either[ReleaseError, Set[PublishedRow]] =
    def dependencies(row: PublishedRow): Set[PublishedRow] =
      graph.nodes
        .filter(n => catalog.rowFor(n.matrixRoot).contains(row))
        .flatMap(_.dependsOn)
        .flatMap(graph.get)
        .flatMap(n => catalog.rowFor(n.matrixRoot))
        .filterNot(_ == row)
        .toSet

    @annotation.tailrec
    def reach(frontier: Set[PublishedRow], seen: Set[PublishedRow]): Set[PublishedRow] =
      val next = frontier.flatMap(dependencies) -- seen
      if next.isEmpty then seen else reach(next, seen ++ next)

    val reached = reach(requested.toSet, requested.toSet)
    (reached -- requested).toList
      .sortBy(_.identity)
      .foldLeft[Either[ReleaseError, Set[PublishedRow]]](
        Right(requested.toSet)
      ) { (acc, row) =>
        acc.flatMap { closure =>
          statusOf(row) match
            case RowStatus.Unreleased       => Right(closure + row)
            case RowStatus.Released         => Right(closure)
            case RowStatus.Partial(missing) => Left(ReleaseError.PartiallyReleased(row, missing))
        }
      }
  end upstream
end ReleasePlan
