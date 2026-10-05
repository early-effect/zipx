package zipx.core

import zipx.workflow.SecretName

/** How CI caches sbt's build state: persist sbt's on-disk action cache between runs, or point sbt at a Bazel-gRPC
  * endpoint.
  */
enum CacheBackend:
  /** Persists sbt's and coursier's caches plus `target/` with `actions/cache`; only one owner saves
    * ([[LocalCacheMode.Save]]). Disables setup-sbt's `disk-cache` and setup-java's `cache: sbt`, which would key the
    * same directories on `hashFiles` and race this.
    */
  case LocalDir

  /** Runs a `buchgr/bazel-remote` gRPC server as a workflow service, for one run only. */
  case BazelRemoteSidecar(image: String, port: Int)

  /** A managed gRPC backend: BuildBuddy, EngFlow, NativeLink.
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
