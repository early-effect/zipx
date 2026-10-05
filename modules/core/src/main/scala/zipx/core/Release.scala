package zipx.core

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

type PullRequestNumber = PullRequestNumber.Type
object PullRequestNumber extends neotype.Subtype[Int]:
  override inline def validate(value: Int): Boolean | String =
    if value > 0 then true else "a pull request number is positive"

enum BuildSession:
  case Development
  case SnapshotPublish
  case PullRequestSnapshot(pr: PullRequestNumber)
  case Release

  def id: String = this match
    case Development             => "development"
    case SnapshotPublish         => "snapshot"
    case PullRequestSnapshot(pr) => s"pr-$pr"
    case Release                 => "release"

  /** Stable across commits, so action-cache digests hold. A publish session's `version` is [[artifactVersion]]. */
  def versionOf(row: PublishedRow): String = this match
    case Release => row.version
    case _       => s"${row.version}${BuildSession.CompileSuffix}"

  /** The `version` a session sets; a snapshot session publishes the git id. */
  def artifactVersion(row: PublishedRow, props: collection.Map[String, String]): Either[SnapshotRevisionError, String] =
    this match
      case Release | Development                    => Right(versionOf(row))
      case SnapshotPublish | PullRequestSnapshot(_) => SnapshotPublishRevision.revision(row, props)

  /** Central validates docs on a release only; scaladoc is the slow part of a snapshot publish. */
  def publishesDocs: Boolean = this match
    case SnapshotPublish | PullRequestSnapshot(_) => false
    case Development | Release                    => true
end BuildSession

object BuildSession:
  /** Compiled by development and test sessions; never published. */
  val CompileSuffix: String = "-ci"

  /** A JVM property, because sbt drops session settings when `++` / `+` switch Scala versions. */
  val Property: String = "zipx.session"

  /** The rows a release session publishes, which names its registry deployment. */
  val ReleaseNameProperty: String = "zipx.release.name"

  def of(props: collection.Map[String, String]): Either[UnknownBuildSession, BuildSession] =
    props.get(Property) match
      case None                => Right(Development)
      case Some("development") => Right(Development)
      case Some("snapshot")    => Right(SnapshotPublish)
      case Some("release")     => Right(Release)
      case Some(id @ s"pr-$n") =>
        n.toIntOption
          .flatMap(PullRequestNumber.make(_).toOption)
          .map(PullRequestSnapshot(_))
          .toRight(UnknownBuildSession(id))
      case Some(id) => Left(UnknownBuildSession(id))
end BuildSession

final case class UnknownBuildSession(id: String):
  def message: String =
    s"-D${BuildSession.Property}=$id is not one of development, snapshot, pr-<number>, release"

/** A multi-row catalog tags `<identity>/v<n>` because a bare `v*` tag there is the image tag `ci.yml` builds on. */
enum TagScheme:
  case Bare
  case PerRow

  def tag(row: PublishedRow): String = this match
    case Bare   => s"v${row.version}"
    case PerRow => s"${row.identity}/v${row.version}"

  def pattern: String = this match
    case Bare   => "v*"
    case PerRow => "*/v*"
end TagScheme

object TagScheme:
  def of(catalog: ShipIndex): TagScheme = if catalog.byIdentity.sizeIs == 1 then Bare else PerRow

enum ReleaseRequest:
  case Tagged(tag: String)
  case AllUnreleased

  /** Catalog identities, in the order the dispatch named them. Unreleased in-repo upstreams still ride along. */
  case Ships(identities: ::[String])

object ReleaseRequest:

  /** The Run workflow default: every unreleased ship. */
  val All: String = "all"

  def fromRef(ref: String): Either[ReleaseError, ReleaseRequest] =
    val raw = ref.trim
    raw match
      case s"refs/tags/$tag" if tag.nonEmpty        => Right(Tagged(tag))
      case s"refs/heads/$branch" if branch.nonEmpty => Right(AllUnreleased)
      case other if other.startsWith("refs/")       => Left(ReleaseError.UnknownRef(other))
      case All                                      => Right(AllUnreleased)
      case ""                                       => Left(ReleaseError.UnknownRef(ref))
      case other                                    => shipsOf(other)

  private def shipsOf(raw: String): Either[ReleaseError, ReleaseRequest] =
    raw.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList.distinct match
      case head :: tail => Right(Ships(::(head, tail)))
      case Nil          => Left(ReleaseError.UnknownRef(raw))
