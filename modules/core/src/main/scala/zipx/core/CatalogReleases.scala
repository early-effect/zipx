package zipx.core

/** How a build crosses its catalog rows: the full and binary Scala versions, and sbt's binary version for plugins. */
final case class CatalogCrossing(scala: String, scalaBinary: String, sbtBinary: String)

object CatalogCrossing:
  /** From a catalog's `scala` and `sbt` versions, the way sbt derives the binary versions. */
  def of(scala: String, sbt: String): CatalogCrossing =
    val binary = scala.split('.').toList match
      case "3" :: _            => "3"
      case major :: minor :: _ => s"$major.$minor"
      case _                   => scala
    CatalogCrossing(scala, binary, sbt.takeWhile(_ != '.'))

/** A Scala platform a library can be published for, with the infix sbt puts before the Scala version. */
enum ScalaPlatform(val infix: String):
  case Jvm     extends ScalaPlatform("")
  case ScalaJs extends ScalaPlatform("sjs1_")
  case Native  extends ScalaPlatform("native0.5_")

/** The versions one published artifact lists, or none when the repository has no artifact by that name. */
trait ReleaseLookup:
  def versions(coord: ZipxCoord, artifact: String): Either[String, Option[List[String]]]

/** Every name a catalog row can be published under, one per platform, crossed the way sbt crosses it. */
object PublishedNames:
  def of(coord: ZipxCoord, crossing: CatalogCrossing): List[String] =
    coord match
      case lib: Lib =>
        lib.cross match
          case Cross.Java   => List(lib.artifact)
          case Cross.Binary =>
            ScalaPlatform.values.toList.map(p => s"${lib.artifact}_${p.infix}${crossing.scalaBinary}")
          case Cross.Full => ScalaPlatform.values.toList.map(p => s"${lib.artifact}_${p.infix}${crossing.scala}")
      case plugin: Plugin => List(s"${plugin.artifact}_sbt${crossing.sbtBinary}_${crossing.scalaBinary}")
end PublishedNames

/** What a catalog update writes, and the family members a sibling holds back. */
final case class CatalogUpdates(bumps: List[DepBump], held: List[FamilyHold])

/** A row that could move further than its `.mod` family: the family moves together, on its literal. */
final case class FamilyHold(row: ZipxCoord, alone: String, together: Option[String]):
  def message: String =
    val family = together.fold("its family has no newer version in common")(to => s"its family moves together to $to")
    s"${row.artifact} could move to $alone alone; $family. Give it its own Lib row to take $alone."

/** What a row can move to, read off the repository instead of assumed: total over platform-only rows and families. */
object CatalogReleases:

  /** Versions `coord` can take. A row is published for the platforms whose artifact lists its current version, and a
    * candidate must be published for every one of them, so a JS-only row is read off its `_sjs1_3` artifact and a row
    * published for JVM and JS never moves to a version only one of them has.
    */
  def available(coord: ZipxCoord, crossing: CatalogCrossing, lookup: ReleaseLookup): Either[String, List[String]] =
    PublishedNames
      .of(coord, crossing)
      .foldLeft[Either[String, List[List[String]]]](Right(Nil)) { (found, name) =>
        found.flatMap(acc => lookup.versions(coord, name).map(acc ++ _.toList))
      }
      .map { published =>
        val platforms = published.filter(_.contains(coord.version)) match
          case Nil      => published
          case matching => matching
        intersect(platforms)
      }

  /** The versions every list holds, in the first list's order. No lists, no versions. */
  def intersect(lists: List[List[String]]): List[String] =
    lists match
      case Nil           => Nil
      case first :: rest => first.filter(version => rest.forall(_.contains(version)))
end CatalogReleases
