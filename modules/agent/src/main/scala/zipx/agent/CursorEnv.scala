package zipx.agent

import neotype.unwrap
import zipx.core.{SbtCommand, SbtImage, ZipxCatalog}
import zipx.shell.{
  Assign,
  Block,
  Command,
  Exec,
  Exit,
  ExitCode,
  FileDescriptor,
  Heredoc,
  HeredocTag,
  If,
  ParamMod,
  ParamText,
  RedirectFd,
  Script,
  ScriptLine,
  SetOpts,
  ShTest,
  VarName,
  Word,
}

/** The sbt-image agent. `baked` is the env Dockerfile (public `FROM`, the same `RUN` lines CI publishes). Empty means
  * the upstream image already has every tool. `env` is plain job env plus layer env plus `HOME` / `user.home` / git
  * `safe.directory`. Secrets are not in here.
  */
final case class AgentImage(
    upstream: String,
    baked: Option[String],
    dockerCli: Option[String],
    fuseOverlayfs: Option[String],
    iptables: Option[String],
    env: Map[String, String],
)

/** What the Cursor renderer draws. Docker is the only choice the build makes here. JDK, runner, secrets, and the warm
  * command come from the settings CI already uses. Ubuntu pins and an sbt image are the two bases. One of them is set.
  */
final case class AgentEnv(
    docker: Boolean,
    rehydrate: SbtCommand,
    secretEnv: List[String],
    pins: Option[AgentPins] = None,
    toolchain: Option[AgentImage] = None,
)

final case class AgentFiles(environmentJson: String, dockerfile: Option[String], installSh: String)

/** Cursor cloud agent files. `environment.json` and the Dockerfile are the only paths under `.cursor/`. The install
  * script lives outside that directory so local rules and MCP stay untracked.
  */
