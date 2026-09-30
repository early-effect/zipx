package zipx.plugin

import java.io.File

object GitFiles:

  def show(root: File, sha: String, rel: String): Either[String, Option[String]] =
    if !succeeds(root, "cat-file", "-e", s"$sha^{commit}") then Left(s"'$sha' is not a commit")
    else if !succeeds(root, "cat-file", "-e", s"$sha:$rel") then Right(None)
    else
      val out  = scala.collection.mutable.ListBuffer.empty[String]
      val code =
        scala.sys.process
          .Process(Seq("git", "show", s"$sha:$rel"), root)
          .!(scala.sys.process.ProcessLogger(out += _, _ => ()))
      if code == 0 then Right(Some(out.mkString("\n"))) else Left(s"git show $sha:$rel exited $code")

  private def succeeds(root: File, args: String*): Boolean =
    scala.sys.process.Process("git" +: args, root).!(scala.sys.process.ProcessLogger(_ => (), _ => ())) == 0
end GitFiles
