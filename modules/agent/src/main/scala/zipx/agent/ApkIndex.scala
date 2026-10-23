package zipx.agent

import java.io.{ByteArrayInputStream, IOException}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration
import java.util.zip.GZIPInputStream

import zipx.core.{JdkFloor, WolfiToolchain}

import scala.util.Using

/** Wolfi `APKINDEX.tar.gz` for both published architectures. The index keeps every historical version, so a candidate
  * is the newest record, not the first.
  */
object ApkIndex:

  val X8664: String   = "x86_64"
  val Aarch64: String = "aarch64"

  def url(arch: String): String = s"https://packages.wolfi.dev/os/$arch/APKINDEX.tar.gz"

  def versions(packageName: String): Either[String, List[String]] =
    for
      amd <- index(X8664)
      arm <- index(Aarch64)
    yield versionsOf(amd, packageName).toSet.intersect(versionsOf(arm, packageName).toSet).toList

  def candidate(current: String, packageName: String): Either[String, Option[String]] =
    versions(packageName).flatMap(found => WolfiToolchain.newestApk(current, found))

  /** Newest apk of `openjdk-$major` whose upstream is a fixed OpenJDK build of that major. */
  def openJdkCandidate(current: String, major: Int): Either[String, Option[String]] =
    versions(s"openjdk-$major").flatMap { found =>
      val safe = found.filter(fixed(major))
      if safe.isEmpty then Left(s"zipx: no fixed openjdk-$major apk is published")
      else WolfiToolchain.newestApk(current, safe)
    }

  def versionsOf(text: String, packageName: String): List[String] =
    text.split("\n\n", -1).toList.flatMap { stanza =>
      val lines = stanza.split("\n", -1).toList
      val name  = lines.collectFirst { case line if line.startsWith("P: ") => line.stripPrefix("P: ") }
      val ver   = lines.collectFirst { case line if line.startsWith("V: ") => line.stripPrefix("V: ") }
      (name, ver) match
        case (Some(found), Some(version)) if found == packageName => Some(version)
        case _                                                    => None
    }

  /** `APKINDEX` member of a gzip-compressed ustar archive. */
  def apkIndexText(gzipped: Array[Byte]): Either[String, String] =
    try
      val tar = Using.resource(new GZIPInputStream(new ByteArrayInputStream(gzipped)))(_.readAllBytes())
      member(tar, "APKINDEX").map(bytes => new String(bytes, StandardCharsets.UTF_8))
    catch case _: IOException => Left("zipx: apk index is not gzip")

  private def fixed(major: Int)(raw: String): Boolean =
    WolfiToolchain.parseApk(raw).flatMap(apk => JdkFloor.parse(apk.upstream).toOption).exists { release =>
      release.feature.contains(major) &&
      JdkFloor.assess(JdkFloor.Vendor.OpenJdk, release) == JdkFloor.Verdict.Safe
    }

  private def index(arch: String): Either[String, String] =
    bytes(url(arch)).flatMap(apkIndexText)

  private def bytes(url: String): Either[String, Array[Byte]] =
    try
      val client = HttpClient
        .newBuilder()
        .connectTimeout(JDuration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
      val req = HttpRequest.newBuilder(URI.create(url)).timeout(JDuration.ofSeconds(30)).GET().build()
      val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
      if res.statusCode == 200 then Right(res.body())
      else Left(s"zipx: apk index HTTP ${res.statusCode}")
    catch
      case err: IOException =>
        Left(s"zipx: apk index lookup failed (${err.getClass.getSimpleName})")
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("zipx: apk index lookup interrupted")

  private def member(tar: Array[Byte], wanted: String): Either[String, Array[Byte]] =
    def go(offset: Int): Either[String, Array[Byte]] =
      if offset + 512 > tar.length then Left(s"zipx: apk index archive has no $wanted member")
      else
        val header = tar.slice(offset, offset + 512)
        if header.forall(_ == 0) then Left(s"zipx: apk index archive has no $wanted member")
        else
          octal(header, 124, 12) match
            case None =>
              Left("zipx: apk index archive header is not ustar")
            case Some(size) =>
              val data = offset + 512
              val end  = data + size
              val next = data + ((size + 511) / 512) * 512
              if end > tar.length || next < data then Left("zipx: apk index archive is truncated")
              else if tarName(header) == wanted then Right(tar.slice(data, end))
              else go(next)
        end if
    go(0)
  end member

  private def tarName(header: Array[Byte]): String =
    val name   = field(header, 0, 100)
    val prefix = field(header, 345, 155)
    if prefix.isEmpty then name else s"$prefix/$name"

  private def field(header: Array[Byte], at: Int, len: Int): String =
    val raw = header.slice(at, at + len)
    val end = raw.indexOf(0)
    val cut = if end < 0 then raw else raw.take(end)
    new String(cut, StandardCharsets.UTF_8).trim

  private def octal(header: Array[Byte], at: Int, len: Int): Option[Int] =
    val text = new String(header.slice(at, at + len), StandardCharsets.UTF_8).takeWhile(ch => ch != 0 && ch != ' ')
    if text.isEmpty then Some(0)
    else
      text.foldLeft[Option[Int]](Some(0)) { (acc, ch) =>
        acc.flatMap { n =>
          val digit = ch - '0'
          if digit < 0 || digit > 7 then None else Some(n * 8 + digit)
        }
      }
  end octal
end ApkIndex