object CursorEnv:

  val EnvironmentRel: String = ".cursor/environment.json"
  val DockerfileRel: String  = ".cursor/Dockerfile"
  val InstallRel: String     = ".github/zipx-agent/install.sh"

  def render(env: AgentEnv): Either[String, AgentFiles] =
    for
      _       <- requireBase(env)
      docker  <- dockerfile(env)
      install <- installScript(env)
    yield AgentFiles(environmentJson(env), docker, install)

  private def requireBase(env: AgentEnv): Either[String, Unit] =
    if env.pins.isDefined || env.toolchain.isDefined then Right(())
    else Left("zipx: cloud agent needs Ubuntu pins or an sbt image")

  def environmentJson(env: AgentEnv): String =
    val image =
      env.toolchain.filter(img => !env.docker && img.baked.isEmpty).map(img => s"""  "image": "${img.upstream}"""")
    val build = Option.when(image.isEmpty)(
      """  "build": {
    "dockerfile": "Dockerfile"
  }"""
    )
    val start =
      if !env.docker then None
      else
        env.toolchain match
          case Some(_) => Some("""  "start": "/usr/local/bin/zipx-dockerd"""")
          case None    => Some("""  "start": "sudo service docker start"""")
    val fields = (image.toList ++ build.toList) ++ List(s"""  "install": "bash $InstallRel"""") ++ start.toList
    "{\n" + fields.mkString(",\n") + "\n}\n"

  def dockerfile(env: AgentEnv): Either[String, Option[String]] =
    env.toolchain match
      case Some(image) => sbtDockerfile(image, env.docker)
      case None        =>
        env.pins match
          case Some(pins) => Right(Some(ubuntuDockerfile(env, pins)))
          case None       => Right(None)

  private def ubuntuDockerfile(env: AgentEnv, pins: AgentPins): String =
    val docker = if env.docker then dockerSetup(pins.dockerCe) else ""
    s"""${ZipxCatalog.GeneratedHeader}
       |FROM ubuntu:${UbuntuBase.Tag}@${pins.baseImage}
       |RUN apt-get update && apt-get install -y --no-install-recommends \\
       |    ca-certificates \\
       |    curl \\
       |    git \\
       |    gnupg \\
       |    sudo \\
       |    tar \\
       |    unzip \\
       |    && rm -rf /var/lib/apt/lists/*
       |RUN curl -fsSL -o /tmp/sbt.zip "https://github.com/sbt/sbt/releases/download/v${pins.sbtRunner}/sbt-${pins.sbtRunner}.zip" \\
       |    && unzip -o /tmp/sbt.zip -d /opt \\
       |    && ln -sfn /opt/sbt/bin/sbt /usr/local/bin/sbt \\
       |    && rm /tmp/sbt.zip
       |$docker
       |RUN id -u ubuntu >/dev/null 2>&1 || useradd -m -s /bin/bash ubuntu \\
       |    && echo "ubuntu ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/ubuntu \\
       |    && chmod 0440 /etc/sudoers.d/ubuntu
       |USER ubuntu
       |WORKDIR /home/ubuntu
       |""".stripMargin

  private def sbtDockerfile(image: AgentImage, docker: Boolean): Either[String, Option[String]] =
    if !docker then
      image.baked match
        case None       => Right(None)
        case Some(body) => appendEnv(body, image.env).map(Some(_))
    else
      for
        cli      <- required(image.dockerCli, "docker-cli")
        fuse     <- required(image.fuseOverlayfs, "fuse-overlayfs")
        iptables <- required(image.iptables, "iptables")
        _        <- AgentDaemon.validateCli(cli)
        _        <- AgentDaemon.validateApt(fuse)
        _        <- AgentDaemon.validateApt(iptables)
        base = image.baked.getOrElse(stub(image.upstream))
        body <- appendEnv(appendRun(base, daemonRun(cli, fuse, iptables)), image.env)
      yield Some(body)

  private def required(value: Option[String], id: String): Either[String, String] =
    value.toRight(s"zipx: zipxAgentEnv needs a $id pin")

  private def stub(upstream: String): String =
    s"""${ZipxCatalog.GeneratedHeader}
       |FROM $upstream
       |USER root
       |WORKDIR /root
       |""".stripMargin

  private def appendRun(body: String, run: String): String =
    val base = if body.endsWith("\n") then body else body + "\n"
    base + run + (if run.endsWith("\n") then "" else "\n")

  private def appendEnv(body: String, env: Map[String, String]): Either[String, String] =
    checkedEnv(env).map { keys =>
      val extra = missingEnv(body, keys).map((key, value) => SbtImage.envLine(key, value))
      if extra.isEmpty then body
      else
        val base = if body.endsWith("\n") then body else body + "\n"
        base + extra.mkString("\n") + "\n"
    }

  private def missingEnv(body: String, env: List[(String, String)]): List[(String, String)] =
    env.filterNot { (key, _) =>
      body.linesIterator.exists(line => line.startsWith(s"ENV $key=") || line.startsWith(s"ENV $key "))
    }

  private def checkedEnv(env: Map[String, String]): Either[String, List[(String, String)]] =
    env.toList.sortBy(_._1).foldLeft[Either[String, List[(String, String)]]](Right(Nil)) { case (acc, (key, value)) =>
      acc.flatMap { found =>
        if !key.matches("[A-Za-z_][A-Za-z0-9_]*") then Left(s"zipx: agent env name '$key' is not an identifier")
        else if value.contains('\n') || value.contains('\r') then Left(s"zipx: agent env '$key' contains a newline")
        else if value.contains("${{") then Left(s"zipx: agent env '$key' contains a GitHub expression")
        else Right(found :+ (key -> value))
      }
    }

  /** Static `dockerd` from the `docker-cli` pin. `service` is absent on the sbt image, and the tarball ships no init
    * script. The start command is the script this writes.
    */
  private def daemonRun(cli: String, fuse: String, iptables: String): String =
    val script = List(
      "#!/bin/sh",
      "set -eu",
      "mkdir -p /var/run /var/log",
      "setsid dockerd --host unix:///var/run/docker.sock --config-file /etc/docker/daemon.json >/var/log/dockerd.log 2>&1 </dev/null &",
      "i=0",
      "while [ \"$i\" -lt 100 ]; do",
      "  if docker info >/dev/null 2>&1; then",
      "    exit 0",
      "  fi",
      "  i=$((i + 1))",
      "  sleep 0.2",
      "done",
      "echo \"zipx: dockerd did not become ready\" >&2",
      "exit 1",
    )
    val quoted = script.map(line => "'" + line.replace("'", "'\\''") + "'").mkString(" \\\n    ")
    s"""RUN set -eux; \\
       |    apt-get update; \\
       |    apt-get install -y --no-install-recommends fuse-overlayfs=$fuse iptables=$iptables; \\
       |    rm -rf /var/lib/apt/lists/*; \\
       |    mkdir -p /etc/docker; \\
       |    printf '%s\\n' '{' '  "storage-driver": "fuse-overlayfs"' '}' > /etc/docker/daemon.json; \\
       |    update-alternatives --set iptables /usr/sbin/iptables-legacy; \\
       |    update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy; \\
       |    case "$$(uname -m)" in \\
       |      x86_64) docker_arch=x86_64 ;; \\
       |      aarch64) docker_arch=aarch64 ;; \\
       |      *) echo "zipx: unsupported architecture $$(uname -m)" >&2; exit 1 ;; \\
       |    esac; \\
       |    curl -fsSL -o /tmp/docker.tgz "https://download.docker.com/linux/static/stable/$${docker_arch}/docker-$cli.tgz"; \\
       |    tar -xzf /tmp/docker.tgz -C /tmp; \\
       |    mv /tmp/docker/* /usr/local/bin/; \\
       |    rm -rf /tmp/docker /tmp/docker.tgz; \\
       |    printf '%s\\n' \\
       |    $quoted \\
       |    > /usr/local/bin/zipx-dockerd; \\
       |    chmod 0755 /usr/local/bin/zipx-dockerd
       |""".stripMargin

  private def dockerSetup(version: String): String =
    s"""RUN set -eux; \\
       |    install -m 0755 -d /etc/apt/keyrings; \\
       |    curl --retry 3 --retry-delay 5 -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg; \\
       |    chmod a+r /etc/apt/keyrings/docker.gpg; \\
       |    echo "deb [arch=$$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $$(. /etc/os-release && echo $$VERSION_CODENAME) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null; \\
       |    apt-get update; \\
       |    apt-get install -y --no-install-recommends \\
       |    docker-ce=$version \\
       |    docker-ce-cli=$version \\
       |    containerd.io \\
       |    docker-buildx-plugin \\
       |    docker-compose-plugin \\
       |    fuse-overlayfs \\
       |    iptables; \\
       |    rm -rf /var/lib/apt/lists/*; \\
       |    mkdir -p /etc/docker; \\
       |    printf '%s\\n' '{' '  "storage-driver": "fuse-overlayfs"' '}' > /etc/docker/daemon.json; \\
       |    update-alternatives --set iptables /usr/sbin/iptables-legacy; \\
       |    update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy; \\
       |    groupadd -f docker; \\
       |    if ! id -u ubuntu >/dev/null 2>&1; then useradd -m -s /bin/bash ubuntu; fi; \\
       |    usermod -aG docker ubuntu
       |""".stripMargin

  def installScript(env: AgentEnv): Either[String, String] =
    for
      checks <- secretChecks(env.secretEnv)
      quoted <- quoteSbt(env.rehydrate.text)
    yield
      val jdk =
        if env.toolchain.isDefined then Nil
        else jdkStep :: linkJdk :: linkBins
      val ttl =
        if coursierInImage(env) then Nil
        else List(Assign(VarName("COURSIER_TTL"), Word.lit("0s"), Assign.Scope.Export))
      val commands: List[Command] =
        Exec(".", Word.lit("project/zipx-ci.env")) ::
          checks :::
          ttl :::
          jdk ::: List(
            Exec("sbt", Word.lit("--server"), Word.lit("--batch"), quoted)
          )
      val body = Script(SetOpts() :: commands).withTrailingNewline(true).render
      s"${ZipxCatalog.GeneratedHeader}\n$body"

  /** The export stays when the image does not carry the key. An upstream-only agent has no Dockerfile to write `ENV`.
    */
  private def coursierInImage(env: AgentEnv): Boolean =
    env.toolchain.exists { image =>
      image.env.contains("COURSIER_TTL") && (env.docker || image.baked.isDefined)
    }

  private def secretChecks(names: List[String]): Either[String, List[Command]] =
    names
      .foldLeft[Either[String, List[VarName]]](Right(Nil)) { (acc, name) =>
        acc.flatMap(found => VarName.make(name).map(found :+ _))
      }
      .flatMap { vars =>
        if vars.isEmpty then Right(Nil)
        else
          vars
            .foldLeft[Either[String, List[Command]]](Right(List(Assign("zipx_missing", Word.quoted(""))))) {
              (acc, name) =>
                for
                  found <- acc
                  lit   <- Word.litMake(name.unwrap)
                yield found :+ If(
                  ShTest.Empty(
                    Word.Dquote(List(Word.VarRef(name, Some(ParamMod.Default(ParamText(""))))))
                  ),
                  Block(
                    Assign(
                      "zipx_missing",
                      Word.dquote(Word.v("zipx_missing"), Word.lit(" "), lit),
                    )
                  ),
                )
            }
            .map(_ :+ reportMissing)
      }

  private def reportMissing: Command =
    If(
      ShTest.NonEmpty(Word.vq("zipx_missing")),
      Block(
        toStderr(
          Exec(
            "echo",
            Word.dquote(
              Word.lit("zipx: cloud agent is missing env (set each as a Cursor Runtime Secret):"),
              Word.v("zipx_missing"),
            ),
          )
        ),
        Exit(ExitCode.Failure),
      ),
    )

  private val showSpec: Command =
    RedirectFd(
      Exec("/opt/jdk/current/bin/java", Word.lit("-XshowSettings:properties"), Word.lit("-version")),
      FileDescriptor.Stderr,
      FileDescriptor.Stdout,
    ) | Exec(
      "awk",
      Word.lit("-F="),
      Word.quoted("""/java.specification.version/ { gsub(/ /, "", $2); print $2 }"""),
    )

  private def jdkStep: Command =
    If(
      ShTest.Executable(Word.lit("/opt/jdk/current/bin/java")),
      Block(
        Assign("zipx_jdk_spec", Word.subst(showSpec)),
        If(
          ShTest.StrEq(Word.vq("zipx_jdk_spec"), Word.vq("ZIPX_JAVA_VERSION")),
          Block(
            Exec(
              "echo",
              Word.dquote(
                Word.lit("zipx: JDK "),
                Word.v("ZIPX_JAVA_VERSION"),
                Word.lit(" already at /opt/jdk/current"),
              ),
            )
          ),
          elseDo = Some(installJdk),
        ),
      ),
      elseDo = Some(installJdk),
    )

  private val installJdk: Block =
    Block(
      Assign("zipx_arch", Word.subst(Exec("dpkg", Word.lit("--print-architecture")))),
      If(
        ShTest.varEquals("zipx_arch", "amd64"),
        Block(Assign("zipx_jarch", Word.lit("x64"))),
        elifs = List(ShTest.varEquals("zipx_arch", "arm64") -> Block(Assign("zipx_jarch", Word.lit("aarch64")))),
        elseDo = Some(
          Block(
            toStderr(Exec("echo", Word.dquote(Word.lit("zipx: unsupported architecture "), Word.v("zipx_arch")))),
            Exit(ExitCode.Failure),
          )
        ),
      ),
      Exec("sudo", Word.lit("mkdir"), Word.lit("-p"), Word.lit("/opt/jdk")),
      Exec(
        "sudo",
        Word.lit("rm"),
        Word.lit("-rf"),
        Word.lit("/opt/jdk/candidate"),
        Word.lit("/tmp/zipx-jdk.tar.gz"),
      ),
      Exec(
        "curl",
        Word.lit("-fsSL"),
        Word.lit("-o"),
        Word.lit("/tmp/zipx-jdk.tar.gz"),
        Word.dquote(
          Word.lit("https://api.adoptium.net/v3/binary/latest/"),
          Word.vBraced("ZIPX_JAVA_VERSION"),
          Word.lit("/ga/linux/"),
          Word.vBraced("zipx_jarch"),
          Word.lit("/jdk/hotspot/normal/eclipse?project=jdk"),
        ),
      ),
      Exec("sudo", Word.lit("mkdir"), Word.lit("-p"), Word.lit("/opt/jdk/candidate")),
      Exec(
        "sudo",
        Word.lit("tar"),
        Word.lit("-xzf"),
        Word.lit("/tmp/zipx-jdk.tar.gz"),
        Word.lit("-C"),
        Word.lit("/opt/jdk/candidate"),
      ),
      Assign(
        "zipx_jdk_home",
        Word.subst(
          Exec(
            "find",
            Word.lit("/opt/jdk/candidate"),
            Word.lit("-mindepth"),
            Word.lit("1"),
            Word.lit("-maxdepth"),
            Word.lit("1"),
            Word.lit("-type"),
            Word.lit("d"),
          )
        ),
      ),
      If(
        ShTest.Empty(Word.Dquote(List(Word.vOrEmpty("zipx_jdk_home")))),
        Block(
          toStderr(Exec("echo", Word.quoted("zipx: Temurin archive had no JDK directory"))),
          Exit(ExitCode.Failure),
        ),
      ),
      Exec("sudo", Word.lit("ln"), Word.lit("-sfn"), Word.vq("zipx_jdk_home"), Word.lit("/opt/jdk/current")),
      Exec("rm", Word.lit("-f"), Word.lit("/tmp/zipx-jdk.tar.gz")),
    )

  private val linkJdk: Command =
    Heredoc(
      Exec("sudo", Word.lit("tee"), Word.lit("/etc/profile.d/zipx-jdk.sh")),
      HeredocTag("ZIPX_JDK"),
      List(
        ScriptLine("export JAVA_HOME=/opt/jdk/current"),
        ScriptLine("""export PATH="$JAVA_HOME/bin:$PATH""""),
      ),
    )

  private def linkBins: List[Command] = List(
    Exec(
      "sudo",
      Word.lit("ln"),
      Word.lit("-sfn"),
      Word.lit("/opt/jdk/current/bin/java"),
      Word.lit("/usr/local/bin/java"),
    ),
    Exec(
      "sudo",
      Word.lit("ln"),
      Word.lit("-sfn"),
      Word.lit("/opt/jdk/current/bin/javac"),
      Word.lit("/usr/local/bin/javac"),
    ),
    Exec("sudo", Word.lit("ln"), Word.lit("-sfn"), Word.lit("/opt/jdk/current/bin/jar"), Word.lit("/usr/local/bin/jar")),
  )

  private def toStderr(command: Exec): Command =
    RedirectFd(command, FileDescriptor.Stdout, FileDescriptor.Stderr)

  private def quoteSbt(text: String): Either[String, Word] =
    if !text.contains("'") then Word.squoteMake(text)
    else
      val parts = text.split("'", -1).toList.map(Word.squoteMake)
      parts
        .foldLeft[Either[String, List[Word]]](Right(Nil)) { (acc, part) =>
          acc.flatMap(found => part.map(found :+ _))
        }
        .map { found =>
          Word.cat(intersperse(found, Word.lit("\\'"))*)
        }

  private def intersperse(words: List[Word], separator: Word): List[Word] =
    words match
      case Nil          => Nil
      case head :: tail => head :: tail.flatMap(word => List(separator, word))
end CursorEnv
