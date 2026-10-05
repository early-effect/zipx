package zipx.core

/** Remote-cache pins shared by docs, planner tests and the live IT so none drift on image, port or env names. */
object RemoteCacheProof:

  val image: String = "buchgr/bazel-remote-cache:v2.6.1"

  /** zipx points sbt at gRPC, not HTTP. */
  val port: Int = 9092

  /** Readiness only. */
  val httpPort: Int = 8080

  val envUri: String    = "ZIPX_REMOTE_CACHE"
  val envHeader: String = "ZIPX_REMOTE_CACHE_HEADER"

  /** Both the GHA service id and the IT's Docker network alias. */
  val serviceName: String = "bazel-remote"

  /** The live IT's sbt container, not the host runner's setup-sbt. */
  val sbtFixtureImage: String = "sbtscala/scala-sbt:eclipse-temurin-25.0.3_9_2.x"

  /** How the fixture sbt container reaches bazel-remote over the shared network. */
  def grpcServiceUri: String = s"grpc://$serviceName:$port"

  def sidecar: CacheBackend.BazelRemoteSidecar =
    CacheBackend.BazelRemoteSidecar(image, port)

  def grpcLocalhost: String = s"grpc://localhost:$port"

  def portMapping: String = s"$port:$port"

  /** Substrings DocSpecs / IT assert on for a sidecar Aggregate test job. */
  def sidecarYamlMustContain: List[String] = List(
    s"image: $image",
    portMapping,
    s"$envUri: $grpcLocalhost",
  )

end RemoteCacheProof
