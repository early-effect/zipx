package zipx.plugin

import zipx.core.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Latest version from Maven-style `maven-metadata.xml` (Maven Central, then the sbt plugin repo). */
object MavenMetadata:

  private val Client: HttpClient =
    HttpClient.newBuilder.nn.connectTimeout(Duration.ofSeconds(10)).nn.build.nn

  def latest(coord: ZipxCoord, scalaBin: String, sbtBin: String): Either[String, Option[String]] =
    val artifact  = mavenArtifact(coord, scalaBin, sbtBin)
    val groupPath = (coord.group: String).replace('.', '/')
    val rel       = s"$groupPath/$artifact/maven-metadata.xml"
    val urls      = List(
      s"https://repo1.maven.org/maven2/$rel",
      s"https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/$rel",
    )
    combine(urls.map(fetchLatest))

  private[plugin] def combine(results: List[Either[String, Option[String]]]): Either[String, Option[String]] =
    results.collectFirst { case Right(Some(v)) => v } match
      case Some(v) => Right(Some(v))
      case None    =>
        val errors = results.collect { case Left(e) => e }
        if results.exists(_.isRight) then Right(None)
        else if errors.nonEmpty then Left(errors.mkString("; "))
        else Right(None)

  private def mavenArtifact(coord: ZipxCoord, scalaBin: String, sbtBin: String): String =
    coord match
      case l: Lib =>
        l.cross match
          case Cross.Java => l.artifact
          case _          => s"${l.artifact}_$scalaBin"
      case p: Plugin =>
        val sbtMaj = sbtBin.takeWhile(_ != '.')
        s"${p.artifact}_sbt${sbtMaj}_$scalaBin"

  private def fetchLatest(url: String): Either[String, Option[String]] =
    try
      val req = HttpRequest.newBuilder(URI.create(url)).nn.timeout(Duration.ofSeconds(15)).nn.GET.nn.build.nn
      val res = Client.send(req, HttpResponse.BodyHandlers.ofString).nn
      if res.statusCode != 200 then Right(None)
      else Right(parseLatest(Option(res.body).getOrElse("")))
    catch case ex: Exception => Left(s"lookup $url: ${ex.getMessage}")

  private[plugin] def parseLatest(xml: String): Option[String] =
    val latest  = raw"<latest>([^<]+)</latest>".r.findFirstMatchIn(xml).map(_.group(1))
    val release = raw"<release>([^<]+)</release>".r.findFirstMatchIn(xml).map(_.group(1))
    latest.orElse(release)
end MavenMetadata
