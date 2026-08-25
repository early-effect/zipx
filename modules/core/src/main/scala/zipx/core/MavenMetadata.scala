package zipx.core

import java.time.Duration

/** Latest version from Maven-style `maven-metadata.xml` (Maven Central, then the sbt plugin repo for [[Plugin]] rows).
  */
object MavenMetadata:

  def latest(coord: ZipxCoord, scalaBin: String, sbtBin: String): Either[String, Option[String]] =
    latest(coord, scalaBin, sbtBin, PreRelease.Skip)

  def latest(
      coord: ZipxCoord,
      scalaBin: String,
      sbtBin: String,
      preRelease: PreRelease,
  ): Either[String, Option[String]] =
    firstHit(metadataUrls(coord, scalaBin, sbtBin), url => fetchLatest(url, preRelease))

  private[core] def latest(
      coord: ZipxCoord,
      scalaBin: String,
      sbtBin: String,
      fetch: String => Either[String, Option[String]],
  ): Either[String, Option[String]] =
    firstHit(metadataUrls(coord, scalaBin, sbtBin), fetch)

  /** Central for every coord. The sbt plugin repo only for [[Plugin]] rows, and only after Central misses. */
  private[core] def metadataUrls(coord: ZipxCoord, scalaBin: String, sbtBin: String): List[String] =
    val artifact  = mavenArtifact(coord, scalaBin, sbtBin)
    val groupPath = (coord.group: String).replace('.', '/')
    val rel       = s"$groupPath/$artifact/maven-metadata.xml"
    val central   = s"https://repo1.maven.org/maven2/$rel"
    coord match
      case _: Lib    => List(central)
      case _: Plugin => List(central, s"https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/$rel")

  private[core] def firstHit(
      urls: List[String],
      fetch: String => Either[String, Option[String]],
  ): Either[String, Option[String]] =
    urls match
      case Nil         => Right(None)
      case url :: rest =>
        fetch(url) match
          case Right(Some(v)) => Right(Some(v))
          case Right(None)    => firstHit(rest, fetch)
          case Left(err)      => Left(err)

  private def mavenArtifact(coord: ZipxCoord, scalaBin: String, sbtBin: String): String =
    coord match
      case l: Lib =>
        l.cross match
          case Cross.Java => l.artifact
          case _          => s"${l.artifact}_$scalaBin"
      case p: Plugin =>
        val sbtMaj = sbtBin.takeWhile(_ != '.')
        s"${p.artifact}_sbt${sbtMaj}_$scalaBin"

  private def fetchLatest(url: String, preRelease: PreRelease): Either[String, Option[String]] =
    HttpLookup.get(url, timeout = Duration.ofSeconds(15)) match
      case Left(err)                       => Left(s"lookup $url: $err")
      case Right(res) if res.status == 200 => Right(parseLatest(res.body, preRelease))
      case Right(res) if res.status == 404 || res.status == 410 || res.notModified =>
        Right(None)
      case Right(res) => Left(s"lookup $url: HTTP ${res.status}")

  private[core] def parseLatest(xml: String, preRelease: PreRelease = PreRelease.Skip): Option[String] =
    val latest  = raw"<latest>([^<]+)</latest>".r.findFirstMatchIn(xml).map(_.group(1))
    val release = raw"<release>([^<]+)</release>".r.findFirstMatchIn(xml).map(_.group(1))
    preRelease match
      case PreRelease.Include => latest.orElse(release)
      case PreRelease.Skip    =>
        release
          .filterNot(VersionStrategy.npm.isPreRelease)
          .orElse(latest.filterNot(VersionStrategy.npm.isPreRelease))
end MavenMetadata
