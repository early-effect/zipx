package zipx.core

import neotype.Subtype
import scala.annotation.targetName

type GroupId = GroupId.Type
object GroupId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "a group id must be non-empty"

/** Without the Scala cross suffix. */
type ArtifactId = ArtifactId.Type
object ArtifactId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "an artifact id must be non-empty"

type DepVersion = DepVersion.Type
object DepVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "a version must be non-empty"

/** `scalaVersion`; a `ThisBuild` setting still matches via delegation. */
type ScalaVersion = ScalaVersion.Type
object ScalaVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "a Scala version must be non-empty"

/** `sbt.version` in `project/build.properties`. */
type SbtVersion = SbtVersion.Type
object SbtVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "an sbt version must be non-empty"

enum Cross:
  /** `%%` (Scala binary). */
  case Binary

  /** `%` (Java / uncross). */
  case Java

  /** `%%%` (Scala.js full). */
  case Full

/** `excludeAll(ExclusionRule(...))` on a plugin or library ModuleID. */
final case class ZipxExclude(organization: GroupId, artifact: Option[ArtifactId] = None)

object ZipxExclude:
  inline def org(inline organization: String): ZipxExclude =
    ZipxExclude(GroupId(organization), None)

  inline def org(inline organization: String, inline artifact: String): ZipxExclude =
    ZipxExclude(GroupId(organization), Some(ArtifactId(artifact)))

/** One row in `zipxVersions`: a library (`Lib`) or an sbt plugin (`Plugin`). */
sealed trait ZipxCoord:
  def group: GroupId
  def artifact: ArtifactId
  def version: DepVersion

/** How a catalog val becomes rows. A plugin adds a given for its own bundle type so those vals are collected too. */
trait AsCoords[A]:
  def coords(value: A): Seq[ZipxCoord]

object AsCoords:
  def apply[A](using ev: AsCoords[A]): AsCoords[A] = ev

  given ofCoord[A <: ZipxCoord]: AsCoords[A] = a => Seq(a)

trait AsPins[A]:
  def pins(value: A): Seq[Pin]

object AsPins:
  def apply[A](using ev: AsPins[A]): AsPins[A] = ev

  given ofPin: AsPins[Pin] = p => Seq(p)

trait AsActions[A]:
  def actions(value: A): Seq[Action]

object AsActions:
  def apply[A](using ev: AsActions[A]): AsActions[A] = ev

  given ofAction: AsActions[Action] = a => Seq(a)

trait AsShips[A]:
  def ships(value: A): Seq[PublishedRow]

object AsShips:
  def apply[A](using ev: AsShips[A]): AsShips[A] = ev

  given ofRow[A <: PublishedRow]: AsShips[A] = a => Seq(a)

type ShipGroupName = ShipGroupName.Type
object ShipGroupName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "a ship group name must be non-empty"

/** The number a [[Ship]] / [[ShipGroup]] row releases next. */
type ReleaseVersion = ReleaseVersion.Type
object ReleaseVersion extends Subtype[String]:
  inline val Shape = """\d+\.\d+\.\d+"""

  override inline def validate(input: String): Boolean | String =
    if input.matches(Shape) then true
    else s"a release number is major.minor.patch, got '$input'"

  private final case class Parts(major: BigInt, minor: BigInt, patch: BigInt)

  private def parts(version: ReleaseVersion): Parts =
    val (major, afterMajor) = version.span(_ != '.')
    val (minor, afterMinor) = afterMajor.drop(1).span(_ != '.')
    Parts(BigInt(major), BigInt(minor), BigInt(afterMinor.drop(1)))

  given ordering: scala.math.Ordering[ReleaseVersion] = scala.math.Ordering.by { version =>
    val p = parts(version)
    (p.major, p.minor, p.patch)
  }

  extension (version: ReleaseVersion)
    def bump(by: ReleaseBump): ReleaseVersion =
      val p = parts(version)
      unsafeMake(by match
        case ReleaseBump.Patch => s"${p.major}.${p.minor}.${p.patch + 1}"
        case ReleaseBump.Minor => s"${p.major}.${p.minor + 1}.0"
        case ReleaseBump.Major => s"${p.major + 1}.0.0")

    def isInitialDevelopment: Boolean = parts(version).major == 0
end ReleaseVersion

