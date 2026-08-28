package zipx.core

import neotype.Subtype
import scala.annotation.targetName

/** Maven / sbt group id (`dev.zio`). */
type GroupId = GroupId.Type
object GroupId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "a group id must be non-empty"

/** Maven / sbt artifact id without a Scala suffix (`zio`, not `zio_3`). */
type ArtifactId = ArtifactId.Type
object ArtifactId extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "an artifact id must be non-empty"

/** A version literal (`2.1.26`, `2.0.6`, `2.1.25-M26`). */
type DepVersion = DepVersion.Type
object DepVersion extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "a version must be non-empty"

/** `ThisBuild / scalaVersion` literal. */
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

/** How a catalog val becomes rows. `Lib` and `Plugin` share [[AsCoords.ofCoord]]; a plugin (splice, a company catalog)
  * adds a given for its own bundle type so those vals are collected too.
  */
trait AsCoords[A]:
  def coords(value: A): Seq[ZipxCoord]

object AsCoords:
  def apply[A](using ev: AsCoords[A]): AsCoords[A] = ev

  given ofCoord[A <: ZipxCoord]: AsCoords[A] = a => Seq(a)

/** How a catalog val becomes pin rows. [[Pin]] has one given; a plugin bundle adds its own. */
trait AsPins[A]:
  def pins(value: A): Seq[Pin]

object AsPins:
  def apply[A](using ev: AsPins[A]): AsPins[A] = ev

  given ofPin: AsPins[Pin] = p => Seq(p)

/** How a catalog val becomes GitHub Action rows. [[Action]] has one given; a plugin bundle adds its own. */
trait AsActions[A]:
  def actions(value: A): Seq[Action]

object AsActions:
  def apply[A](using ev: AsActions[A]): AsActions[A] = ev

  given ofAction: AsActions[Action] = a => Seq(a)

/** How a catalog val becomes outbound version rows. [[Ship]] / [[ShipGroup]] have givens; a bundle can add its own. */
trait AsShips[A]:
  def ships(value: A): Seq[PublishedRow]

object AsShips:
  def apply[A](using ev: AsShips[A]): AsShips[A] = ev

  given ofRow[A <: PublishedRow]: AsShips[A] = a => Seq(a)

/** Identity of a [[ShipGroup]] (`foo` in `ShipGroup("foo", "1.4.2")(...)`). */
type ShipGroupName = ShipGroupName.Type
object ShipGroupName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.nonEmpty then true else "a ship group name must be non-empty"

/** One outbound version row: a lone [[Ship]] or a [[ShipGroup]] whose members share a number. */
sealed trait PublishedRow:
  def version: DepVersion

  /** `"Ship"` or `"ShipGroup"`, for comments and apply. */
  def label: String

  /** Project id or group name. */
  def identity: String

  /** Matrix roots this row owns. */
  def memberRoots: List[ModuleId]
end PublishedRow

final case class Ship(id: ModuleId, version: DepVersion) extends PublishedRow:
  def label: String               = "Ship"
  def identity: String            = id
  def memberRoots: List[ModuleId] = List(id)

object Ship:
  /** Catalog literal. `@targetName` plus `new` because [[ModuleId]] / [[DepVersion]] erase to `String` and would clash
    * with the case-class apply. Same pattern as [[Action.apply]] (`Lib` / `Plugin` dodge it with extra defaults; Ship
    * has none).
    */
  @targetName("fromLiterals")
  inline def apply(inline id: String, inline version: String): Ship =
    new Ship(ModuleId(id), DepVersion(version))

final case class ShipGroup(
    name: ShipGroupName,
    version: DepVersion,
    members: List[ModuleId],
) extends PublishedRow:
  def label: String               = "ShipGroup"
  def identity: String            = name
  def memberRoots: List[ModuleId] = members

object ShipGroup:
  /** Catalog literal. Member ids are runtime strings (`String*`), so they cannot use inline [[ModuleId.apply]]. */
  inline def apply(inline name: String, inline version: String)(members: String*): ShipGroup =
    new ShipGroup(
      ShipGroupName(name),
      DepVersion(version),
      members.iterator.map(ModuleId.unsafeMake).toList,
    )

/** A full git commit SHA (40 hex). Stricter than [[zipx.workflow.ActionRef]], which still allows tags. */
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
) extends ZipxCoord:
  inline def mod(inline artifact: String): Lib = copy(artifact = ArtifactId(artifact))
  def test: Lib                                = copy(config = Some("test"))
  def java: Lib                                = copy(cross = Cross.Java)
  def full: Lib                                = copy(cross = Cross.Full)
  def excluding(ex: ZipxExclude*): Lib         = copy(excludes = excludes ++ ex.toList)
end Lib

object Lib:
  /** String factory for catalog literals. The case-class `apply` is `(GroupId, ArtifactId, DepVersion, …defaults)`.
    * Passing only three args would pick *this* overload: neotype `Conversion` makes `GroupId` a `String`, and a 3-arg
    * method beats one that fills defaults. Supplying the defaults selects the case-class constructor.
    */
  inline def apply(inline group: String, inline artifact: String, inline version: String): Lib =
    Lib(GroupId(group), ArtifactId(artifact), DepVersion(version), Cross.Binary, None, Nil)

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
  /** Three-arg catalog literal. Extra args select the case-class constructor so this does not recurse. */
  inline def apply(inline feed: String, inline id: String, inline version: String): Pin =
    Pin(PinFeedName(feed), id, DepVersion(version), None, None)

  /** Catalog literal with checksum and PURL. Empty strings become None. */
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

/** A GitHub Action catalog row: `owner/repo` (or `owner/repo/path`), a version label, and a full commit SHA. */
final case class Action(name: String, version: DepVersion, sha: GitSha):
  def current: String = version: String

  def toRef: Either[String, zipx.workflow.ActionRef] =
    zipx.workflow.ActionRef.make(s"$name@${sha: String}")

  def bumped(toVersion: String, toSha: String): Either[String, Action] =
    Action.make(name, toVersion, toSha)
end Action

object Action:
  /** Catalog literal. `sha` is named so apply rewrites version and SHA together.
    *
    * `@targetName` because `DepVersion` / `GitSha` erase to `String` and would clash with the case-class apply. `new`
    * so this does not recurse into itself (same arity as the case-class apply; `Lib` / `Plugin` pass extra defaults
    * instead).
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
