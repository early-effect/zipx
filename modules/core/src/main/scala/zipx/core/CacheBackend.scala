package zipx.core

import zipx.workflow.SecretName

/** How CI caches sbt's build state. sbt 2.x's action cache is machine-wide and content-addressed on disk, so the choice
  * is between persisting those directories between runs and pointing sbt at a Bazel-gRPC endpoint instead.
  */
enum CacheBackend:
  /** Persists sbt's and coursier's caches plus the build `target/` with `actions/cache`. Keys are OS + JDK +
    * [[PlanConfig.cacheEpoch]] + run id + job id, so every job in a run saves its own entry; `restore-keys` then fall
    * back from the same run to the same epoch to the prior release's epoch to any older OS+JDK entry. Also disables
    * setup-sbt's `disk-cache` and setup-java's `cache: sbt`, which would otherwise key the same directories on
    * `hashFiles` and race this.
    */
  case LocalDir

  /** Runs a `buchgr/bazel-remote` gRPC server as a workflow service, for one run only. */
  case BazelRemoteSidecar(image: String, port: Int)

  /** A managed gRPC backend: BuildBuddy, EngFlow, NativeLink.
    *
    * @param headerSecret
    *   the *name* of the secret whose value becomes the auth header, typed because it is spliced into a
    *   `${{ secrets.… }}` expression. [[CacheBackend.managedRemote]] writes one as a literal checked while the build
    *   compiles.
    */
  case ManagedRemote(uri: String, headerSecret: SecretName)
end CacheBackend

object CacheBackend:

  /** {{{
    * zipxCache := CacheBackend.managedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")
    * }}}
    */
  inline def managedRemote(uri: String, inline headerSecret: String): CacheBackend =
    ManagedRemote(uri, SecretName(headerSecret))

  def managedRemoteMake(uri: String, headerSecret: String): Either[String, CacheBackend] =
    SecretName.make(headerSecret).map(ManagedRemote(uri, _))

end CacheBackend