enum ReleaseBump:
  case Patch, Minor, Major

  /** The word `zipxModverBump` accepts. */
  def token: String = this match
    case Patch => "patch"
    case Minor => "minor"
    case Major => "major"

  /** Does not consult MiMa or the version scheme. */
  def next(version: ReleaseVersion): ReleaseVersion = version.bump(this)
end ReleaseBump

object ReleaseBump:
  def fromToken(raw: String): Option[ReleaseBump] =
    raw.toLowerCase match
      case "patch" => Some(Patch)
      case "minor" => Some(Minor)
      case "major" => Some(Major)
      case _       => None

  def of(kind: BumpKind): Option[ReleaseBump] = kind match
    case BumpKind.Patch                      => Some(Patch)
    case BumpKind.Minor                      => Some(Minor)
    case BumpKind.Major                      => Some(Major)
    case BumpKind.None | BumpKind.PreRelease => None
end ReleaseBump

/** One outbound version row: a lone [[Ship]] or a [[ShipGroup]] whose members share a number. */
sealed trait PublishedRow:
  def version: ReleaseVersion

  def label: String

  def identity: String

  /** Matrix roots this row owns. */
  def memberRoots: List[ModuleId]

  def at(version: ReleaseVersion): PublishedRow
end PublishedRow

final case class Ship(id: ModuleId, version: ReleaseVersion) extends PublishedRow:
  def label: String                             = "Ship"
  def identity: String                          = id
  def memberRoots: List[ModuleId]               = List(id)
  def at(version: ReleaseVersion): PublishedRow = copy(version = version)

object Ship:
  /** `@targetName` plus `new` because [[ModuleId]] / [[ReleaseVersion]] erase to `String` and would clash with the
    * case-class `apply`.
    */
  @targetName("fromLiterals")
  inline def apply(inline id: String, inline version: String): Ship =
    new Ship(ModuleId(id), ReleaseVersion(version))

final case class ShipGroup(
    name: ShipGroupName,
    version: ReleaseVersion,
    members: List[ModuleId],
) extends PublishedRow:
  def label: String                             = "ShipGroup"
  def identity: String                          = name
  def memberRoots: List[ModuleId]               = members
  def at(version: ReleaseVersion): PublishedRow = copy(version = version)

object ShipGroup:
  /** Varargs member ids cannot use inline [[ModuleId.apply]]. */
  inline def apply(inline name: String, inline version: String)(members: String*): ShipGroup =
    new ShipGroup(
      ShipGroupName(name),
      ReleaseVersion(version),
      members.iterator.map(ModuleId.unsafeMake).toList,
    )

/** Stricter than [[zipx.workflow.ActionRef]], which still allows tags. */
type GitSha = GitSha.Type
object GitSha extends Subtype[String]:
  inline val Hex40 = "[0-9a-fA-F]{40}"

  override inline def validate(input: String): Boolean | String =
    if input.matches(Hex40) then true
    else s"a git SHA must be 40 hex characters, got '$input'"

final case class Lib(
    group: GroupId,
    artifact: ArtifactId,
    version: DepVersion,
    cross: Cross = Cross.Binary,
    config: Option[String] = None,
    excludes: List[ZipxExclude] = Nil,
    /** Pre-mod artifact. Set by [[mod]] so [[fromGraph]] can name the probe GAV. */
    family: Option[ArtifactId] = None,
    /** When set, ModuleID revision comes from the selected family GAV on the probe graph. */
    alignTo: Option[ArtifactId] = None,
) extends ZipxCoord:
  inline def mod(inline artifact: String): Lib =
    copy(artifact = ArtifactId(artifact), family = Some(this.artifact))
  def test: Lib                        = copy(config = Some("test"))
  def java: Lib                        = copy(cross = Cross.Java)
  def full: Lib                        = copy(cross = Cross.Full)
  def excluding(ex: ZipxExclude*): Lib = copy(excludes = excludes ++ ex.toList)
  def fromGraph: Lib                   = copy(alignTo = Some(family.getOrElse(artifact)))
  def isAligned: Boolean               = alignTo.nonEmpty
  def coordinate: LibCoordinate        = LibCoordinate(group, artifact)
end Lib

/** A library as `libraryDependencies` names it: group and base artifact, before any cross suffix. */
final case class LibCoordinate(group: GroupId, artifact: ArtifactId)

