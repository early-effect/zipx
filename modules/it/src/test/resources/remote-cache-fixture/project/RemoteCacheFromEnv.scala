import sbt.*
import sbt.Keys.*

/** Mirrors zipx plugin env wiring: ZIPX_REMOTE_CACHE (+ optional ZIPX_CACHE_VERSION override for IT). */
object RemoteCacheFromEnv extends AutoPlugin:
  override def trigger = allRequirements

  override def globalSettings: Seq[Setting[?]] =
    sys.env.get("ZIPX_REMOTE_CACHE").filter(_.nonEmpty) match
      case None => Nil
      case Some(uriStr) =>
        val version: Long = sys.env.get("ZIPX_CACHE_VERSION").flatMap(_.toLongOption).getOrElse {
          cacheVersionFor(
            sys.props.getOrElse("java.specification.version", "unknown"),
            sys.props.getOrElse("os.name", "unknown").toLowerCase.split(' ').head,
          )
        }
        Seq(
          Global / remoteCache    := Some(uri(uriStr)),
          Global / cacheVersion   := version,
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
