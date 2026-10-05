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

  /** `names` gives the artifact names a row resolves as, one per platform the build uses it on. */
  def status(
      arg: String,
      coords: Seq[ZipxCoord],
      registry: ArtifactRegistry,
      names: Lib => List[String],
      cache: File,
      headers: Map[String, String],
      log: Logger,
  ): Unit =
    val command = "zipxSnapshotStatus"
    families(select(rowOf(arg, command), pinsOf(coords), command), coords).foreach { family =>
      val report = SnapshotPinAdvice.hold(family.version) match
        case Some(SnapshotHold.Local(_)) =>
          SnapshotStatus.report(family.literal, family.version, PointerRead.Absent, artifactPresent = false)
        case _ =>
          val pointer = lineOf(family.version).fold(PointerRead.Absent)(
            familyPointer(registry, family, names, cache, headers, _)
          )
          val present = family.members.exists { lib =>
            names(lib).exists(artifactPresent(registry, lib.group, _, lib.version, cache, headers))
          }
          SnapshotStatus.report(family.literal, family.version, pointer, present)
      log.info(report.fold(err => sys.error(s"zipx: $err"), SnapshotStatus.render))
    }
  end status

  /** Returns the rewritten catalog, or the same text when every selected pin is already current. `<row> <line>` moves
    * the row to that line's latest commit; with no line, each pin stays on its own.
    */
  def advance(
      arg: String,
      coords: Seq[ZipxCoord],
      source: String,
      registry: ArtifactRegistry,
      names: Lib => List[String],
      cache: File,
      headers: Map[String, String],
      log: Logger,
  ): String =
    val (row, target) = tokensOf(arg) match
      case Nil                => (None, None)
      case one :: Nil         => (Some(one), None)
      case one :: line :: Nil =>
        ReleaseVersion.make(line) match
          case Right(parsed) => (Some(one), Some(parsed))
          case Left(_)       => sys.error(s"zipx: '$line' is not a release line. $AdvanceUsage")
      case _ => sys.error(s"zipx: $AdvanceUsage")
    target match
      case Some(line) =>
        families(select(row, libsOf(coords), "zipxSnapshotAdvance"), coords).foldLeft(source) { (src, family) =>
          val sha = pointerOf(registry, family, names, cache, headers, line)
          PinRewrite.moveTo(family.version, line, sha) match
            case Left(err)   => sys.error(s"zipx: $err")
            case Right(None) =>
              log.info(s"zipx: ${family.literal} ${family.version} is the latest snapshot of $line")
              src
            case Right(Some(next)) => rewrite(src, family, next, log)
        }
      case None => advanceInPlace(row, coords, source, registry, names, cache, headers, log)
    end match
  end advance

  private def advanceInPlace(
      row: Option[String],
      coords: Seq[ZipxCoord],
      source: String,
      registry: ArtifactRegistry,
      names: Lib => List[String],
      cache: File,
      headers: Map[String, String],
      log: Logger,
  ): String =
    val named = row.isDefined
    families(select(row, pinsOf(coords), "zipxSnapshotAdvance"), coords).foldLeft(source) { (src, family) =>
      def onLine(line: ReleaseVersion): String =
        PinRewrite.advance(family.version, pointerOf(registry, family, names, cache, headers, line)) match
          case Left(err) =>
            sys.error(s"zipx: $err")
          case Right(None) =>
            log.info(s"zipx: ${family.literal} ${family.version} is the latest snapshot")
            src
          case Right(Some(next)) =>
            rewrite(src, family, next, log)
      SnapshotPinAdvice.hold(family.version) match
        case Some(SnapshotHold.Local(id)) =>
          val message = SnapshotRevisionError.Unstable(id).message
          if named then sys.error(s"zipx: $message") else log.info(s"zipx: ${family.literal} $message")
          src
        case Some(SnapshotHold.Pointer(_)) if !named =>
          log.info(s"zipx: ${family.literal} ${family.version} is the snapshot pointer, not a pin.")
          src
        case Some(SnapshotHold.Pointer(line)) => onLine(line)
        case Some(SnapshotHold.Commit(line))  => onLine(line)
        case None                             => src
      end match
    }
  end advanceInPlace

  private inline val AdvanceUsage =
    "zipxSnapshotAdvance takes a row and, optionally, a line: sbt 'zipxSnapshotAdvance widgets 1.5.0'"

  private def rewrite(source: String, family: Family, next: String, log: Logger): String =
    val rewritten = PinRewrite
      .replace(source, family.group, family.literal, family.version, next)
      .fold(err => sys.error(s"zipx: $err"), identity)
    log.info(s"zipx: ${family.literal} ${family.version} -> $next")
    rewritten

  /** The first family artifact with a pointer for `line` speaks for all of them: one commit publishes them all. */
  private def familyPointer(
      registry: ArtifactRegistry,
      family: Family,
      names: Lib => List[String],
      cache: File,
      headers: Map[String, String],
      line: ReleaseVersion,
  ): PointerRead =
    PointerRead.first(family.members.iterator.map(rowPointer(registry, _, names, cache, headers, line)))

  private def pointerOf(
      registry: ArtifactRegistry,
      family: Family,
      names: Lib => List[String],
      cache: File,
      headers: Map[String, String],
      line: ReleaseVersion,
  ): GitSha =
    val pointer = SnapshotPointer.pointerVersion(line)
    familyPointer(registry, family, names, cache, headers, line) match
      case PointerRead.Names(sha) => sha
      case PointerRead.Absent     =>
        sys.error(s"zipx: ${registry.snapshotRepository} has no $pointer pointer for ${family.literal}")
      case PointerRead.Unnamed =>
        sys.error(
          s"zipx: the $pointer pointer for ${family.literal} names no commit (no ${SnapshotPointer.ShaElement}). " +
            "Publish the line with sbt zipxSnapshotPublish, or pin a commit by its stored id."
        )
  end pointerOf

  def pinRelease(
      arg: String,
      coords: Seq[ZipxCoord],
      source: String,
      names: Lib => List[String],
      released: (String, String, String) => Boolean,
      log: Logger,
  ): String =
    val command = "zipxPinRelease"
    val row     = rowOf(arg, command).orElse(sys.error(s"zipx: $command takes the artifact name, or group:artifact"))
    families(select(row, pinsOf(coords), command), coords).foldLeft(source) { (src, family) =>
      val line   = lineOf(family.version)
      val onRepo = line.exists(v => family.members.exists(lib => names(lib).exists(released(lib.group, _, v))))
      PinRewrite.pinRelease(family.version, onRepo) match
        case Left(err)                                       => sys.error(s"zipx: $err")
        case Right(next) if next == (family.version: String) =>
          log.info(s"zipx: ${family.literal} is already $next")
          src
        case Right(next) =>
          val rewritten = PinRewrite
            .replace(src, family.group, family.literal, family.version, next)
            .fold(err => sys.error(s"zipx: $err"), identity)
          log.info(s"zipx: ${family.literal} ${family.version} -> $next")
          rewritten
      end match
    }
  end pinRelease

  /** One `Lib(...)` literal in the catalog source, and every row that shares its version through `.mod`. */
  private final case class Family(group: GroupId, literal: ArtifactId, version: DepVersion, members: ::[Lib])

  /** A `.mod` row is rewritten through its family's literal. */
  private def families(selected: List[Lib], coords: Seq[ZipxCoord]): List[Family] =
    val libs                            = coords.collect { case lib: Lib => lib }.toList
    def literalOf(lib: Lib): ArtifactId = lib.family.getOrElse(lib.artifact)
    selected.map(lib => (lib.group, literalOf(lib))).distinct.flatMap { (group, literal) =>
      libs.filter(lib => lib.group == group && literalOf(lib) == literal) match
        case head :: tail => Some(Family(group, literal, head.version, ::(head, tail)))
        case Nil          => None
    }

  def defaultBranch(root: File): String =
    gitLine(root, "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
      .orElse(gitLine(root, "branch", "--show-current"))
      .getOrElse("main")

  private def libsOf(coords: Seq[ZipxCoord]): List[Lib] = coords.collect { case lib: Lib => lib }.toList

  private def pinsOf(coords: Seq[ZipxCoord]): List[Lib] =
    libsOf(coords).filter(lib => SnapshotPinAdvice.hold(lib.version).isDefined)

  /** The row a command names: `artifact` or `group:artifact`, or none for every candidate. */
  private def rowOf(arg: String, command: String): Option[String] =
    tokensOf(arg) match
      case Nil        => None
      case one :: Nil => Some(one)
      case _          => sys.error(s"zipx: $command takes one coordinate, or no arguments for every snapshot pin")

  private def tokensOf(arg: String): List[String] = arg.split("\\s+").toList.filter(_.nonEmpty)

  private def select(row: Option[String], candidates: List[Lib], command: String): List[Lib] =
    row match
      case None      => candidates
      case Some(one) =>
        candidates.filter(lib => (lib.artifact: String) == one || s"${lib.group}:${lib.artifact}" == one) match
          case Nil =>
            sys.error(s"zipx: '$one' is not a row $command can take")
          case several if several.sizeIs > 1 && !one.contains(":") =>
            sys.error(
              s"zipx: '$one' matches ${several.map(lib => s"${lib.group}:${lib.artifact}").mkString(", ")}. Name group:artifact."
            )
          case several =>
            several

  /** A local build has no line: no registry holds it. */
  private def lineOf(version: String): Option[ReleaseVersion] =
    SnapshotPinAdvice.hold(version).flatMap {
      case SnapshotHold.Commit(line)  => Some(line)
      case SnapshotHold.Pointer(line) => Some(line)
      case SnapshotHold.Local(_)      => None
    }

  /** The first artifact with a pointer for `line` speaks for the row: every platform publishes from one commit. */
  private def rowPointer(
      registry: ArtifactRegistry,
      lib: Lib,
      names: Lib => List[String],
      cache: File,
      headers: Map[String, String],
      line: ReleaseVersion,
  ): PointerRead =
    PointerRead.first(names(lib).iterator.map(pointerSha(registry, lib.group, _, line, cache, headers)))

  private def pointerSha(
      registry: ArtifactRegistry,
      organization: String,
      artifact: String,
      line: ReleaseVersion,
      cache: File,
      headers: Map[String, String],
  ): PointerRead =
    PointerRead.of(
      pomUnder(registry, organization, artifact, SnapshotPointer.pointerVersion(line), line, cache, headers)
    )

  private def artifactPresent(
      registry: ArtifactRegistry,
      organization: String,
      artifact: String,
      revision: String,
      cache: File,
      headers: Map[String, String],
  ): Boolean =
    def present(pin: SnapshotRevision.Commit): Boolean =
      pomUnder(registry, organization, artifact, pin.storedId, pin.id, cache, headers).isDefined
    DepRevision.of(revision) match
      case DepRevision.Commit(pin)         => present(pin)
      case DepRevision.UnstoredCommit(pin) => present(pin)
      case _                               => false
  end artifactPresent

  /** A POM under `version`: the plain file, or sbt's unique snapshot named by that directory's metadata. `base` is the
    * version without `-SNAPSHOT`, which the timestamp replaces in the file name.
    */
  private def pomUnder(
      registry: ArtifactRegistry,
      organization: String,
      artifact: String,
      version: String,
      base: String,
      cache: File,
      headers: Map[String, String],
  ): Option[String] =
    def get(relative: String) = fetch(registry.snapshotRepository, relative, cache, headers)
    get(SnapshotPointer.pomRelative(organization, artifact, version)).orElse {
      get(SnapshotPointer.metadataRelative(organization, artifact, version))
        .flatMap(body => SnapshotPointer.timestampedVersion(base, body))
        .flatMap(stamped => get(SnapshotPointer.uniquePomRelative(organization, artifact, version, stamped)))
    }
  end pomUnder

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