object Lib:
  /** Three args would pick this overload even for typed values (neotype's `Conversion` makes `GroupId` a `String`), so
    * the body passes every default to reach the case-class constructor.
    */
  inline def apply(inline group: String, inline artifact: String, inline version: String): Lib =
    Lib(GroupId(group), ArtifactId(artifact), DepVersion(version), Cross.Binary, None, Nil, None, None)

final case class Plugin(
    group: GroupId,
    artifact: ArtifactId,
    version: DepVersion,
    excludes: List[ZipxExclude] = Nil,
) extends ZipxCoord:
  def excluding(ex: ZipxExclude*): Plugin = copy(excludes = excludes ++ ex.toList)

object Plugin:
  /** See [[Lib.apply]]: pass `Nil` so this does not recurse into the String factory. */
  inline def apply(inline group: String, inline artifact: String, inline version: String): Plugin =
    Plugin(GroupId(group), ArtifactId(artifact), DepVersion(version), Nil)

/** A declared `libraryDependencies` GAV, compared against [[Lib]] rows (config is ignored). */
final case class DeclaredGav(group: String, artifact: String, revision: String):
  def render: String = s"$group:$artifact:$revision"

final case class DepBump(coord: ZipxCoord, bump: BumpKind, to: String):
  def group: String    = coord.group
  def artifact: String = coord.artifact
  def from: String     = coord.version
  def ctor: String     =
    coord match
      case _: Lib    => "Lib"
      case _: Plugin => "Plugin"

/** A non-Maven catalog row: CDN / checksum / vendor pin. Not a [[ZipxCoord]]. */
final case class Pin(
    feed: PinFeedName,
    id: String,
    version: DepVersion,
    sha256: Option[String] = None,
    purl: Option[Purl] = None,
):
  def current: String = version: String

  def toPinnedDep: PinnedDep = PinnedDep(id, current, purl)

  def bumped(candidate: PinCandidate): Either[String, Pin] =
    DepVersion.make(candidate.version).map { ver =>
      copy(
        version = ver,
        sha256 = candidate.sha256.orElse(sha256),
        purl = candidate.purl.orElse(purl),
      )
    }
end Pin

object Pin:
  /** Extra args select the case-class constructor so this does not recurse. */
  inline def apply(inline feed: String, inline id: String, inline version: String): Pin =
    Pin(PinFeedName(feed), id, DepVersion(version), None, None)

  /** Empty strings become `None`. */
  inline def apply(
      inline feed: String,
      inline id: String,
      inline version: String,
      inline sha256: String,
      inline purl: String,
  ): Pin =
    Pin(
      PinFeedName(feed),
      id,
      DepVersion(version),
      Option.when(sha256.nonEmpty)(sha256),
      Option.when(purl.nonEmpty)(Purl(purl)),
    )
end Pin

/** A GitHub Action catalog row; `name` is `owner/repo` or `owner/repo/path`. */
final case class Action(name: String, version: DepVersion, sha: GitSha):
  def current: String = version: String

  def toRef: Either[String, zipx.workflow.ActionRef] =
    zipx.workflow.ActionRef.make(s"$name@${sha: String}")

  def bumped(toVersion: String, toSha: String): Either[String, Action] =
    Action.make(name, toVersion, toSha)
end Action

object Action:
  /** `@targetName` because `DepVersion` / `GitSha` erase to `String` and would clash with the case-class `apply`; `new`
    * so this does not recurse. Catalogs name `sha =` so apply rewrites version and SHA together.
    */
  @targetName("fromLiterals")
  inline def apply(inline name: String, inline version: String, inline sha: String): Action =
    new Action(name, DepVersion(version), GitSha(sha))

  def make(name: String, version: String, sha: String): Either[String, Action] =
    val trimmed = name.trim
    if trimmed.isEmpty || trimmed.contains('@') then
      Left(s"zipx: Action name must be owner/repo or owner/repo/path, got '$name'")
    else
      for
        ver  <- DepVersion.make(version)
        gsha <- GitSha.make(sha)
        _    <- zipx.workflow.ActionRef.make(s"$trimmed@$sha")
      yield new Action(trimmed, ver, gsha)
  end make
end Action

final case class ActionBump(action: Action, bump: BumpKind, toVersion: String, toSha: String)
