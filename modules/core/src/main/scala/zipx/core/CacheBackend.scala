package zipx.core

import zipx.workflow.SecretName

/** How CI caches sbt's build state: persist sbt's on-disk action cache between runs, or point sbt at a remote action
  * cache. `http` and `https` use bazel's HTTP API. Any other scheme, including `grpc` and `grpcs`, uses gRPC. The URI
  * is passed through.
  */
enum CacheBackend:
  /** Persists `~/.sbt`, sbt's disk action cache, and coursier with `actions/cache`; only one owner saves
    * ([[LocalCacheMode.Save]]). Disables setup-sbt's `disk-cache` and setup-java's `cache: sbt`, which would key the
    * same directories on `hashFiles` and race this.
    */
  case LocalDir

  /** Runs a `buchgr/bazel-remote` server as a workflow service, for one run only. The published port is gRPC. */
  case BazelRemoteSidecar(image: String, port: Int)

  /** A managed remote cache. The URI may be `grpc`, `grpcs`, `http`, or `https`. BuildBuddy, EngFlow, and NativeLink
    * are the usual `grpcs` endpoints.
    *
    * @param headerSecret
    *   the *name* of the secret whose value becomes the auth header.
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

/** What one job does with the [[CacheBackend.LocalDir]] build snapshot. One owner saves, everyone else restores:
  * per-job saves (about 300 MB each) evict the default branch's snapshot, and `restore-keys` then hand each job
  * whichever entry is newest rather than one that compiled what it needs.
  */
enum LocalCacheMode:
  /** Restore, then save under the `build` role: the builtin test, and `cache-rehydrate`. */
  case Save

  /** Never saves. The default for every capability. */
  case Restore

  /** No LocalDir steps at all: jobs that load sbt but compile nothing worth keeping, and remote backends. */
  case Off

  /** The `cache-mode` input of the generated `zipx-sbt-setup` composite. */
  def input: String = this match
    case Save    => "save"
    case Restore => "restore"
    case Off     => "off"
end LocalCacheMode

/** Which [[CacheBackend.LocalDir]] build snapshot a restoring Publish job starts from on a merge push, where
  * `cache-rehydrate` owns the save.
  */
enum SnapshotSource:
  /** Wait for `cache-rehydrate` and restore this run's save. The default: a job that only packages compiles nothing. */
  case ThisRun

  /** Start beside `cache-rehydrate` and restore the newest earlier save. For jobs that compile their own slice anyway,
    * such as a docker image matrix, where waiting costs more wall clock than the warmer snapshot saves.
    */
  case Prior
end SnapshotSource
