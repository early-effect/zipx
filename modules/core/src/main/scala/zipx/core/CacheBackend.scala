package zipx.core

/** How CI caches sbt's build state.
  *
  * sbt 2.x's action cache is machine-wide and content-addressed on disk. All three backends are fully wired by the
  * planner: [[LocalDir]] relies on `setup-java` `cache: sbt` plus `setup-sbt`'s disk-cache (no second `actions/cache`);
  * the remote backends point sbt at a Bazel-gRPC endpoint (sidecar or managed) via job env that the plugin reads at
  * load time.
  */
enum CacheBackend:
  /** Default: use the caches already built into `actions/setup-java` (`cache: sbt`) and `sbt/setup-sbt` (disk-cache).
    * No extra `actions/cache` step — that double-restored `~/.cache/sbt` against setup-sbt.
    */
  case LocalDir

  /** Run a `buchgr/bazel-remote` gRPC server as a workflow service and point `Global / remoteCache` at it. */
  case BazelRemoteSidecar(image: String, port: Int)

  /** Point `Global / remoteCache` at a managed gRPC backend (BuildBuddy/EngFlow/NativeLink). `headerSecret` is the
    * *name* of a GitHub Actions secret whose value becomes the auth header (rendered via [[EnvValue.secret]]).
    */
  case ManagedRemote(uri: String, headerSecret: String)
