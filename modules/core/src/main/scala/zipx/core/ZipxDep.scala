package zipx.core

import neotype.Subtype

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
