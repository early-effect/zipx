package zipx.cli

import zipx.syntax.PluginsSbt
import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** ZIO entry for `cs launch rocks.earlyeffect:zipx-cli_3:… -- catalog …`.
  *
  * No Typelevel. No Cats Effect. Apply sits above the target sbt session.
  */
object Main extends ZIOAppDefault:

  def run: ZIO[ZIOAppArgs, Any, ExitCode] =
    for
      raw  <- ZIOAppArgs.getArgs.map(_.toList)
      code <- Args.parse(raw) match
        case Left(err)  => fail(err)
        case Right(cmd) => runCommand(cmd)
    yield code

  private def runCommand(cmd: CatalogCommand): UIO[ExitCode] =
    val io = cmd match
      case CatalogCommand.Update(yes, dryRun, verifyLoad, file) =>
        update(Path.of(file), yes = yes, dryRun = dryRun, verifyLoad = verifyLoad)
      case CatalogCommand.Generate(file) =>
        generate(Path.of(file))
      case CatalogCommand.Check(file) =>
        check(Path.of(file))
    io.foldZIO(fail, ZIO.succeed)

  private def update(file: Path, yes: Boolean, dryRun: Boolean, verifyLoad: Boolean): IO[String, ExitCode] =
    for
      _ <- say("zipx: catalog update (ZIO). Lookup and apply land in a later commit.")
      _ <- ZIO.when(dryRun)(say("zipx: dry-run; no files written."))
      _ <- ZIO.when(yes && !dryRun)(say(s"zipx: --yes against ${file.toString} (apply not wired yet)."))
      _ <- ZIO.when(verifyLoad)(say("zipx: --verify-load (probe not wired yet)."))
    yield ExitCode.success

  private def generate(file: Path): IO[String, ExitCode] =
    say(s"zipx: catalog generate from ${file.toString} (write not wired yet).").as(ExitCode.success)

  private def check(file: Path): IO[String, ExitCode] =
    val plugins = Option(file.getParent).getOrElse(Path.of(".")).resolve("plugins.sbt")
    for
      text <- readText(plugins)
      got  <- ZIO.fromEither(PluginsSbt.parse(text))
      _    <- say(s"zipx: ${got.size} plugin line(s) in ${plugins.toString}")
    yield ExitCode.success

  private def say(msg: String): IO[String, Unit] =
    Console.printLine(msg).mapError(err => s"zipx: ${err.getMessage}")

  private def fail(err: String): UIO[ExitCode] =
    Console.printLineError(err).orDie.as(ExitCode.failure)

  private def readText(path: Path): IO[String, String] =
    ZIO
      .attempt(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .mapError(err => s"zipx: cannot read ${path.toString}: ${err.getMessage}")
end Main
