package zipx.core

import java.time.Duration

/** Versions from Maven-style `maven-metadata.xml`: Maven Central, then the sbt plugin repo for [[Plugin]] rows. */
object MavenMetadata:

  val releases: ReleaseLookup = lookup(fetchVersions)

  private[core] def lookup(fetch: String => Either[String, Option[List[String]]]): ReleaseLookup =
    (coord, artifact) => firstHit(metadataUrls(coord, artifact), fetch)

  /** Central for every coord. The sbt plugin repo only for [[Plugin]] rows, and only after Central misses. */
  private[core] def metadataUrls(coord: ZipxCoord, artifact: String): List[String] =
    val rel     = s"${(coord.group: String).replace('.', '/')}/$artifact/maven-metadata.xml"
    val central = s"https://repo1.maven.org/maven2/$rel"
    coord match
      case _: Lib    => List(central)
      case _: Plugin => List(central, s"https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/$rel")

  private def firstHit[A](urls: List[String], fetch: String => Either[String, Option[A]]): Either[String, Option[A]] =
    urls match
      case Nil         => Right(None)
      case url :: rest =>
        fetch(url) match
          case Right(Some(found)) => Right(Some(found))
          case Right(None)        => firstHit(rest, fetch)
          case Left(err)          => Left(err)

  private def fetchVersions(url: String): Either[String, Option[List[String]]] =
    HttpLookup.get(url, timeout = Duration.ofSeconds(15)) match
      case Left(err)                                            => Left(s"lookup $url: $err")
      case Right(res) if res.status == 200                      => Right(Some(versionsIn(res.body)))
      case Right(res) if res.status == 404 || res.status == 410 => Right(None)
      case Right(res)                                           => Left(s"lookup $url: HTTP ${res.status}")

  def versionsIn(xml: String): List[String] =
    raw"<version>([^<]+)</version>".r.findAllMatchIn(xml).map(_.group(1).trim).toList

  def latestRelease(xml: String): Option[ReleaseVersion] =
    versionsIn(xml).flatMap(ReleaseVersion.make(_).toOption).maxOption(using ReleaseVersion.ordering)
end MavenMetadata
