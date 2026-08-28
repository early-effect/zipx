package zipx.plugin

import java.io.File
import sbt.*
import zipx.core.*
import zipx.syntax.CatalogSource
import zio.json.*

/** Local vs CI version assignment for Ship-backed modules. Not autoImport. */
object ModverRelease:

  final case class PushPayload(before: Option[String]) derives JsonCodec

  def versionString(
      projectId: String,
      ships: Seq[PublishedRow],
      branches: Seq[String],
      versionsFile: String,
      root: File,
      env: Map[String, String],
  ): String =
    Modver.rowForProject(projectId, ships) match
      case None      => "0.1.0-SNAPSHOT"
      case Some(pub) =>
        if isReleasingPush(env, branches) then
          val moved = movedOrFail(root, versionsFile, ships, env)
          val index = ShipIndex.from(ships)
          if Modver.thisCommitReleases(pub, moved, index) then pub.version: String
          else s"${pub.version}-ci"
        else s"${pub.version}-ci"

  def isReleasingPush(env: Map[String, String], branches: Seq[String]): Boolean =
    env.get("GITHUB_ACTIONS").contains("true") &&
      env.get("GITHUB_EVENT_NAME").contains("push") &&
      env.get("GITHUB_REF").exists(ref => branches.exists(b => ref == s"refs/heads/$b"))

  def movedOrFail(
      root: File,
      versionsFile: String,
      ships: Seq[PublishedRow],
      env: Map[String, String],
  ): MovedRows =
    val before = readBefore(env).fold(err => sys.error(s"zipx: $err"), identity)
    val shown  = gitShow(root, before, versionsFile)
    val prev   =
      Modver.previousIndex(shown, src => CatalogSource.parse(src, versionsFile).map(c => ShipIndex.from(c.ships)))
    val index = ShipIndex.from(ships)
    Modver.movedRows(index, prev).fold(err => sys.error(s"zipx: $err"), identity)
  end movedOrFail

  def readBefore(env: Map[String, String]): Either[String, String] =
    env.get("GITHUB_EVENT_PATH") match
      case None       => Left("GITHUB_EVENT_PATH is missing on a default-branch push")
      case Some(path) =>
        val file = new File(path)
        if !file.exists then Left(s"GITHUB_EVENT_PATH '$path' is not readable")
        else
          val raw = IO.read(file)
          raw.fromJson[PushPayload] match
            case Left(err)                => Left(s"GITHUB_EVENT_PATH is not a push payload: $err")
            case Right(PushPayload(None)) => Left("push payload has no before field")
            case Right(PushPayload(Some(sha))) if Modver.isZeroSha(sha) =>
              Left("github.event.before is all-zero; refusing to guess the publish set")
            case Right(PushPayload(Some(sha))) => Right(sha)

  def gitShow(root: File, sha: String, rel: String): Either[String, Option[String]] =
    val out  = scala.collection.mutable.ListBuffer.empty[String]
    val err  = scala.collection.mutable.ListBuffer.empty[String]
    val code =
      scala.sys.process
        .Process(Seq("git", "show", s"$sha:$rel"), root)
        .!(scala.sys.process.ProcessLogger(out += _, err += _))
    if code == 0 then Right(Some(out.mkString("\n")))
    else if err.mkString.contains("exists on disk, but not in") || err.mkString.contains("does not exist") ||
      err.mkString.contains("exists on disk but not in") || code == 128
    then Right(None)
    else Left(s"git show $sha:$rel failed")
  end gitShow
end ModverRelease
