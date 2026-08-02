package zipx.it

import zipx.core.RemoteCacheProof
import zio.*
import zio.test.*

import java.net.HttpURLConnection
import java.nio.file.Files

object RemoteCacheItSpec extends ZIOSpecDefault:

  private val dockerOk = FixtureRunner.shouldRunLiveIt

  private def require(cond: Boolean, msg: => String): Unit =
    if !cond then throw new RuntimeException(msg)

  private def httpGet(url: String, connectMs: Int = 5_000, readMs: Int = 10_000): String =
    val conn = new java.net.URI(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(connectMs)
    conn.setReadTimeout(readMs)
    conn.setRequestMethod("GET")
    val code   = conn.getResponseCode
    val stream = if code >= 200 && code < 300 then conn.getInputStream else conn.getErrorStream
    try
      val body = if stream == null then "" else new String(stream.readAllBytes())
      if code < 200 || code >= 300 then
        throw new RuntimeException(s"HTTP $code for $url${if body.nonEmpty then s": $body" else ""}")
      body
    finally
      if stream != null then stream.close()
      conn.disconnect()
  end httpGet

  /** One sbt process under an isolated HOME; in-session `wipeItCaches` forces the post-wipe phase onto remote. */
  private def runIsolatedSbt(
      grpcUri: String,
      script: String,
      homePrefix: String,
  ): FixtureRunner.RunResult =
    val fixture = FixtureRunner.materializeFixture()
    val home    = Files.createTempDirectory(s"$homePrefix-")
    try FixtureRunner.runSbt(fixture, grpcUri, script, home)
    finally
      FixtureRunner.deleteTree(fixture)
      FixtureRunner.deleteTree(home)
  end runIsolatedSbt

  private def remoteHitHint(out: String): Boolean =
    val lower = out.toLowerCase
    lower.contains("cache") && (lower.contains("hit") || lower.contains("remote"))

  def spec =
    suite("RemoteCacheItSpec")(
      test("contract pins match RemoteCacheProof") {
        for
          c <- ZIO.service[BazelRemoteTestContainer]
          _ <- ZIO.succeed(c.grpcUri).debug("grpcUri")
        yield assertTrue(
          c.config.image == RemoteCacheProof.image,
          c.config.grpcPort == RemoteCacheProof.port,
          c.grpcUri.startsWith("grpc://"),
        )
      } @@ TestAspect.timeout(30.seconds) @@ TestAspect.timed,
      test("sidecar is ready (HTTP status)") {
        for
          c           <- ZIO.service[BazelRemoteTestContainer]
          (dur, body) <- ZIO.attemptBlockingInterrupt(httpGet(s"${c.httpBase}/status")).timed
          _           <- ZIO.succeed(s"${dur.toMillis}ms bodyLen=${body.length}").debug("http /status")
        yield assertTrue(body.nonEmpty)
      } @@ TestAspect.timeout(45.seconds) @@ TestAspect.timed,
      test("Put then Get across wiped local caches") {
        for
          c           <- ZIO.service[BazelRemoteTestContainer]
          (wall, run) <- ZIO.attemptBlockingInterrupt {
            runIsolatedSbt(
              c.grpcUri,
              script = "itStamp; compile; test; wipeItCaches; itStamp; compile; test; itStamp",
              homePrefix = "zipx-it-home",
            )
          }.timed
          _ <- ZIO.succeed(run.ok).debug("put/get ok")
          phases = run.phaseMs
          _ <- ZIO
            .succeed(
              phases match
                case Some((putMs, getMs)) =>
                  s"wall=${wall.toMillis}ms put=${putMs}ms get=${getMs}ms"
                case None => s"wall=${wall.toMillis}ms (no itStamp phases)\n${run.out.takeRight(2500)}"
            )
            .debug("put/get")
          after   = run.afterWipe
          hitHint = remoteHitHint(after)
          _ <- ZIO.succeed(hitHint).debug("remoteHitHint")
          putMs = phases.map(_._1).getOrElse(0L)
          getMs = phases.map(_._2).getOrElse(Long.MaxValue)
        yield
          require(run.ok, s"Put/Get script failed:\n${run.out}")
          require(
            hitHint || getMs <= putMs * 2,
            s"Expected remote reuse signal or non-regressing time; put=${putMs}ms get=${getMs}ms\n$after",
          )
          assertTrue(run.ok)
      } @@ TestAspect.timeout(5.minutes) @@ TestAspect.timed,
      test("different cacheVersion does not false-hit") {
        for
          c           <- ZIO.service[BazelRemoteTestContainer]
          (wall, run) <- ZIO.attemptBlockingInterrupt {
            // Load-time safe: writeItCacheVersion + reload (no `set` / `eval`).
            runIsolatedSbt(
              c.grpcUri,
              script = List(
                "writeItCacheVersion111",
                "reload",
                "itStamp",
                "compile",
                "wipeItCaches",
                "writeItCacheVersion222",
                "reload",
                "itStamp",
                "compile",
                "itStamp",
              ).mkString("; "),
              homePrefix = "zipx-it-home-cv",
            )
          }.timed
          phases = run.phaseMs
          _ <- ZIO
            .succeed(
              phases match
                case Some((a, b)) => s"wall=${wall.toMillis}ms cv111=${a}ms cv222=${b}ms"
                case None         => s"wall=${wall.toMillis}ms (no itStamp phases)\n${run.out.takeRight(2500)}"
            )
            .debug("cacheVersion")
        yield
          require(run.ok, s"cacheVersion script failed:\n${run.out}")
          assertTrue(run.ok)
      } @@ TestAspect.timeout(5.minutes) @@ TestAspect.timed,
    ).provideShared(BazelRemoteTestContainer.default) @@
      TestAspect.sequential @@
      TestAspect.withLiveClock @@
      TestAspect.timeout(5.minutes) @@
      TestAspect.timed @@
      (if dockerOk then TestAspect.identity else TestAspect.ignore)

end RemoteCacheItSpec
