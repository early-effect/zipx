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
    test("304 with If-None-Match is not-modified") {
      val etags                 = new ConcurrentHashMap[String, String]()
      val send: HttpLookup.Send = req =>
        val inm = req.headers().firstValue("If-None-Match").orElse("")
        if inm == "\"abc\"" then ZIO.succeed(status(304, Map("ETag" -> "\"abc\"")))
        else ZIO.succeed(ok("body", Some("\"abc\"")))
      for
        first  <- HttpLookup.getZio(url, send = send, retry = Schedule.stop, firstJitter = Duration.Zero, etags = etags)
        second <- HttpLookup.getZio(url, send = send, retry = Schedule.stop, firstJitter = Duration.Zero, etags = etags)
      yield assertTrue(first.status == 200, first.body == "body", second.notModified, second.isMiss)
    },
    test("parseRetryAfter reads delta-seconds") {
      HttpLookup.parseRetryAfter("12").map(d => assertTrue(d.contains(12.seconds)))
    },
  )
end HttpLookupSpec
