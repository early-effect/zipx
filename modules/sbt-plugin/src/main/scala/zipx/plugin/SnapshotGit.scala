package zipx.plugin

import zipx.core.{DirtyStamp, GitSha, SnapshotRevisionError}

import java.io.File
import java.util.Date

/** Git facts a snapshot publish needs. The id itself is [[zipx.core.SnapshotRevision]]. */
object SnapshotGit:

  enum Tree:
    case Clean(full: GitSha)
    case Dirty(full: GitSha, at: DirtyStamp)
    case Missing(at: DirtyStamp)

  def describe(root: File): Either[SnapshotRevisionError, Tree] =
    val stamp = dirtyNow
    if git(root, "rev-parse", "--is-inside-work-tree").isEmpty then Right(Tree.Missing(stamp))
    else
      git(root, "rev-parse", "HEAD").flatMap(raw => GitSha.make(raw.toLowerCase).toOption) match
        case None =>
          Right(Tree.Missing(stamp))
        case Some(full) =>
          val dirty = git(root, "status", "--porcelain").exists(_.nonEmpty)
          if dirty then Right(Tree.Dirty(full, stamp))
          else
            git(root, "rev-parse", "--short=12", "HEAD") match
              case Some(short) if short.length == 12 && short == full.take(12) =>
                Right(Tree.Clean(full))
              case Some(short) =>
                Left(SnapshotRevisionError.NotCommitPin(s"git abbrev $short is not 12 unique hex"))
              case None =>
                Right(Tree.Missing(stamp))
    end if
  end describe

  def porcelain(root: File): String =
    git(root, "status", "--porcelain").getOrElse("")

  private def dirtyNow: DirtyStamp =
    val now = new Date
    val raw = f"$now%tY$now%tm$now%td-$now%tH$now%tM"
    DirtyStamp.from(raw).fold(err => sys.error(s"zipx: ${err.message}"), identity)

  private def git(root: File, args: String*): Option[String] =
    try
      val out  = new StringBuilder
      val code = scala.sys.process
        .Process("git" +: args, root)
        .!(
          scala.sys.process.ProcessLogger(
            line =>
              if out.nonEmpty then out += '\n';
              out ++= line
            ,
            _ => (),
          )
        )
      Option.when(code == 0)(out.toString.trim).filter(_.nonEmpty)
    catch case scala.util.control.NonFatal(_) => None
end SnapshotGit
