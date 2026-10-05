package zipx.core

/** A repository holding exactly `published`: artifact name, as sbt crosses it, to the versions it lists. */
final case class FakeReleases(published: Map[String, List[String]]) extends ReleaseLookup:
  def versions(coord: ZipxCoord, artifact: String): Either[String, Option[List[String]]] =
    Right(published.get(artifact))

object FakeReleases:
  val crossing: CatalogCrossing = CatalogCrossing("3.9.0", "3", "2")

  def of(published: (String, List[String])*): FakeReleases = FakeReleases(published.toMap)
