package zipx.core

/** Shared pins for remote-cache docs, planner tests, and live IT.
  *
  * One source of truth so Specular examples, [[PlannerSpec]], and `RemoteCacheItSpec` cannot drift on image, port, or
  * env names. Prefer this over `:latest` in any proof surface.
  */
object RemoteCacheProof:

  /** Official Docker Hub image for [buchgr/bazel-remote](https://github.com/buchgr/bazel-remote), version-pinned. */
  val image: String = "buchgr/bazel-remote-cache:v2.6.1"

  /** gRPC listen port inside the sidecar (HTTP is 8080; zipx points sbt at gRPC). */
  val port: Int = 9092

  val envUri: String    = "ZIPX_REMOTE_CACHE"
  val envHeader: String = "ZIPX_REMOTE_CACHE_HEADER"

  /** Service id emitted on BazelRemoteSidecar jobs. */
  val serviceName: String = "bazel-remote"

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
