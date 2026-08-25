package zipx.cli

/** Argv for `zipx catalog …`. No third-party parser. */
enum CatalogCommand:
  case Update(yes: Boolean, dryRun: Boolean, verifyLoad: Boolean, file: String)
  case Generate(file: String)
  case Check(file: String)

object Args:

  val DefaultFile: String = zipx.core.ZipxCatalog.DefaultVersionsFile

  def parse(args: List[String]): Either[String, CatalogCommand] =
    args match
      case "catalog" :: rest => parseCatalog(rest)
      case Nil               => Left(usage)
      case other             => Left(s"zipx: unknown command '${other.head}'.\n$usage")

  private def parseCatalog(args: List[String]): Either[String, CatalogCommand] =
    args match
      case "update" :: rest =>
        flags(rest).map { f =>
          CatalogCommand.Update(yes = f.yes, dryRun = f.dryRun, verifyLoad = f.verifyLoad, file = f.file)
        }
      case "generate" :: rest => flags(rest).map(f => CatalogCommand.Generate(f.file))
      case "check" :: rest    => flags(rest).map(f => CatalogCommand.Check(f.file))
      case Nil                => Left(s"zipx: catalog needs update, generate, or check.\n$usage")
      case other              => Left(s"zipx: unknown catalog command '${other.head}'.\n$usage")

  private final case class Flags(yes: Boolean, dryRun: Boolean, verifyLoad: Boolean, file: String)

  private def flags(args: List[String]): Either[String, Flags] =
    def loop(rest: List[String], acc: Flags): Either[String, Flags] =
      rest match
        case Nil                               => Right(acc)
        case ("--yes" | "yes") :: more         => loop(more, acc.copy(yes = true))
        case ("--dry-run" | "dry-run") :: more => loop(more, acc.copy(dryRun = true))
        case "--verify-load" :: more           => loop(more, acc.copy(verifyLoad = true))
        case "--file" :: path :: more          => loop(more, acc.copy(file = path))
        case "--file" :: Nil                   => Left("zipx: --file needs a path")
        case unknown :: _                      => Left(s"zipx: unknown flag '$unknown'.\n$usage")
    loop(args, Flags(yes = false, dryRun = false, verifyLoad = false, file = DefaultFile))
  end flags

  val usage: String =
    """usage:
      |  zipx catalog update [--yes|--dry-run] [--verify-load] [--file project/ZipxVersions.scala]
      |  zipx catalog generate [--file project/ZipxVersions.scala]
      |  zipx catalog check [--file project/ZipxVersions.scala]
      |""".stripMargin
end Args
