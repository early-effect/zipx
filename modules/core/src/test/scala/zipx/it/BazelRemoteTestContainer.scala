package zipx.it

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import zipx.core.RemoteCacheProof
import zio.*

import java.time.Duration

/** Config for the bazel-remote sidecar (saferis-style plain Testcontainers). */
final case class BazelRemoteContainerConfig(
    image: String = RemoteCacheProof.image,
    grpcPort: Int = RemoteCacheProof.port,
    httpPort: Int = RemoteCacheProof.httpPort,
    maxSizeGb: Int = 1,
    /** Shared Docker network alias so the sbt fixture reaches gRPC as `bazel-remote:9092`. */
    networkAlias: String = RemoteCacheProof.serviceName,
)

object BazelRemoteContainerConfig:
  val default: ULayer[BazelRemoteContainerConfig] = ZLayer.succeed(BazelRemoteContainerConfig())

/** Long-lived bazel-remote container on a shared [[Network]], same shape as saferis' PostgresTestContainer. */
final case class BazelRemoteTestContainer(
    config: BazelRemoteContainerConfig,
    network: Network,
):
  val container: GenericContainer[?] =
    val c: GenericContainer[?] = new GenericContainer(DockerImageName.parse(config.image))
    c.withNetwork(network)
    c.withNetworkAliases(config.networkAlias)
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
    try container.start()
    catch
      case e: Throwable =>
        throw new RuntimeException(
          "Remote-cache IT requires Docker; could not start bazel-remote: " + e.getMessage,
          e,
        )
    this

  val stop: UIO[Unit] =
    ZIO.succeed:
      container.stop()

  /** Host-mapped URI (for diagnostics from the test JVM). Fixture sbt uses [[RemoteCacheProof.grpcServiceUri]]. */
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

  /** Shared Docker network + bazel-remote, acquire/release like saferis PostgresTestContainer. */
  val default: ZLayer[Any, Nothing, BazelRemoteTestContainer] =
    ZLayer.scoped:
      ZIO.acquireRelease(
        for
          network      <- ZIO.attempt(Network.newNetwork()).orDie
          config       <- ZIO.succeed(BazelRemoteContainerConfig())
          c            <- ZIO.succeed(BazelRemoteTestContainer(config, network))
          (dur, ready) <- ZIO.attempt(c.start).orDie.timed
          _            <- ZIO.succeed(s"${dur.toMillis}ms image=${c.config.image}").debug("bazel-remote start")
        yield ready
      ) { c =>
        c.stop *> ZIO.succeed(c.network.close()).ignore
      }

end BazelRemoteTestContainer
