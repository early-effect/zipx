package zipx.core

import zio.json.*

/** GitHub Dependency Submission snapshot JSON. */
object PinSnapshot:

  val Correlator: String = "zipx-pin-snapshot"

  final case class Snapshot(
      version: Int,
      sha: String,
      ref: String,
      job: Job,
      detector: Detector,
      scanned: String,
      manifests: Map[String, Manifest],
  ) derives JsonEncoder
  final case class Job(correlator: String, id: String) derives JsonEncoder
  final case class Detector(name: String, version: String, url: String) derives JsonEncoder
  final case class Manifest(name: String, resolved: Map[String, Resolved]) derives JsonEncoder
  final case class Resolved(package_url: String, relationship: String, scope: String) derives JsonEncoder

  def render(
      feeds: Seq[PinFeed],
      pins: Seq[Pin],
      sha: String,
      ref: String,
      jobId: String,
      scanned: String,
      detectorVersion: String,
  ): String =
    val manifests = feeds
      .map { feed =>
        val resolved = PinFeeds
          .inventory(feed, pins)
          .flatMap { pin =>
            pin.purl.map { purl =>
              pin.id -> Resolved(package_url = purl, relationship = "direct", scope = "runtime")
            }
          }
          .toMap
        (feed.name: String) -> Manifest(name = feed.name, resolved = resolved)
      }
      .filter(_._2.resolved.nonEmpty)
      .toMap
    Snapshot(
      version = 0,
      sha = sha,
      ref = ref,
      job = Job(Correlator, jobId),
      detector = Detector("zipx", detectorVersion, "https://github.com/early-effect/zipx"),
      scanned = scanned,
      manifests = manifests,
    ).toJson
  end render

  /** POST the snapshot to GitHub's Dependency Submission API. Tests never call this. */
  def submit(
      token: String,
      repository: String,
      body: String,
  ): Either[String, Unit] =
    if !repository.contains("/") then Left("GITHUB_REPOSITORY must be owner/name")
    else
      HttpLookup.post(
        s"https://api.github.com/repos/$repository/dependency-graph/snapshots",
        body,
        headers = Map(
          "Accept"               -> "application/vnd.github+json",
          "Authorization"        -> s"Bearer $token",
          "X-GitHub-Api-Version" -> "2022-11-28",
          "Content-Type"         -> "application/json",
        ),
        timeout = java.time.Duration.ofSeconds(30),
      ) match
        case Left(err)                                           => Left(s"github snapshot: $err")
        case Right(res) if res.status >= 200 && res.status < 300 => Right(())
        case Right(res)                                          => Left(s"github snapshot: HTTP ${res.status}")
end PinSnapshot
