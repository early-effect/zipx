package zipx.core

final case class CatalogCrossing(scala: String, scalaBinary: String, sbtBinary: String)

object CatalogCrossing:
  def of(scala: String, sbt: String): CatalogCrossing =
    val binary = scala.split('.').toList match
      case "3" :: _            => "3"
      case major :: minor :: _ => s"$major.$minor"
      case _                   => scala
    CatalogCrossing(scala, binary, sbt.takeWhile(_ != '.'))

enum ScalaPlatform(val infix: String):
  case Jvm     extends ScalaPlatform("")
  case ScalaJs extends ScalaPlatform("sjs1_")
  case Native  extends ScalaPlatform("native0.5_")

trait ReleaseLookup:
  /** `None` when the repository has no artifact by that name. */
  def versions(coord: ZipxCoord, artifact: String): Either[String, Option[List[String]]]

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

final case class CatalogUpdates(bumps: List[DepBump], held: List[FamilyHold])

final case class FamilyHold(row: ZipxCoord, alone: String, together: Option[String]):
  def message: String =
    val family = together.fold("its family has no newer version in common")(to => s"its family moves together to $to")
    s"${row.artifact} could move to $alone alone; $family. Give it its own Lib row to take $alone."

object CatalogReleases:

  /** A row's platforms are the artifacts that list its current version, and a candidate must be on all of them. */
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

  /** In the first list's order. */
  def intersect(lists: List[List[String]]): List[String] =
    lists match
      case Nil           => Nil
      case first :: rest => first.filter(version => rest.forall(_.contains(version)))
end CatalogReleases
