package zipx.core

/** GitHub Dependency Submission snapshot JSON. Hand-rolled so core stays free of a JSON library. */
object PinSnapshot:

  val Correlator: String = "zipx-pin-snapshot"

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
      .map(feed => feed.name -> PinFeeds.inventory(feed, pins).filter(_.purl.isDefined))
      .filter(_._2.nonEmpty)
    val manifestJson =
      if manifests.isEmpty then "{}"
      else
        manifests
          .map { (name, pins) =>
            val resolved = pins
              .map { pin =>
                val purl = pin.purl.get
                s"""      "${escape(pin.id)}":{"package_url":"${escape(
                    purl
                  )}","relationship":"direct","scope":"runtime"}"""
              }
              .mkString(",\n")
            s"""    "${escape(name)}":{"name":"${escape(name)}","resolved":{\n$resolved\n    }}"""
          }
          .mkString(",\n")
    s"""{
  "version": 0,
  "sha": "${escape(sha)}",
  "ref": "${escape(ref)}",
  "job": {"correlator": "$Correlator", "id": "${escape(jobId)}"},
  "detector": {"name": "zipx", "version": "${escape(detectorVersion)}", "url": "https://github.com/early-effect/zipx"},
  "scanned": "${escape(scanned)}",
  "manifests": {
$manifestJson
  }
}"""
  end render

  def escape(s: String): String =
    val out = StringBuilder(s.length)
    var i   = 0
    while i < s.length do
      s.charAt(i) match
        case '"'         => out.append("\\\"")
        case '\\'        => out.append("\\\\")
        case '\n'        => out.append("\\n")
        case '\r'        => out.append("\\r")
        case '\t'        => out.append("\\t")
        case c if c < 32 =>
          out.append("\\u")
          val hex = Integer.toHexString(c)
          var pad = 4 - hex.length
          while pad > 0 do
            out.append('0')
            pad -= 1
          out.append(hex)
        case c => out.append(c)
      end match
      i += 1
    end while
    out.toString
  end escape

  /** POST the snapshot to GitHub's Dependency Submission API. Tests never call this. */
  def submit(
      token: String,
      repository: String,
      body: String,
      client: java.net.http.HttpClient =
        java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build(),
  ): Either[String, Unit] =
    if !repository.contains("/") then Left("GITHUB_REPOSITORY must be owner/name")
    else
      val uri     = java.net.URI.create(s"https://api.github.com/repos/$repository/dependency-graph/snapshots")
      val request = java.net.http.HttpRequest
        .newBuilder(uri)
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", s"Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
        .build()
      try
        val response = client.send(
          request,
          java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8),
        )
        if response.statusCode() >= 200 && response.statusCode() < 300 then Right(())
        else Left(s"github snapshot: HTTP ${response.statusCode()}")
      catch case e: Exception => Left(s"github snapshot: ${e.getMessage}")
end PinSnapshot
