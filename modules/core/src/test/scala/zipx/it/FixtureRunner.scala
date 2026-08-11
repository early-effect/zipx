package zipx.it

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.ToStringConsumer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import zipx.core.RemoteCacheProof

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Duration
import java.util.Comparator
import scala.jdk.CollectionConverters.*

/** Materializes the classpath fixture and runs sbt **inside** a plain Testcontainers `GenericContainer`
  * (`RemoteCacheProof.sbtFixtureImage`) on the same Docker network as bazel-remote.
  *
  * No host `Process("sbt")`: the fixture does not depend on setup-sbt / PATH on the runner.
  */
object FixtureRunner:

  private val FixtureResource = "remote-cache-fixture"
  private val FixtureMount    = "/fixture"

  final case class RunResult(exitCode: Int, out: String, elapsedMs: Long):
    def ok: Boolean = exitCode == 0

    def afterWipe: String =
      val marker = "ZIPX_IT_WIPE"
      out.indexOf(marker) match
        case -1 => ""
        case i  => out.substring(i + marker.length)

    def phaseMs: Option[(Long, Long)] =
      val stamps =
        raw"ZIPX_IT_STAMP (\d+)".r.findAllMatchIn(out).map(_.group(1).toLong).toVector
      if stamps.size >= 3 then Some((stamps(1) - stamps(0), stamps(2) - stamps(1)))
      else None
  end RunResult

  /** Materialize fixture into a temp dir (unique per call). */
  def materializeFixture(): Path =
    val root = Files.createTempDirectory("zipx-remote-cache-fixture-")
    copyResourceTree(FixtureResource, root)
    root

  /** One-shot sbt container: put/get scripts, isolated HOME inside the container. */
  def runSbt(
      network: Network,
      fixtureDir: Path,
      script: String,
  ): RunResult =
    val logs                   = new ToStringConsumer
    val c: GenericContainer[?] =
      new GenericContainer(DockerImageName.parse(RemoteCacheProof.sbtFixtureImage))
    c.withNetwork(network)
    // Copy (not bind): Docker Desktop on macOS often cannot mount /var/folders or /tmp; a container-local
    // copy is writable for wipeItCaches and works the same on GHA Linux.
    c.withCopyFileToContainer(
      MountableFile.forHostPath(fixtureDir.toAbsolutePath.toString),
      FixtureMount,
    )
    c.withWorkingDirectory(FixtureMount)
    c.withEnv(RemoteCacheProof.envUri, RemoteCacheProof.grpcServiceUri)
    c.withEnv("HOME", "/tmp/sbt-home")
    c.withEnv("SBT_OPTS", "-Xmx512m")
    // Foreground server: thin client reuses a background server across fixtures and breaks isolation.
    c.withCommand(
      "sbt",
      "--server",
      "--batch",
      script,
    )
    c.withStartupCheckStrategy(
      new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(8))
    )
    c.withLogConsumer(logs)
    c.withStartupTimeout(Duration.ofMinutes(8))

    val started = System.nanoTime()
    try
      try c.start()
      catch
        case e: Throwable =>
          throw new RuntimeException(
            s"Remote-cache IT requires Docker; could not run sbt fixture (${RemoteCacheProof.sbtFixtureImage}): ${e.getMessage}\n${logs.toUtf8String}",
            e,
          )
      val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
      val exit    =
        Option(c.getCurrentContainerInfo)
          .map(_.getState.getExitCodeLong.longValue.toInt)
          .getOrElse(-1)
      RunResult(exit, logs.toUtf8String, elapsed)
    finally
      try c.stop()
      catch case _: Throwable => ()
    end try
  end runSbt

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
