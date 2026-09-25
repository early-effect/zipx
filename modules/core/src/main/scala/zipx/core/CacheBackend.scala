package zipx.core

import zipx.workflow.SecretName

/** How CI caches sbt's build state. sbt 2.x's action cache is machine-wide and content-addressed on disk, so the choice
  * is between persisting those directories between runs and pointing sbt at a Bazel-gRPC endpoint instead.
  */
enum CacheBackend:
  /** Persists sbt's and coursier's caches plus the build `target/` with `actions/cache`. Only the build snapshot's
    * owner saves ([[LocalCacheMode.Save]]); every other sbt job restores it. Keys are OS + JDK +
    * [[PlanConfig.cacheEpoch]] + `build` + run id + job id; `restore-keys` fall back from this run's build saves to the
    * epoch's latest build save, then the prior release's, then any older OS+JDK entry. Also disables setup-sbt's
    * `disk-cache` and setup-java's `cache: sbt`, which would otherwise key the same directories on `hashFiles` and race
    * this.
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

/** What one job does with the [[CacheBackend.LocalDir]] build snapshot.
  *
  * One owner saves, everyone else restores. When every sbt job saved its own entry, a single PR run wrote nine 300 MB
  * entries, evicted the default branch's snapshot within one wave of PRs, and `restore-keys` handed each job whichever
  * entry was newest, usually a job that never compiled what it needed.
  */
enum LocalCacheMode:
  /** Restore, then save this job's snapshot under the `build` role: the builtin test, and `cache-rehydrate`. */
  case Save

  /** Restore the latest build snapshot and never save. The default for every capability. */
  case Restore

  /** No LocalDir steps at all: jobs that load sbt but compile nothing worth keeping, and remote backends. */
  case Off

  /** The `cache-mode` input of the generated `zipx-sbt-setup` composite. */
  def input: String = this match
    case Save    => "save"
    case Restore => "restore"
    case Off     => "off"
end LocalCacheMode
