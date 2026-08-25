package zipx.cli

import zio.*

import java.nio.file.Path

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
      plan <- ZIO.fromEither(CatalogOps.planUpdate(file))
      _    <- say(s"zipx catalog update:\n${CatalogOps.formatPlan(plan)}")
      _    <-
        if CatalogOps.nothingToDo(plan) then say("zipx: no catalog updates to apply")
        else if dryRun || !yes then say("zipx: pass --yes to apply (dry-run lists only).")
        else
          ZIO
            .fromEither(
              LoadVerify.applyWrite(file, () => CatalogOps.writeUpdate(file, plan), verify = verifyLoad)
            )
            .flatMap {
              case None =>
                say(s"zipx: wrote ${file.toString}")
              case Some(err) =>
                say(s"zipx: load failed after apply; restored snapshot ($err)")
            }
    yield ExitCode.success

  private def generate(file: Path): IO[String, ExitCode] =
    ZIO.fromEither(CatalogOps.generate(file)) *>
      say(s"zipx: wrote plugins.sbt from ${file.toString}").as(ExitCode.success)

  private def check(file: Path): IO[String, ExitCode] =
    ZIO.fromEither(CatalogOps.check(file)) *>
      say(s"zipx: ${file.toString} plugin list is up to date").as(ExitCode.success)

  private def say(msg: String): IO[String, Unit] =
    Console.printLine(msg).mapError(err => s"zipx: ${err.getMessage}")

  private def fail(err: String): UIO[ExitCode] =
    Console.printLineError(err).orDie.as(ExitCode.failure)
end Main
