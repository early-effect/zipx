package zipx.core

/** How CI caches sbt's build state.
  *
  * sbt 2.x's action cache is machine-wide and content-addressed on disk; the cheapest sound CI strategy is to persist
  * that directory with `actions/cache`. Remote (Bazel-gRPC) backends are modeled here for later milestones but only
  * [[CacheBackend.LocalDir]] emits steps today.
  */
enum CacheBackend:
  /** Persist sbt's local action-cache directory with `actions/cache@v4`. No infra required. */
  case LocalDir

  /** Run a `buchgr/bazel-remote` gRPC server as a workflow service and point `Global / remoteCache` at it. */
  case BazelRemoteSidecar(image: String, port: Int)

  /** Point `Global / remoteCache` at a managed gRPC backend (BuildBuddy/EngFlow/NativeLink), auth via a header secret. */
  case ManagedRemote(uri: String, headerSecret: String)