end ReleaseRequest

enum ReleaseError:
  case NoRows
  case UnknownRef(ref: String)
  case UnknownShip(name: String, ships: List[String])
  case UnknownTag(tag: String, tags: List[String])
  case TagMismatch(tag: String, row: PublishedRow, expected: String)
  case AlreadyReleased(row: PublishedRow)
  case PartiallyReleased(row: PublishedRow, missing: ::[Gav])
  case RegistryUnreachable(row: PublishedRow, detail: String)
  case NothingToRelease
  case SnapshotPinned(dependencies: ::[String])

  def message: String = this match
    case NoRows                   => "zipx-release.yml releases Ship / ShipGroup rows, and the catalog has none"
    case UnknownRef(ref)          => s"'$ref' is not a tag, a branch, all, or a comma-separated list of ships"
    case UnknownShip(name, ships) =>
      val known = if ships.isEmpty then "none" else ships.mkString(", ")
      s"ship '$name' is not in the catalog; this catalog releases $known"
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
    case NothingToRelease             => "every row's catalog number is already released"
    case SnapshotPinned(dependencies) =>
      s"a release cannot depend on a snapshot: ${dependencies.mkString(", ")}; release those first and pin the release"
end ReleaseError

final case class ReleaseEntry(row: PublishedRow, tag: String)

final case class ReleasePlan(entries: ::[ReleaseEntry]):

  def projects(graph: ModuleGraph, catalog: ShipIndex): List[ModuleId] =
    val rows = entries.map(_.row).toSet
    graph.topologicalSort.flatMap(graph.get).collect {
      case node if node.publishes && catalog.rowFor(node.matrixRoot).exists(rows.contains) => node.id
    }

object ReleasePlan:

  /** `dependencies` are `group:artifact:revision` of what the released projects declare. */
  def refuseSnapshots(dependencies: List[String]): Either[ReleaseError, Unit] =
    dependencies.filter(SnapshotPinAdvice.blocksRelease).distinct.sorted match
      case head :: tail => Left(ReleaseError.SnapshotPinned(::(head, tail)))
      case Nil          => Right(())

  def plan(
      request: ReleaseRequest,
      catalog: ShipIndex,
      graph: ModuleGraph,
      status: PublishedRow => Either[ReleaseError, RowStatus],
  ): Either[ReleaseError, ReleasePlan] =
    val rows                     = inBuildOrder(catalog, graph)
    def tagOf(row: PublishedRow) = TagScheme.of(catalog).tag(row)
    for
      _        <- Either.cond(rows.nonEmpty, (), ReleaseError.NoRows)
      statuses <- rows.foldLeft[Either[ReleaseError, Map[PublishedRow, RowStatus]]](Right(Map.empty)) { (acc, row) =>
        acc.flatMap(known => status(row).map(s => known + (row -> s)))
      }
      statusOf = statuses.withDefaultValue(RowStatus.Unreleased)
      requested <- request match
        case ReleaseRequest.Tagged(tag)   => byTag(tag, rows, tagOf).flatMap(releasable(_, statusOf))
        case ReleaseRequest.AllUnreleased => unreleased(rows, statusOf)
        case ReleaseRequest.Ships(names)  => selected(names, rows, statusOf)
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

  private def selected(
      names: ::[String],
      rows: List[PublishedRow],
      statusOf: PublishedRow => RowStatus,
  ): Either[ReleaseError, List[PublishedRow]] =
    val known = rows.map(_.identity).toSet
    names.toList.filterNot(known.contains) match
      case head :: _ => Left(ReleaseError.UnknownShip(head, rows.map(_.identity)))
      case Nil       =>
        val wanted = names.toSet
        rows
          .filter(row => wanted.contains(row.identity))
          .foldLeft[Either[ReleaseError, List[PublishedRow]]](Right(Nil)) { (acc, row) =>
            acc.flatMap { picked =>
              statusOf(row) match
                case RowStatus.Unreleased       => Right(picked :+ row)
                case RowStatus.Released         => Left(ReleaseError.AlreadyReleased(row))
                case RowStatus.Partial(missing) => Left(ReleaseError.PartiallyReleased(row, missing))
            }
          }
    end match
  end selected

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

  /** A release POM names in-repo dependencies at their catalog numbers, so their unreleased rows release with it. */
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
