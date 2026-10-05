package zipx.core

import java.util.concurrent.ConcurrentHashMap
import zio.*
import zio.test.*

object HttpLookupSpec extends ZIOSpecDefault:

  private val url = "https://example.com/meta"

  private def ok(body: String = "ok", etag: Option[String] = None): HttpLookupResult =
    HttpLookupResult(200, body, etag.map("ETag" -> _).toMap)

  private def status(code: Int, headers: Map[String, String] = Map.empty): HttpLookupResult =
    HttpLookupResult(code, "", headers)

  private def countingSend(n: Ref[Int], respond: Int => Task[HttpLookupResult]): HttpLookup.Send =
    _ => n.updateAndGet(_ + 1).flatMap(respond)

  def spec = suite("HttpLookup")(
    test("first-attempt jitter sleeps before the initial send") {
      for
        _ <- TestRandom.feedDoubles(0.5)
        n <- Ref.make(0)
        send = countingSend(n, _ => ZIO.succeed(ok()))
        fiber <- HttpLookup
          .getZio(url, send = send, retry = Schedule.stop, firstJitter = 250.millis)
          .fork
        _  <- TestClock.adjust(124.millis)
        c1 <- n.get
        _  <- TestClock.adjust(2.millis)
        c2 <- n.get
        _  <- fiber.join
      yield assertTrue(c1 == 0, c2 == 1)
    },
    test("retries 5xx then succeeds") {
      for
        n <- Ref.make(0)
        send = countingSend(
          n,
          i => if i < 3 then ZIO.succeed(status(503)) else ZIO.succeed(ok("done")),
        )
        result <- HttpLookup.getZio(url, send = send, retry = Schedule.recurs(5), firstJitter = Duration.Zero)
        count  <- n.get
      yield assertTrue(result.status == 200, result.body == "done", count == 3)
    },
    test("exponential retry waits between 5xx attempts") {
      for
        n <- Ref.make(0)
        send = countingSend(n, _ => ZIO.succeed(status(503)))
        fiber <- HttpLookup
          .getZio(
            url,
            send = send,
            retry = Schedule.exponential(100.millis) && Schedule.recurs(2),
            firstJitter = Duration.Zero,
          )
          .fork
        _  <- TestClock.adjust(1.nanos)
        c0 <- n.get
        _  <- TestClock.adjust(100.millis)
        c1 <- n.get
        _  <- TestClock.adjust(200.millis)
        c2 <- n.get
        _  <- fiber.join.either
      yield assertTrue(c0 == 1, c1 == 2, c2 == 3)
    },
    test("honors Retry-After on 429 before the next attempt") {
      for
        n <- Ref.make(0)
        send = countingSend(
          n,
          i =>
            if i == 1 then ZIO.succeed(status(429, Map("Retry-After" -> "2")))
            else ZIO.succeed(ok()),
        )
        fiber <- HttpLookup.getZio(url, send = send, retry = Schedule.recurs(5), firstJitter = Duration.Zero).fork
        _     <- TestClock.adjust(1.second)
        c1    <- n.get
        _     <- TestClock.adjust(1.second)
        out   <- fiber.join
        c2    <- n.get
      yield assertTrue(c1 == 1, c2 == 2, out.status == 200)
    },
    test("404 is a miss and is not retried") {
      for
        n <- Ref.make(0)
        send = countingSend(n, _ => ZIO.succeed(status(404)))
        result <- HttpLookup.getZio(url, send = send, retry = Schedule.recurs(5), firstJitter = Duration.Zero)
        count  <- n.get
      yield assertTrue(result.status == 404, result.isMiss, count == 1)
    },
    test("other 4xx is not retried") {
      for
        n <- Ref.make(0)
        send = countingSend(n, _ => ZIO.succeed(status(400)))
        result <- HttpLookup.getZio(url, send = send, retry = Schedule.recurs(5), firstJitter = Duration.Zero).either
        count  <- n.get
      yield assertTrue(result == Left("HTTP 400"), count == 1)
    },
    test("retries timeouts") {
      for
        n <- Ref.make(0)
        send = countingSend(n, _ => ZIO.fail(new java.net.http.HttpTimeoutException("timed out")))
        result <- HttpLookup.getZio(url, send = send, retry = Schedule.recurs(2), firstJitter = Duration.Zero).either
        count  <- n.get
      yield assertTrue(result.isLeft, count == 3)
    },
    test("a repeat GET revalidates with the first ETag, and a 304 answers with the first body") {
      val seen                  = new ConcurrentHashMap[String, String]()
      val send: HttpLookup.Send = req =>
        val inm = req.headers().firstValue("If-None-Match").orElse("")
        seen.put(if inm.isEmpty then "plain" else "conditional", inm)
        if inm == "\"abc\"" then ZIO.succeed(status(304, Map("ETag" -> "\"abc\"")))
        else ZIO.succeed(ok("body", Some("\"abc\"")))
      val cache = new ConcurrentHashMap[String, HttpLookupResult]()
      for
        first  <- HttpLookup.getZio(url, send = send, retry = Schedule.stop, firstJitter = Duration.Zero, cache = cache)
        second <- HttpLookup.getZio(url, send = send, retry = Schedule.stop, firstJitter = Duration.Zero, cache = cache)
      yield assertTrue(
        first.status == 200,
        second.status == 200,
        second.body == "body",
        !second.isMiss,
        seen.get("conditional") == "\"abc\"",
      )
      end for
    },
    test("a caller's own If-None-Match gets the server's 304 back") {
      val send: HttpLookup.Send = req =>
        if req.headers().firstValue("If-None-Match").orElse("") == "\"abc\"" then ZIO.succeed(status(304, Map.empty))
        else ZIO.succeed(ok("body", Some("\"abc\"")))
      for result <- HttpLookup.getZio(
          url,
          ifNoneMatch = Some("\"abc\""),
          send = send,
          retry = Schedule.stop,
          firstJitter = Duration.Zero,
        )
      yield assertTrue(result.notModified)
    },
    test("parseRetryAfter reads delta-seconds") {
      HttpLookup.parseRetryAfter("12").map(d => assertTrue(d.contains(12.seconds)))
    },
    test("a pre-signed artifact hop drops Authorization, and its 200 is published") {
      val signed                = "https://signed.example/pom"
      val send: HttpLookup.Send = req =>
        val auth = req.headers().firstValue("Authorization").orElse("")
        if req.uri().toString == url then ZIO.succeed(status(302, Map("Location" -> signed)))
        else ZIO.succeed(HttpLookupResult(200, auth, Map.empty))
      val denied: HttpLookup.Send = _ => ZIO.succeed(status(401))
      val stayed: HttpLookup.Send = _ => ZIO.succeed(status(302, Map("Location" -> signed)))
      for
        hopped <- HttpLookup.getZio(
          url,
          headers = Map("Authorization" -> "Bearer secret"),
          send = send,
          retry = Schedule.stop,
          firstJitter = Duration.Zero,
          followRedirect = true,
        )
        blocked <- HttpLookup
          .getZio(url, send = denied, retry = Schedule.stop, firstJitter = Duration.Zero, followRedirect = true)
          .either
        plain <- HttpLookup
          .getZio(url, send = stayed, retry = Schedule.stop, firstJitter = Duration.Zero)
          .either
      yield assertTrue(
        hopped.status == 200,
        hopped.body.isEmpty,
        Modver.registryStatus(hopped.status) == Right(RegistryStatus.Published),
        Modver.registryStatus(401) == Left("HTTP 401"),
        blocked == Left("HTTP 401"),
        plain == Left("HTTP 302"),
      )
      end for
    },
    test("a pre-signed hop that is missing is a miss, not an unreachable registry") {
      val signed                = "https://signed.example/missing"
      val send: HttpLookup.Send = req =>
        if req.uri().toString == url then ZIO.succeed(status(302, Map("Location" -> signed)))
        else ZIO.succeed(status(404))
      for result <- HttpLookup.getZio(
          url,
          headers = Map("Authorization" -> "Bearer secret"),
          send = send,
          retry = Schedule.stop,
          firstJitter = Duration.Zero,
          followRedirect = true,
        )
      yield assertTrue(result.isMiss, Modver.registryStatus(result.status) == Right(RegistryStatus.Missing))
    },
    test("a packages jar redirect is one hop, and a miss is not") {
      val jar    = "https://maven.pkg.github.com/acme/repo/org/artifact/1.0.0/artifact-1.0.0.jar"
      val signed = "https://github-registry-files.githubusercontent.com/1/file"
      assertTrue(
        HttpLookup.redirectTarget(302, Some(signed), jar).contains(signed),
        HttpLookup.redirectTarget(302, Some("artifact.jar"), jar).exists(_.endsWith("/artifact.jar")),
        HttpLookup.redirectTarget(302, None, jar).isEmpty,
        HttpLookup.redirectTarget(302, Some(""), jar).isEmpty,
        HttpLookup.redirectTarget(200, Some(signed), jar).isEmpty,
        HttpLookup.redirectTarget(404, None, jar).isEmpty,
      )
    },
  )
end HttpLookupSpec
