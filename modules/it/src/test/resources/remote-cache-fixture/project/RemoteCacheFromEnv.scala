import sbt.*
import sbt.Keys.*

/** Mirrors zipx plugin env wiring for the IT fixture.
  *
  * `Global / cacheVersion` is load-time only (safe for remote-cache keying). Override order:
  *   1. `.it-cache-version` in the project base (written by `writeItCacheVersion111` / `222`, then `reload`)
  *   2. `ZIPX_CACHE_VERSION` env
  *   3. JDK/OS FNV hash (same idea as ZipxPlugin)
  */
object RemoteCacheFromEnv extends AutoPlugin:
  override def trigger = allRequirements

  override def projectSettings: Seq[Setting[?]] =
    sys.env.get("ZIPX_REMOTE_CACHE").filter(_.nonEmpty) match
      case None         => Nil
      case Some(uriStr) =>
        Seq(
          Global / remoteCache  := Some(uri(uriStr)),
          Global / cacheVersion := {
            val itFile = baseDirectory.value / ".it-cache-version"
            if itFile.isFile then
              IO.read(itFile)
                .trim
                .toLongOption
                .getOrElse:
                  sys.error(s"invalid cache version in $itFile")
            else
              sys.env
                .get("ZIPX_CACHE_VERSION")
                .flatMap(_.toLongOption)
                .getOrElse:
                  cacheVersionFor(
                    sys.props.getOrElse("java.specification.version", "unknown"),
                    sys.props.getOrElse("os.name", "unknown").toLowerCase.split(' ').head,
                  )
            end if
          },
        )

  private def cacheVersionFor(jdk: String, os: String): Long =
    val input = s"jdk=$jdk;os=$os"
    var hash  = 0xcbf29ce484222325L
    val prime = 0x100000001b3L
    input.getBytes(java.nio.charset.StandardCharsets.UTF_8).foreach { b =>
      hash = (hash ^ (b & 0xff)) * prime
    }
    hash & Long.MaxValue
end RemoteCacheFromEnv
