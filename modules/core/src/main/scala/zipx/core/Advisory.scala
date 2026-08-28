package zipx.core

import java.net.URI
import java.time.Duration
import zio.json.*

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
    endpoint: URI = URI.create("https://api.osv.dev/v1/query")
) extends AdvisorySource:

  def advisories(purl: Purl, version: String): Either[String, List[Advisory]] =
    val body = OsvAdvisorySource.queryBody(purl, version)
    HttpLookup.post(
      endpoint.toString,
      body,
      headers = Map("Content-Type" -> "application/json"),
      timeout = Duration.ofSeconds(30),
    ) match
      case Left(err)                       => Left(s"osv: $err")
      case Right(res) if res.status == 200 => OsvAdvisorySource.parseResponse(res.body)
      case Right(res)                      => Left(s"osv: HTTP ${res.status}")
  end advisories
end OsvAdvisorySource

object OsvAdvisorySource:

  private final case class Query(`package`: Pkg, version: String) derives JsonEncoder
  private final case class Pkg(purl: String) derives JsonEncoder
  private final case class Response(vulns: Option[List[Vuln]]) derives JsonDecoder
  private final case class Vuln(id: Option[String], summary: Option[String], severity: Option[String])
      derives JsonDecoder

  private[core] def queryBody(purl: Purl, version: String): String =
    Query(Pkg(purl), version).toJson

  /** Pulls `id`, `summary`, and a severity token out of an OSV query response. Empty or missing `vulns` is no finding.
    */
  private[core] def parseResponse(json: String): Either[String, List[Advisory]] =
    json.fromJson[Response] match
      case Left(err)  => Left(s"osv: $err")
      case Right(res) =>
        Right(
          res.vulns.getOrElse(Nil).map { v =>
            Advisory(
              id = v.id.getOrElse("unknown"),
              severity = v.severity.map(AdvisorySeverity.parse).getOrElse(AdvisorySeverity.Low),
              summary = v.summary.getOrElse(""),
            )
          }
        )
end OsvAdvisorySource
