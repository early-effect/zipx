package zipx.cli

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Snapshot catalog files, probe `sbt --batch about` in a new JVM, restore on failure. */
object LoadVerify:

  final case class Snapshot(versions: String, plugins: Option[String], props: Option[String])

  def snapshot(versionsFile: Path): Snapshot =
    Snapshot(
      versions = read(versionsFile),
      plugins = readIfExists(sibling(versionsFile, "plugins.sbt")),
      props = readIfExists(sibling(versionsFile, "build.properties")),
    )

  def restore(versionsFile: Path, snap: Snapshot): Unit =
    write(versionsFile, snap.versions)
    val plugins = sibling(versionsFile, "plugins.sbt")
    snap.plugins match
      case Some(body) => write(plugins, body)
      case None       => Files.deleteIfExists(plugins)
    val props = sibling(versionsFile, "build.properties")
    snap.props match
      case Some(body) => write(props, body)
      case None       => Files.deleteIfExists(props)
  end restore

  def probe(root: Path, run: Path => Either[String, Unit] = defaultProbe): Either[String, Unit] =
    run(root)

  def applyWrite(
      versionsFile: Path,
      write: () => Either[String, Unit],
      verify: Boolean,
      run: Path => Either[String, Unit] = defaultProbe,
  ): Either[String, Option[String]] =
    val snap = snapshot(versionsFile)
    write().flatMap { _ =>
      if !verify then Right(None)
      else
        val root = Option(versionsFile.getParent).flatMap(p => Option(p.getParent)).getOrElse(Path.of("."))
        probe(root, run) match
          case Right(_)  => Right(None)
          case Left(err) =>
            restore(versionsFile, snap)
            Right(Some(err))
    }
  end applyWrite

  private def defaultProbe(root: Path): Either[String, Unit] =
    try
      val pb = new ProcessBuilder("sbt", "--batch", "about")
      pb.directory(root.toFile)
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val code = proc.waitFor()
      if code == 0 then Right(())
      else Left(out.linesIterator.find(_.contains("error")).getOrElse(s"sbt about exited $code"))
    catch case err: Exception => Left(s"could not run sbt about: ${err.getMessage}")

  private def sibling(file: Path, name: String): Path =
    Option(file.getParent).getOrElse(Path.of(".")).resolve(name)

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def readIfExists(path: Path): Option[String] =
    if Files.exists(path) then Some(read(path)) else None

  private def write(path: Path, body: String): Unit =
    Option(path.getParent).foreach(p => Files.createDirectories(p))
    Files.write(path, body.getBytes(StandardCharsets.UTF_8))
    ()
end LoadVerify
