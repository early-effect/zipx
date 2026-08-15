package zipx.core

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

enum AdvisorySeverity:
  case Low, Moderate, High, Critical

  def rank: Int = ordinal

object AdvisorySeverity:
  def parse(raw: String): AdvisorySeverity =
    raw.trim.toLowerCase match
      case "critical"         => Critical
      case "high"             => High
      case "moderate" | "med" => Moderate
      case _                  => Low

final case class Advisory(id: String, severity: AdvisorySeverity, summary: String)

trait AdvisorySource:
  def advisories(purl: Purl, version: String): Either[String, List[Advisory]]

/** OSV HTTP client. Tests inject a fake [[AdvisorySource]] and never construct this. */
final class OsvAdvisorySource(
    client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    endpoint: URI = URI.create("https://api.osv.dev/v1/query"),
) extends AdvisorySource:

  def advisories(purl: Purl, version: String): Either[String, List[Advisory]] =
    val body    = OsvAdvisorySource.queryBody(purl, version)
    val request = HttpRequest
      .newBuilder(endpoint)
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()
    try
      val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      if response.statusCode() == 200 then OsvAdvisorySource.parseResponse(response.body())
      else Left(s"osv: HTTP ${response.statusCode()}")
    catch case e: Exception => Left(s"osv: ${e.getMessage}")
  end advisories
end OsvAdvisorySource

object OsvAdvisorySource:

  private[core] def queryBody(purl: Purl, version: String): String =
    s"""{"package":{"purl":"${PinSnapshot.escape(purl)}"},"version":"${PinSnapshot.escape(version)}"}"""

  /** Pulls `id`, `summary`, and a severity token out of an OSV query response. Empty or missing `vulns` is no finding.
    */
  private[core] def parseResponse(json: String): Either[String, List[Advisory]] =
    MiniJson.extractArray(json, "vulns") match
      case None             => Right(Nil)
      case Some(Left(err))  => Left(err)
      case Some(Right(arr)) =>
        Right(MiniJson.objects(arr).map { obj =>
          val id       = MiniJson.stringField(obj, "id").getOrElse("unknown")
          val summary  = MiniJson.stringField(obj, "summary").getOrElse("")
          val severity =
            MiniJson.stringField(obj, "severity").map(AdvisorySeverity.parse).getOrElse(AdvisorySeverity.Low)
          Advisory(id, severity, summary)
        })
end OsvAdvisorySource
