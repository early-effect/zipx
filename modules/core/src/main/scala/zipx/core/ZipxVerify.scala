package zipx.core

/** Fail-closed default for leftover Scala Steward YAML after zipx stopped generating it. */
enum LeftoverOpt:
  case Fail
  case Warn(reason: String)

object LeftoverOpt:
  val WorkflowPath: String   = ".github/workflows/zipx-scala-steward.yml"
  val GithubConfPath: String = ".github/.scala-steward.conf"
  val RepoConfPath: String   = ".scala-steward.conf"

  def validate(opt: LeftoverOpt): Either[String, LeftoverOpt] =
    opt match
      case Warn(reason) if reason.trim.isEmpty =>
        Left(
          "zipxLeftoverSteward Warn reason must be non-empty. Example:\n" +
            """zipxLeftoverSteward := LeftoverOpt.Warn("deleting zipx-scala-steward.yml Monday after catalog PR lands")"""
        )
      case other => Right(other)

  def leftoverMessage(files: List[String], reason: Option[String]): String =
    val listed = files.mkString(", ")
    val why    = reason.map(r => s": $r").getOrElse("")
    s"leftover $listed$why. zipx no longer generates a bot workflow. Delete those files. " +
      "Versions live in project/ZipxVersions.scala; bump with sbt \"zipxDepUpdate yes\". " +
      "To keep the files briefly: zipxLeftoverSteward := LeftoverOpt.Warn(\"reason\")."
end LeftoverOpt

enum VerifyOpt:
  case On
  case Skip(reason: String)

object VerifyOpt:
  def validate(opt: VerifyOpt, gate: String): Either[String, VerifyOpt] =
    opt match
      case Skip(reason) if reason.trim.isEmpty =>
        Left(
          s"zipxVerify.$gate Skip reason must be non-empty. Example:\n" +
            s"""zipxVerify := ZipxVerify.Strict.copy($gate = VerifyOpt.Skip("hotfix: <why>"))"""
        )
      case other => Right(other)

final case class ZipxVerify(
    fmt: VerifyOpt = VerifyOpt.On,
    workflowCheck: VerifyOpt = VerifyOpt.On,
    advisories: VerifyOpt = VerifyOpt.On,
)

object ZipxVerify:
  val Strict: ZipxVerify = ZipxVerify()

  def validate(v: ZipxVerify): Either[String, ZipxVerify] =
    for
      _ <- VerifyOpt.validate(v.fmt, "fmt")
      _ <- VerifyOpt.validate(v.workflowCheck, "workflowCheck")
      _ <- VerifyOpt.validate(v.advisories, "advisories")
    yield v
end ZipxVerify
