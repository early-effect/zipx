package zipx.it

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import zipx.core.RemoteCacheProof
import zio.*

import java.time.Duration

final case class BazelRemoteContainerConfig(
    image: String = RemoteCacheProof.image,
    grpcPort: Int = RemoteCacheProof.port,
    httpPort: Int = 8080,
    maxSizeGb: Int = 1,
)

object BazelRemoteContainerConfig:
  val default: ULayer[BazelRemoteContainerConfig] = ZLayer.succeed(BazelRemoteContainerConfig())

final case class BazelRemoteTestContainer(config: BazelRemoteContainerConfig):
  val container: GenericContainer[?] =
    val c: GenericContainer[?] = new GenericContainer(DockerImageName.parse(config.image))
    c.withExposedPorts(Integer.valueOf(config.grpcPort), Integer.valueOf(config.httpPort))
    c.withCommand(s"--max_size=${config.maxSizeGb}", "--dir=/data")
    // ListeningPort alone races: gRPC can accept before HTTP /status is up.
    c.waitingFor(
      Wait
        .forHttp("/status")
        .forPort(config.httpPort)
        .forStatusCode(200)
        .withStartupTimeout(Duration.ofMinutes(2))
    )
    c
  end container

  def start: BazelRemoteTestContainer =
    container.start()
    this

  val stop: UIO[Unit] =
    ZIO.succeed:
      container.stop()

  def grpcUri: String =
    val host = container.getHost
    val port = container.getMappedPort(config.grpcPort)
    s"grpc://$host:$port"

  def httpBase: String =
    val host = container.getHost
    val port = container.getMappedPort(config.httpPort)
    s"http://$host:$port"

end BazelRemoteTestContainer

object BazelRemoteTestContainer:
  val base: ZLayer[BazelRemoteContainerConfig, Nothing, BazelRemoteTestContainer] =
    ZLayer.derive[BazelRemoteTestContainer]

  val layer: ZLayer[BazelRemoteContainerConfig, Nothing, BazelRemoteTestContainer] =
    base >>> ZLayer.scoped:
      ZIO.acquireRelease(
        for
          c            <- ZIO.service[BazelRemoteTestContainer]
          (dur, ready) <- ZIO.attempt(c.start).orDie.timed
          _            <- ZIO.succeed(s"${dur.toMillis}ms image=${c.config.image}").debug("bazel-remote start")
        yield ready
      )(_.stop)

  val default: ZLayer[Any, Nothing, BazelRemoteTestContainer] =
    BazelRemoteContainerConfig.default >>> layer

end BazelRemoteTestContainer
