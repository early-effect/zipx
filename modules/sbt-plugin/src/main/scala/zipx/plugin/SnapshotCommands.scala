package zipx.plugin

import sbt.IO
import sbt.util.Logger
import zipx.core.*

import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import scala.util.control.NonFatal

/** `zipxSnapshotStatus`, `zipxSnapshotAdvance`, and `zipxPinRelease` against the release workflow's registry.
  *
  * A `file:` registry is read directly. An HTTP registry is fetched once under a lock, after a broken
  * `maven-metadata.xml` and its checksum sidecars are deleted, and retried once.
  */
object SnapshotCommands:

  def status(
      arg: String,
      coords: Seq[ZipxCoord],
      registry: ArtifactRegistry,
      scalaBin: String,
      scalaVer: String,
      cache: File,
      headers: Map[String, String],
      log: Logger,
  ): Unit =
    select(arg, coords, "zipxSnapshotStatus").foreach { lib =>
      val artifact = mavenName(lib, scalaBin, scalaVer)
      val report   = SnapshotPinAdvice.hold(lib.version) match
        case Some(SnapshotHold.Local(_)) =>
          SnapshotStatus.report(lib.artifact, lib.version, None, artifactPresent = false)
        case _ =>
          val pointer = pointerSha(registry, lib.group, artifact, lineOf(lib.version), cache, headers)
          val present = artifactPresent(registry, lib.group, artifact, lib.version, cache, headers)
          SnapshotStatus.report(lib.artifact, lib.version, pointer, present)
      log.info(report.fold(err => sys.error(s"zipx: $err"), SnapshotStatus.render))
    }

  /** Returns the rewritten catalog, or the same text when every selected pin is already current. */
  def advance(
      arg: String,
      coords: Seq[ZipxCoord],
      source: String,
      registry: ArtifactRegistry,
      scalaBin: String,
      scalaVer: String,
      cache: File,
      headers: Map[String, String],
      log: Logger,
  ): String =
    val named = arg.trim.nonEmpty
    select(arg, coords, "zipxSnapshotAdvance").foldLeft(source) { (src, lib) =>
      SnapshotPinAdvice.hold(lib.version) match
        case Some(SnapshotHold.Local(id)) =>
          val message = SnapshotRevisionError.Unstable(id).message
          if named then sys.error(s"zipx: $message") else log.info(s"zipx: ${lib.artifact} $message")
          src
        case Some(SnapshotHold.Pointer(_)) if !named =>
          log.info(s"zipx: ${lib.artifact} ${lib.version} is the snapshot pointer, not a pin.")
          src
        case _ =>
          val sha =
            pointerSha(registry, lib.group, mavenName(lib, scalaBin, scalaVer), lineOf(lib.version), cache, headers)
              .getOrElse(sys.error(s"zipx: the pointer for ${lib.artifact} has no ${SnapshotPointer.ShaElement}"))
          PinRewrite.advance(lib.version, sha) match
            case Left(err) =>
              sys.error(s"zipx: $err")
            case Right(None) =>
              log.info(s"zipx: ${lib.artifact} ${lib.version} is the latest snapshot")
              src
            case Right(Some(next)) =>
              val rewritten =
                PinRewrite
                  .replace(src, lib.group, lib.artifact, lib.version, next)
                  .fold(err => sys.error(s"zipx: $err"), identity)
              log.info(s"zipx: ${lib.artifact} ${lib.version} -> $next")
              rewritten
          end match
    }
  end advance

  def pinRelease(
      arg: String,
      coords: Seq[ZipxCoord],
      source: String,
      scalaBin: String,
      scalaVer: String,
      released: (String, String, String) => Boolean,
      log: Logger,
  ): String =
    if arg.trim.isEmpty then sys.error("zipx: zipxPinRelease takes the artifact name, or group:artifact")
    select(arg, coords, "zipxPinRelease").foldLeft(source) { (src, lib) =>
      val artifact = mavenName(lib, scalaBin, scalaVer)
      val line     = lineOf(lib.version)
      val onRepo   = line.exists(v => released(lib.group, artifact, v))
      PinRewrite.pinRelease(lib.version, onRepo) match
        case Left(err)                                    => sys.error(s"zipx: $err")
        case Right(next) if next == (lib.version: String) =>
          log.info(s"zipx: ${lib.artifact} is already $next")
          src
        case Right(next) =>
          val rewritten = PinRewrite
            .replace(src, lib.group, lib.artifact, lib.version, next)
            .fold(err => sys.error(s"zipx: $err"), identity)
          log.info(s"zipx: ${lib.artifact} ${lib.version} -> $next")
          rewritten
      end match
    }
  end pinRelease

  def defaultBranch(root: File): String =
    gitLine(root, "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
      .orElse(gitLine(root, "branch", "--show-current"))
      .getOrElse("main")

  private def select(arg: String, coords: Seq[ZipxCoord], command: String): List[Lib] =
    val pins   = coords.collect { case lib: Lib => lib }.filter(lib => SnapshotPinAdvice.hold(lib.version).isDefined)
    val tokens = arg.split("\\s+").toList.filter(_.nonEmpty)
    tokens match
      case Nil =>
        pins.toList
      case one :: Nil =>
        val matches = pins.filter(lib => (lib.artifact: String) == one || s"${lib.group}:${lib.artifact}" == one)
        matches match
          case Nil =>
            sys.error(s"zipx: '$one' is not a snapshot pin in the catalog")
          case several if several.sizeIs > 1 && !one.contains(":") =>
            sys.error(
              s"zipx: '$one' matches ${several.map(lib => s"${lib.group}:${lib.artifact}").mkString(", ")}. Name group:artifact."
            )
          case several =>
            several.toList
      case _ =>
        sys.error(s"zipx: $command takes one coordinate, or no arguments for every snapshot pin")
    end match
  end select

  private def lineOf(version: String): Option[String] =
    SnapshotPinAdvice
      .hold(version)
      .map {
        case SnapshotHold.Commit(line)  => line: String
        case SnapshotHold.Pointer(line) => line: String
        case SnapshotHold.Local(_)      => ""
      }
      .filter(_.nonEmpty)

  private def pointerSha(
      registry: ArtifactRegistry,
      organization: String,
      artifact: String,
      line: Option[String],
      cache: File,
      headers: Map[String, String],
  ): Option[GitSha] =
    line match
      case None      => None
      case Some(raw) =>
        ReleaseVersion.make(raw).toOption.flatMap { parsed =>
          val version = SnapshotPointer.pointerVersion(parsed)
          val pom     =
            fetch(
              registry.snapshotRepository,
              SnapshotPointer.pomRelative(organization, artifact, version),
              cache,
              headers,
            )
          val xml = pom.orElse {
            val metadata =
              fetch(
                registry.snapshotRepository,
                SnapshotPointer.metadataRelative(organization, artifact, version),
                cache,
                headers,
              )
            metadata.flatMap(body => SnapshotPointer.timestampedVersion(raw, body)).flatMap { stamped =>
              fetch(
                registry.snapshotRepository,
                SnapshotPointer.uniquePomRelative(organization, artifact, version, stamped),
                cache,
                headers,
              )
            }
          }
          xml.map(body => SnapshotPointer.shaFromPom(body).fold(err => sys.error(s"zipx: $err"), identity))
        }

  private def artifactPresent(
      registry: ArtifactRegistry,
      organization: String,
      artifact: String,
      revision: String,
      cache: File,
      headers: Map[String, String],
  ): Boolean =
    val id = SnapshotRevision.parse(revision) match
      case Right(pin: SnapshotRevision.Commit) => pin.id
      case _                                   => revision
    val stored = SnapshotPointer.storedRevision(id, registry)
    fetch(
      registry.snapshotRepository,
      SnapshotPointer.pomRelative(organization, artifact, stored),
      cache,
      headers,
    ).isDefined
  end artifactPresent

  private def mavenName(lib: Lib, scalaBin: String, scalaVer: String): String =
    lib.cross match
      case Cross.Java   => lib.artifact
      case Cross.Full   => s"${lib.artifact}_$scalaVer"
      case Cross.Binary => s"${lib.artifact}_$scalaBin"

  private def fetch(root: String, relative: String, cache: File, headers: Map[String, String]): Option[String] =
    val url = s"${root.stripSuffix("/")}/$relative"
    if url.startsWith("file:") then read(url, Map.empty).fold(err => sys.error(s"zipx: $err"), identity)
    else locked(cache)(read(url, headers)).fold(err => sys.error(s"zipx: $err"), identity)

  private def read(url: String, headers: Map[String, String]): Either[String, Option[String]] =
    if url.startsWith("file:") then
      val file = new File(URI.create(url))
      if file.isFile then Right(Some(IO.read(file))) else Right(None)
    else
      def once: Either[String, Option[String]] =
        HttpLookup.get(url, headers = headers, timeout = Duration.ofSeconds(15)) match
          case Left(err)                                            => Left(s"lookup $url: $err")
          case Right(res) if res.status == 200                      => Right(Some(res.body))
          case Right(res) if res.status == 404 || res.status == 410 => Right(None)
          case Right(res)                                           => Left(s"lookup $url: HTTP ${res.status}")
      once

  /** Delete a metadata file whose checksum sidecar does not match, then fetch. One retry. */
  private def locked(cache: File)(read: => Either[String, Option[String]]): Either[String, Option[String]] =
    IO.createDirectory(cache)
    val channel = FileChannel.open(new File(cache, ".lock").toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val held    = channel.lock()
    try
      clean(cache)
      read match
        case ok @ Right(_) => ok
        case Left(_)       =>
          clean(cache, force = true)
          read
    finally
      held.release()
      channel.close()
    end try
  end locked

  private def clean(cache: File, force: Boolean = false): Unit =
    val xml  = new File(cache, "maven-metadata.xml")
    val sha1 = sidecar(new File(cache, "maven-metadata.xml.sha1"))
    val md5  = sidecar(new File(cache, "maven-metadata.xml.md5"))
    val drop =
      force || SnapshotPointer.deleteBroken(
        xml.isFile,
        sha1.map(sum => xml.isFile && digest(xml, "SHA-1") == sum),
        md5.map(sum => xml.isFile && digest(xml, "MD5") == sum),
      )
    if drop then
      IO.delete(xml)
      IO.delete(new File(cache, "maven-metadata.xml.sha1"))
      IO.delete(new File(cache, "maven-metadata.xml.md5"))
  end clean

  private def sidecar(file: File): Option[String] =
    if file.isFile then IO.read(file).trim.split("\\s+").headOption.filter(_.nonEmpty) else None

  private def digest(file: File, algorithm: String): String =
    val hash = MessageDigest.getInstance(algorithm).digest(IO.readBytes(file))
    hash.map("%02x".format(_)).mkString

  private def gitLine(root: File, args: String*): Option[String] =
    val out  = new StringBuilder
    val code =
      try
        scala.sys.process
          .Process("git" +: args.toList, root)
          .!(scala.sys.process.ProcessLogger(line => out.append(line), _ => ()))
      catch case NonFatal(_) => 1
    if code == 0 then Option(out.toString.trim).filter(_.nonEmpty) else None
end SnapshotCommands
