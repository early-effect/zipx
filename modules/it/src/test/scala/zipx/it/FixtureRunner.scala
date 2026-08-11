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

  /** Suite-scoped boot + coursier so successive fixture runs do not re-download the launcher. */
  private lazy val SharedTooling: Path =
    val root = Files.createTempDirectory("zipx-it-sbt-tooling-")
    Files.createDirectories(root.resolve("boot"))
    Files.createDirectories(root.resolve("coursier"))
    root

  final case class RunResult(exitCode: Int, out: String, elapsedMs: Long):
    def ok: Boolean = exitCode == 0

    /** Output after the in-session [[wipeItCaches]] marker (empty if wipe never ran). */
    def afterWipe: String =
      val marker = "ZIPX_IT_WIPE"
      out.indexOf(marker) match
        case -1 => ""
        case i  => out.substring(i + marker.length)

    /** Put/Get phase durations from `itStamp` markers (`before; put; afterWipe; get; end` => 3 stamps). */
    def phaseMs: Option[(Long, Long)] =
      val stamps =
        raw"ZIPX_IT_STAMP (\d+)".r.findAllMatchIn(out).map(_.group(1).toLong).toVector
      if stamps.size >= 3 then Some((stamps(1) - stamps(0), stamps(2) - stamps(1)))
      else None
  end RunResult

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

  /** One sbt process; `script` is a `;`-joined command string (`compile`, `wipeItCaches`, `set ...`, …). */
  def runSbt(
      fixtureDir: Path,
      grpcUri: String,
      script: String,
      home: Path,
      extraEnv: Map[String, String] = Map.empty,
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
    val bootDir     = SharedTooling.resolve("boot").toAbsolutePath.toString
    val coursierDir = SharedTooling.resolve("coursier").toAbsolutePath.toString
    val env         = scala.collection.mutable.Map.from(sys.env) ++ extraEnv ++ Map(
      RemoteCacheProof.envUri -> grpcUri,
      "HOME"                  -> home.toAbsolutePath.toString,
      "COURSIER_CACHE"        -> coursierDir,
      "SBT_OPTS"              -> s"-Xmx512m -Dsbt.boot.directory=$bootDir",
    )

    // Foreground server: thin client reuses a background server across fixtures and breaks isolation.
    val cmd     = Seq("sbt", "--server", "--batch", s"-Dsbt.boot.directory=$bootDir", script)
    logSbtSpawnDiagnostics(fixtureDir, home, env.toMap, cmd)
    val started = System.nanoTime()
    val code    =
      try Process(cmd, fixtureDir.toFile, env.toSeq*).!(logger)
      catch
        case e: java.io.IOException =>
          val diag = sbtSpawnDiagnostics(fixtureDir, home, env.toMap, cmd)
          throw new java.io.IOException(s"${e.getMessage}\n--- zipx IT sbt spawn diagnostics ---\n$diag", e)
    val elapsed = (System.nanoTime() - started).nanos.toMillis
    RunResult(code, log.toString, elapsed)
  end runSbt

  /** Pre-spawn / on-failure dump for CI: PATH, which(sbt), file checks under toolcache. Temporary while diagnosing
    * setup-sbt@v1.5.7 / sbt 2.0.6 ENOENT on child `sbt`.
    */
  private def logSbtSpawnDiagnostics(
      fixtureDir: Path,
      home: Path,
      env: Map[String, String],
      cmd: Seq[String],
  ): Unit =
    val diag = sbtSpawnDiagnostics(fixtureDir, home, env, cmd)
    System.err.println(s"--- zipx IT sbt spawn diagnostics ---\n$diag")

  private def sbtSpawnDiagnostics(
      fixtureDir: Path,
      home: Path,
      env: Map[String, String],
      cmd: Seq[String],
  ): String =
    val path        = env.getOrElse("PATH", "<missing>")
    val pathEntries = path.split(java.io.File.pathSeparatorChar).toList
    val whichSbt =
      Try(Process(Seq("which", "sbt"), null: java.io.File, "PATH" -> path).!!.trim)
        .getOrElse("<which failed>")
    val whichSbtParent =
      Try(Process(Seq("which", "sbt")).!!.trim).getOrElse("<which failed in parent env>")
    val sbtOnPath = pathEntries
      .map(dir => Path.of(dir, "sbt"))
      .find(p => Files.isRegularFile(p) || Files.isSymbolicLink(p))
    val sbtFileInfo = sbtOnPath
      .map { p =>
        val abs  = p.toAbsolutePath
        val exec = Files.isExecutable(abs)
        val size = Try(Files.size(abs)).getOrElse(-1L)
        val head =
          Try(Files.readString(abs).linesIterator.take(3).mkString(" | ")).getOrElse("<unreadable>")
        s"$abs exists=${Files.exists(abs)} executable=$exec size=$size head=[$head]"
      }
      .getOrElse("<no sbt file on PATH entries>")
    val toolcache = Path.of("/opt/hostedtoolcache/sbt")
    val toolcacheListing =
      if Files.isDirectory(toolcache) then
        Try {
          val stream = Files.list(toolcache)
          try stream.toArray.map(_.asInstanceOf[Path].getFileName.toString).mkString(", ")
          finally stream.close()
        }.getOrElse("<list failed>")
      else "<no /opt/hostedtoolcache/sbt>"
    val toolcacheSbt206 = Path.of("/opt/hostedtoolcache/sbt/2.0.6/sbt/bin/sbt")
    val toolcacheSbt204 = Path.of("/opt/hostedtoolcache/sbt/2.0.4/sbt/bin/sbt")
    def fileCheck(p: Path): String =
      s"$p exists=${Files.exists(p)} exec=${Try(Files.isExecutable(p)).getOrElse(false)}"
    List(
      s"cwd=$fixtureDir",
      s"home=$home",
      s"cmd=${cmd.mkString(" ")}",
      s"parent which sbt=$whichSbtParent",
      s"child-env which sbt=$whichSbt",
      s"sbt on PATH search=$sbtFileInfo",
      s"PATH length=${path.length} entries=${pathEntries.size}",
      s"PATH=$path",
      s"HOME=${env.getOrElse("HOME", "<missing>")}",
      s"JAVA_HOME=${env.getOrElse("JAVA_HOME", "<missing>")}",
      s"sys.env PATH present=${sys.env.contains("PATH")}",
      s"sys.env keys=${sys.env.keys.toList.sorted.mkString(",")}",
      s"toolcache versions=[$toolcacheListing]",
      s"toolcache 2.0.6: ${fileCheck(toolcacheSbt206)}",
      s"toolcache 2.0.4: ${fileCheck(toolcacheSbt204)}",
    ).mkString("\n")
  end sbtSpawnDiagnostics

  /** Drop project outputs + sbt action cache; keep boot/coursier (shared tooling) intact. */
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
