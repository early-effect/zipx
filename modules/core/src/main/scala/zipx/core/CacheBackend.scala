package zipx.core

/** How CI caches sbt's build state.
  *
  * sbt 2.x's action cache is machine-wide and content-addressed on disk. All three backends are fully wired by the
  * planner: [[LocalDir]] persists the local cache dirs with an epoch-keyed `actions/cache` (and disables the
  * hashFiles-based built-ins that would otherwise race it); the remote backends point sbt at a Bazel-gRPC endpoint
  * (sidecar or managed) via job env that the plugin reads at load time.
  */
enum CacheBackend:
  /** Persist `~/.sbt`, `~/.cache/sbt`, `~/.cache/coursier`, and the build `target/` (compiled classes +
    * `target/sona-staging`) with `actions/cache` (pin via [[ActionPins.cache]]). Primary key is OS + JDK +
    * [[PlanConfig.cacheEpoch]] + `github.run_id` + job id so each job in a run can save; restore-keys prefer the same
    * run (accumulated upstream jobs), then the same epoch from prior runs. Disables setup-sbt `disk-cache` and
    * setup-java `cache: sbt` so caching is not also pinned to `hashFiles`.
    */
  case LocalDir

  /** Run a `buchgr/bazel-remote` gRPC server as a workflow service and point `Global / remoteCache` at it. */
  case BazelRemoteSidecar(image: String, port: Int)

  /** Point `Global / remoteCache` at a managed gRPC backend (BuildBuddy/EngFlow/NativeLink). `headerSecret` is the
    * *name* of a GitHub Actions secret whose value becomes the auth header (rendered via [[EnvValue.secret]]).
    */
  case ManagedRemote(uri: String, headerSecret: String)
end CacheBackend
