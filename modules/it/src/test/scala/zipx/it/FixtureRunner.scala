package zipx.it

import zipx.core.RemoteCacheProof

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.Comparator
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/** Runs the bundled remote-cache fixture under an isolated HOME with ZIPX_REMOTE_CACHE set. */
object FixtureRunner:

  /** Classpath resource root for the tiny sbt fixture. */
  private val FixtureResource = "remote-cache-fixture"

  final case class RunResult(exitCode: Int, out: String, elapsedMs: Long):
    def ok: Boolean = exitCode == 0

  def dockerAvailable: Boolean =
    Try(Process(Seq("docker", "info")).!(ProcessLogger(_ => (), _ => ())) == 0).getOrElse(false)

  /** Live IT when `-Dzipx.it.docker=1` (set on the `it` module) or `ZIPX_IT_DOCKER=1`, and Docker is up. */
  def shouldRunLiveIt: Boolean =
    val enabled =
      sys.props.get("zipx.it.docker").contains("1") || sys.env.get("ZIPX_IT_DOCKER").contains("1")
    if !enabled then false
    else if !dockerAvailable then sys.error("zipx.it.docker / ZIPX_IT_DOCKER enabled but docker is not available")
    else true

  /** Materialize fixture into a temp dir (unique per call). */
  def materializeFixture(): Path =
    val root = Files.createTempDirectory("zipx-remote-cache-fixture-")
    copyResourceTree(FixtureResource, root)
    root

  def runSbt(
      fixtureDir: Path,
      grpcUri: String,
      tasks: Seq[String],
      home: Path,
      extraEnv: Map[String, String] = Map.empty,
      cacheVersionOverride: Option[Long] = None,
  ): RunResult =
    Files.createDirectories(home)
    val log    = new StringBuilder
    val logger = ProcessLogger(
      line =>
        log.append(line).append('\n'); ()
      ,
      line =>
        log.append(line).append('\n'); (),
    )
    val env = scala.collection.mutable.Map.from(sys.env) ++ extraEnv ++ Map(
      RemoteCacheProof.envUri -> grpcUri,
      "HOME"                  -> home.toAbsolutePath.toString,
      "SBT_OPTS"              -> "-Xmx512m",
    )
    cacheVersionOverride.foreach(v => env("ZIPX_CACHE_VERSION") = v.toString)

    // Avoid thin-client attach to an unrelated sbt server; one scripted command string.
    val script  = tasks.mkString("; ")
    val cmd     = Seq("sbt", "-Dsbt.client=false", "--batch", script)
    val started = System.nanoTime()
    val code    =
      Process(cmd, fixtureDir.toFile, env.toSeq*).!(logger)
    val elapsed = (System.nanoTime() - started).nanos.toMillis
    RunResult(code, log.toString, elapsed)
  end runSbt

  def wipeLocalCaches(fixtureDir: Path, home: Path): Unit =
    deleteTree(fixtureDir.resolve("target"))
    deleteTree(fixtureDir.resolve("project/target"))
    deleteTree(home.resolve(".cache/sbt"))

  def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      try
        Files.walk(p).sorted(Comparator.reverseOrder()).forEach { f =>
          try Files.deleteIfExists(f)
          catch case _: Exception => ()
        }
      catch case _: Exception => ()
  end deleteTree

  private def copyResourceTree(resourceRoot: String, dest: Path): Unit =
    val cl = Thread.currentThread().getContextClassLoader
    Option(cl.getResource(resourceRoot)) match
      case None =>
        sys.error(s"Missing classpath resource: $resourceRoot")
      case Some(url) if url.getProtocol == "file" =>
        val src = Path.of(url.toURI)
        Files.walk(src).forEach { p =>
          val rel = src.relativize(p)
          val out = dest.resolve(rel.toString)
          if Files.isDirectory(p) then Files.createDirectories(out)
          else
            Files.createDirectories(out.getParent)
            Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING)
        }
      case Some(url) if url.getProtocol == "jar" =>
        val fsUrl = java.net.URI.create(url.toString.split("!").head)
        val fs    = java.nio.file.FileSystems.newFileSystem(fsUrl, Map.empty[String, Any].asJava)
        try
          val src = fs.getPath("/" + resourceRoot)
          Files.walk(src).forEach { p =>
            val rel = src.relativize(p).toString
            val out = dest.resolve(rel)
            if Files.isDirectory(p) then Files.createDirectories(out)
            else
              Files.createDirectories(out.getParent)
              Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING)
          }
        finally fs.close()
        end try
      case Some(url) =>
        sys.error(s"Unsupported resource URL: $url")
    end match
  end copyResourceTree

  def writeUtf8(path: Path, content: String): Unit =
    Files.createDirectories(path.getParent)
    Files.writeString(path, content, StandardCharsets.UTF_8)

end FixtureRunner
