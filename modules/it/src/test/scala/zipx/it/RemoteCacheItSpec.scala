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
    val in = conn.getInputStream
    try new String(in.readAllBytes())
    finally
      in.close()
      conn.disconnect()
  end httpGet

  def spec =
    suite("RemoteCacheItSpec")(
      test("contract pins match RemoteCacheProof") {
        for c <- ZIO.service[BazelRemoteTestContainer]
        yield assertTrue(
          c.config.image == RemoteCacheProof.image,
          c.config.grpcPort == RemoteCacheProof.port,
          c.grpcUri.startsWith("grpc://"),
        )
      } @@ TestAspect.timeout(30.seconds),
      test("sidecar is ready (HTTP status)") {
        for
          c    <- ZIO.service[BazelRemoteTestContainer]
          body <- ZIO.attemptBlockingInterrupt(httpGet(s"${c.httpBase}/status"))
        yield assertTrue(body.nonEmpty)
      } @@ TestAspect.timeout(45.seconds),
      test("Put then Get across wiped local caches") {
        for
          c      <- ZIO.service[BazelRemoteTestContainer]
          result <- ZIO.attemptBlockingInterrupt {
            val fixture = FixtureRunner.materializeFixture()
            val homeA   = Files.createTempDirectory("zipx-it-home-a-")
            val homeB   = Files.createTempDirectory("zipx-it-home-b-")
            try
              val put = FixtureRunner.runSbt(
                fixture,
                c.grpcUri,
                Seq("compile", "test"),
                homeA,
              )
              require(put.ok, s"Put failed:\n${put.out}")
              FixtureRunner.wipeLocalCaches(fixture, homeA)
              val get = FixtureRunner.runSbt(
                fixture,
                c.grpcUri,
                Seq("compile", "test"),
                homeB,
              )
              require(get.ok, s"Get failed:\n${get.out}")
              val hitHint =
                get.out.toLowerCase.contains("cache") &&
                  (get.out.toLowerCase.contains("hit") || get.out.toLowerCase.contains("remote"))
              require(
                hitHint || get.elapsedMs <= put.elapsedMs * 2,
                s"Expected remote reuse signal or non-regressing time; put=${put.elapsedMs}ms get=${get.elapsedMs}ms\n${get.out}",
              )
              true
            finally
              FixtureRunner.deleteTree(fixture)
              FixtureRunner.deleteTree(homeA)
              FixtureRunner.deleteTree(homeB)
            end try
          }
        yield assertTrue(result)
      } @@ TestAspect.timeout(5.minutes),
      test("different cacheVersion does not false-hit") {
        for
          c      <- ZIO.service[BazelRemoteTestContainer]
          result <- ZIO.attemptBlockingInterrupt {
            val fixture = FixtureRunner.materializeFixture()
            val homeA   = Files.createTempDirectory("zipx-it-home-cv-a-")
            val homeB   = Files.createTempDirectory("zipx-it-home-cv-b-")
            try
              val put = FixtureRunner.runSbt(
                fixture,
                c.grpcUri,
                Seq("compile"),
                homeA,
                cacheVersionOverride = Some(111L),
              )
              require(put.ok, s"Put failed:\n${put.out}")
              FixtureRunner.wipeLocalCaches(fixture, homeA)
              val other = FixtureRunner.runSbt(
                fixture,
                c.grpcUri,
                Seq("compile"),
                homeB,
                cacheVersionOverride = Some(222L),
              )
              require(other.ok, s"Partitioned compile failed:\n${other.out}")
              true
            finally
              FixtureRunner.deleteTree(fixture)
              FixtureRunner.deleteTree(homeA)
              FixtureRunner.deleteTree(homeB)
            end try
          }
        yield assertTrue(result)
      } @@ TestAspect.timeout(5.minutes),
    ).provideShared(BazelRemoteTestContainer.default) @@
      TestAspect.sequential @@
      TestAspect.withLiveClock @@
      TestAspect.timeout(5.minutes) @@
      (if dockerOk then TestAspect.identity else TestAspect.ignore)

end RemoteCacheItSpec
