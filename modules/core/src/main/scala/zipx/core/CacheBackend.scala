package zipx.core

/** How CI caches sbt's build state.
  *
  * sbt 2.x's action cache is machine-wide and content-addressed on disk. All three backends are fully wired by the
  * planner: [[LocalDir]] persists the local cache dir with `actions/cache`; the remote backends point sbt at a
  * Bazel-gRPC endpoint (sidecar or managed) via job env that the plugin reads at load time.
  */
enum CacheBackend:
  /** Persist sbt's local action-cache directory with `actions/cache` (pin via [[ActionPins.cache]]). No infra required. */
  case LocalDir

  /** Run a `buchgr/bazel-remote` gRPC server as a workflow service and point `Global / remoteCache` at it. */
  case BazelRemoteSidecar(image: String, port: Int)

  /** Point `Global / remoteCache` at a managed gRPC backend (BuildBuddy/EngFlow/NativeLink). `headerSecret` is the
    * *name* of a GitHub Actions secret whose value becomes the auth header (rendered via [[EnvValue.secret]]).
    */
  case ManagedRemote(uri: String, headerSecret: String)
