package zipx.agent

import neotype.unwrap
import zipx.core.{SbtCommand, ZipxCatalog}
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

/** What the Cursor renderer draws. Docker is the only choice the build makes here. JDK, runner, secrets, and the warm
  * command come from the settings CI already uses.
  */
final case class AgentEnv(
    docker: Boolean,
    rehydrate: SbtCommand,
    secretEnv: List[String],
    pins: AgentPins,
)

final case class AgentFiles(environmentJson: String, dockerfile: String, installSh: String)

/** Cursor cloud agent files. `environment.json` and the Dockerfile are the only paths under `.cursor/`. The install
  * script lives outside that directory so local rules and MCP stay untracked.
  */
object CursorEnv:

  val EnvironmentRel: String = ".cursor/environment.json"
  val DockerfileRel: String  = ".cursor/Dockerfile"
  val InstallRel: String     = ".github/zipx-agent/install.sh"

  def render(env: AgentEnv): Either[String, AgentFiles] =
    installScript(env).map { install =>
      AgentFiles(environmentJson(env.docker), dockerfile(env), install)
    }

  def environmentJson(docker: Boolean): String =
    val fields = List(
      """  "build": {
    "dockerfile": "Dockerfile"
  }""",
      s"""  "install": "bash $InstallRel"""",
    ) ++ (if docker then List("""  "start": "sudo service docker start"""") else Nil)
    "{\n" + fields.mkString(",\n") + "\n}\n"

  def dockerfile(env: AgentEnv): String =
    val docker = if env.docker then dockerSetup(env.pins.dockerCe) else ""
    s"""${ZipxCatalog.GeneratedHeader}
       |FROM ubuntu:${UbuntuBase.Tag}@${env.pins.baseImage}
       |RUN apt-get update && apt-get install -y --no-install-recommends \\
       |    ca-certificates \\
       |    curl \\
       |    git \\
       |    gnupg \\
       |    sudo \\
       |    tar \\
       |    unzip \\
       |    && rm -rf /var/lib/apt/lists/*
       |RUN curl -fsSL -o /tmp/sbt.zip "https://github.com/sbt/sbt/releases/download/v${env.pins.sbtRunner}/sbt-${env.pins.sbtRunner}.zip" \\
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
  end dockerfile

  private def dockerSetup(version: String): String =
    s"""RUN install -m 0755 -d /etc/apt/keyrings \\
       |    && curl --retry 3 --retry-delay 5 -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \\
       |    && chmod a+r /etc/apt/keyrings/docker.gpg \\
       |    && echo "deb [arch=$$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $$(. /etc/os-release && echo $$VERSION_CODENAME) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null \\
       |    && apt-get update \\
       |    && apt-get install -y --no-install-recommends \\
       |    docker-ce=$version \\
       |    docker-ce-cli=$version \\
       |    containerd.io \\
       |    docker-buildx-plugin \\
       |    docker-compose-plugin \\
       |    fuse-overlayfs \\
       |    iptables \\
       |    && rm -rf /var/lib/apt/lists/* \\
       |    && mkdir -p /etc/docker \\
       |    && printf '%s\\n' '{' '  "storage-driver": "fuse-overlayfs"' '}' > /etc/docker/daemon.json \\
       |    && update-alternatives --set iptables /usr/sbin/iptables-legacy \\
       |    && update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy \\
       |    && groupadd -f docker \\
       |    && id -u ubuntu >/dev/null 2>&1 || useradd -m -s /bin/bash ubuntu \\
       |    && usermod -aG docker ubuntu
       |""".stripMargin

  def installScript(env: AgentEnv): Either[String, String] =
    for
      checks <- secretChecks(env.secretEnv)
      quoted <- quoteSbt(env.rehydrate.text)
    yield
      val commands: List[Command] =
        Exec(".", Word.lit("project/zipx-ci.env")) ::
          checks :::
          List(
            Assign(VarName("COURSIER_TTL"), Word.lit("0s"), Assign.Scope.Export),
            jdkStep,
            linkJdk,
          ) ::: linkBins ::: List(
            Exec("sbt", Word.lit("--server"), Word.lit("--batch"), quoted)
          )
      val body = Script(SetOpts() :: commands).withTrailingNewline(true).render
      s"${ZipxCatalog.GeneratedHeader}\n$body"

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
            Word.dquote(Word.lit("zipx: cloud agent is missing secret-backed env:"), Word.v("zipx_missing")),
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
