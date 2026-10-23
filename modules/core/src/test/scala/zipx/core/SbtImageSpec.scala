package zipx.core

import neotype.unwrap
import zipx.workflow.*
import zio.*
import zio.test.*

object SbtImageSpec extends ZIOSpecDefault:

  private val digest = "a" * 64

  private def imagePin(version: String): Pin =
    Pin(PinFeedName.unsafeMake("sbt-image"), SbtImage.BaseId, DepVersion.unsafeMake(version), None, None)

  private val noLookup: PinLookup = _ => Right(None)

  private def toolPin(id: String, version: String): Pin =
    Pin(PinFeedName.unsafeMake("sbt-image"), id, DepVersion.unsafeMake(version), None, None)

  private def widgetSteps(version: String, arch: CpuArch): List[ImageStep] =
    val archName = arch match
      case CpuArch.Amd64 => "x86_64"
      case CpuArch.Arm64 => "aarch64"
    List(
      ImageStep.Tree(
        url = s"https://example.test/widgets/$version/widgets-$archName.tar.gz",
        dest = "/opt/widgets",
        format = ArchiveFormat.TarGz,
        strip = 1,
      ),
      ImageStep.EmptyFile("/opt/widgets/READY"),
      ImageStep.Package("libwidgets", "1.2.3-1"),
    )
  end widgetSteps

  private def widgets(steps: (String, CpuArch) => List[ImageStep]): SbtImageLayer =
    SbtImageLayer("widgets", noLookup).install(steps).withEnv("WIDGET_PATH" -> "/opt/widgets")

  private def gadgets: SbtImageLayer =
    SbtImageLayer("gadgets", noLookup)
      .install { (version, _) =>
        List(
          ImageStep.File(
            url = s"https://example.test/gadgets/$version/gadget",
            dest = "/usr/local/bin/gadget",
            source = FileSource.Raw,
            mode = FileMode.Executable,
          )
        )
      }
      .withEnv("WIDGET_PATH" -> "/usr/local/bin/gadget")

  /** Each `RUN` command as the shell receives it: Docker drops every backslash-newline continuation. */
  private def runBodies(dockerfile: String): List[String] =
    dockerfile
      .replace("\\\n", "")
      .linesIterator
      .collect { case line if line.startsWith("RUN ") => line.stripPrefix("RUN ") }
      .toList

  /** `sh -n` parses without running. Exit status and diagnostics are data for the assertion. */
  private def shellCheck(script: String): Task[(Int, String)] =
    ZIO.attemptBlocking {
      val process = new ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start()
      val output  = String(process.getInputStream.readAllBytes())
      (process.waitFor(), output)
    }

  private def resolve(extra: List[Pin], layers: Seq[SbtImageLayer] = Nil): Either[SbtImageError, SbtImagePlan] =
    SbtImage.resolve(
      JdkVersion("21"),
      SbtImage.DefaultSbtVersion,
      extra,
      Nil,
      coursier = false,
      docker = false,
      layers,
    )

  private def image(
      java: String,
      pins: Seq[Pin] = Nil,
      nodes: List[NodeVersion] = Nil,
      coursier: Boolean = false,
      docker: Boolean = false,
  ): Either[SbtImageError, SbtImagePlan] =
    SbtImage.resolve(JdkVersion.unsafeMake(java), SbtImage.DefaultSbtVersion, pins, nodes, coursier, docker)

  def spec = suite("Sbt image")(
    test("a JDK below 21 is refused before the vendor check") {
      assertTrue(
        image("17") == Left(SbtImageError.JdkBelowMinimum(17)),
        image("11") == Left(SbtImageError.JdkBelowMinimum(11)),
        image("8") == Left(SbtImageError.JdkBelowMinimum(8)),
        image("20").left.exists(_.message.contains("below the minimum")),
      )
    },
    test("an openjdk pin below the fixed build is refused, and a later major with no package is refused") {
      assertTrue(
        image("21", Seq(toolPin("openjdk-21", "21.0.13-r0"))) == Left(
          SbtImageError.JdkVulnerable("21.0.13-r0", "21.0.13.0.2")
        ),
        image("25", Seq(toolPin("openjdk-25", "25.0.5-r1"))) == Left(
          SbtImageError.JdkVulnerable("25.0.5-r1", "25.0.5.0.2")
        ),
        image("22") == Left(SbtImageError.JdkUnsupported(22)),
        image("27") == Left(SbtImageError.JdkUnsupported(27)),
        image("25", Seq(toolPin("openjdk-25", "21.0.13.0.2-r0"))) == Left(SbtImageError.JdkMismatch(21, "25")),
        image("21", Seq(toolPin("sbtscala/scala-sbt", "1"))) == Left(
          SbtImageError.RetiredTemurinPin("sbtscala/scala-sbt=1")
        ),
      )
    },
    test("the default image is Wolfi, a pinned OpenJDK, and the build's sbt") {
      val plan = image("21")
      val body = plan.map(_.dockerfile).getOrElse("")
      assertTrue(
        plan.map(_.upstream) == Right(WolfiToolchain.reference(WolfiToolchain.BaseDigest)),
        body.contains(s"FROM ${WolfiToolchain.reference(WolfiToolchain.BaseDigest)}"),
        body.contains("apk add --no-cache"),
        body.contains(s"openjdk-21=${WolfiToolchain.Jdk21.version}"),
        body.contains(s"openjdk-21-default-jdk=${WolfiToolchain.Jdk21.version}"),
        body.contains(SbtImage.envLine("JAVA_HOME", WolfiToolchain.JavaHome)),
        body.contains(s"sbt-${SbtImage.DefaultSbtVersion}.tgz"), {
          val made   = body.indexOf("mkdir -p /usr/local/bin")
          val linked = body.indexOf("ln -sfn /usr/local/sbt/bin/sbt /usr/local/bin/sbt")
          made >= 0 && made < linked
        },
        body.contains("USER sbtuser"),
        !body.contains("eclipse-temurin"),
        !body.contains("setup-java"),
        !body.contains("apt-get"),
        !body.contains("dpkg"),
        plan.exists(_.derivedTag.startsWith("df-")),
        WolfiToolchain.parseApk(WolfiToolchain.Jdk21.version).map(_.upstream).contains("21.0.13.0.2"),
        image("21", Seq(imagePin(s"sha256:$digest"))).map(_.upstream) == Right(
          WolfiToolchain.reference(s"sha256:$digest")
        ),
        image("21", Seq(imagePin("latest"))) == Left(SbtImageError.BadBasePin("latest")),
      )
    },
    test("a numbered release stays on its major") {
      val listed = List("22.9.0", "22.14.0", "22.14.1", "24.1.0", "22.15.0-rc.1")
      assertTrue(
        SbtImage.numberedCandidate("22.14.0", listed) == Right(Some("22.14.1")),
        SbtImage.numberedCandidate("22.14.1", listed) == Right(None),
        SbtImage.numberedCandidate("latest", listed).isLeft,
      )
    },
    test("a gh pin bakes the official client, and a push without the pin is refused") {
      val baked = resolve(List(toolPin(SbtImage.GhId, "2.102.0")))
      val body  = baked.map(_.dockerfile).getOrElse("")
      val plain = resolve(Nil).map(_.dockerfile).getOrElse("")
      assertTrue(
        body.contains("https://github.com/cli/cli/releases/download/v2.102.0/gh_2.102.0_linux_${gh_arch}.tar.gz"),
        body.contains("""mv "/tmp/gh_2.102.0_linux_${gh_arch}/bin/gh" /usr/local/bin/gh"""),
        body.contains("uname -m"),
        body.contains("gh_arch=amd64"),
        !body.contains("$${"),
        !body.contains("dpkg"),
        baked.map(_.dockerSocket) == Right(false),
        baked.exists(_.derivedTag.startsWith("df-")),
        !plain.contains("cli/cli"),
        resolve(List(toolPin(SbtImage.GhId, "latest"))) == Left(
          SbtImageError.ToolPinNotConcrete(SbtImage.GhId, "latest")
        ),
        SbtImage.missingPushClient(onPush = true, Nil) == Some(
          SbtImageError.MissingToolPin(SbtImage.GhId, "testAffected(onPush = true)")
        ),
        SbtImage.missingPushClient(onPush = false, Nil) == None,
        SbtImage.missingPushClient(onPush = true, Seq(toolPin(SbtImage.GhId, "2.102.0"))) == None,
        SbtImage.missingPushClient(onPush = true, Seq(toolPin(SbtImage.GhId, "latest"))) == None,
        resolve(
          List(toolPin(SbtImage.GhId, "2.102.0")),
          List(SbtImageLayer(SbtImage.GhId, noLookup).install(widgetSteps)),
        ) == Left(SbtImageError.ReservedLayerId(SbtImage.GhId)),
      )
    },
    test("node, cs, and the docker client are baked, and the tag is the Dockerfile hash") {
      val pins = Seq(Pin("sbt-image", "cs", "2.1.24"), Pin("sbt-image", "docker-cli", "28.5.2"))
      val plan = image("21", pins, List(NodeVersion("22.14.0")), coursier = true, docker = true)
      val body = plan.map(_.dockerfile).getOrElse("")
      assertTrue(
        plan.exists(_.derivedTag.startsWith("df-")),
        body.contains(s"FROM ${WolfiToolchain.reference(WolfiToolchain.BaseDigest)}"),
        body.contains("node-v22.14.0-linux-"),
        body.contains("node_arch=x64"),
        body.contains("node.tar.gz"),
        !body.contains("tar.xz"),
        body.contains("coursier/releases/download/v2.1.24/cs-"),
        body.contains("docker-28.5.2.tgz"),
        !body.contains("TOKEN"),
        !body.contains(".cache"),
        plan.exists(image => image.derivedTag == SbtImage.contentTag(body)),
        plan.exists(_.dockerSocket),
        !body.contains("dpkg"),
      )
    },
    test("two node versions and an alias without a pin are refused, and an unpinned docker client uses the default") {
      assertTrue(
        image("21", nodes = List(NodeVersion("20.11.0"), NodeVersion("22.14.0"))).left
          .exists(_.message.contains("one Node version")),
        image("21", nodes = List(NodeVersion("lts"))) == Left(SbtImageError.NodeAliasUnpinned("lts")),
        image("21", coursier = true) == Left(SbtImageError.MissingToolPin(SbtImage.CsId, "version updates")),
        image("21", docker = true).exists(_.dockerfile.contains(s"docker-${SbtImage.DefaultDockerCli}.tgz")),
      )
    },
    test("an alias bakes the concrete node pin") {
      val plan = image("21", Seq(Pin("sbt-image", "node", "22.14.0")), List(NodeVersion("lts")))
      assertTrue(plan.exists(_.dockerfile.contains("node-v22.14.0-linux-")))
    },
    test("sbt jobs publish the image and keep the LocalDir snapshot off the runner home") {
      val plan = image("21").fold(err => throw AssertionError(err), identity)
      val wf   = Planner.plan(
        GraphFixture(List(ModuleNode(ModuleId("core")))),
        List(Capability.test),
        PlanConfig(sbtImage = Some(plan), skipMergedPrPush = false, verifyCleanLabel = None),
      )
      val test  = wf.jobs("test")
      val setup = test.steps.find(_.uses.contains(ZipxComposites.SbtSetupRef))
      assertTrue(
        wf.jobs.contains(SbtImageWorkflow.EnvImageJob),
        test.container.exists(_.contains("needs.zipx-env-image.outputs.image")),
        test.containerOptions.isEmpty,
        test.env.get("HOME").contains(SbtImage.CacheHome),
        test.env.get("JAVA_OPTS").exists(_.contains(s"-Duser.home=${SbtImage.CacheHome}")),
        test.env.get("GIT_CONFIG_VALUE_0").contains("*"),
        test.permissions.isEmpty,
        setup.exists(_.`with`.get("cache-home").contains(SbtImage.CacheHome)),
        !setup.exists(_.`with`.contains("baked-toolchain")),
        !setup.exists(_.`with`.contains("node-version")),
        !test.steps.exists(_.uses.exists(_.unwrap.startsWith("actions/setup-java@"))),
      )
    },
    test("macos and windows runners are refused for an sbt job") {
      val plan                                   = image("21").fold(err => throw AssertionError(err), identity)
      def refused(label: String): Option[String] =
        try
          Planner.plan(
            GraphFixture(List(ModuleNode(ModuleId("core")))),
            List(Capability.test),
            PlanConfig(sbtImage = Some(plan), runnerOs = RunnerOs.unsafeMake(label)),
          )
          None
        catch case e: RuntimeException => Some(e.getMessage)
      assertTrue(
        refused("macos-latest").exists(_.contains("macos-latest")),
        refused("windows-latest").exists(_.contains("windows-latest")),
      )
    },
    test("a derived image is published once and docker jobs mount the runner socket") {
      val plan   = image("21", docker = true).fold(err => throw AssertionError(err), identity)
      val docker = Capability(
        name = Capability.DockerName,
        phase = Phase.Publish,
        ordering = Ordering.DependencyOrdered,
        gate = Gate.OnReleaseTag,
        participates = _.docker,
        command = CommandSource.PerModule(n => SbtCommand.module(n, SbtCommand.unsafeTask("dockerBuild"))),
        matrixed = false,
        scope = CapabilityScope.Once,
      )
      val wf = Planner.plan(
        GraphFixture(List(ModuleNode(ModuleId("svc"), docker = true))),
        List(Capability.test, docker),
        PlanConfig(
          sbtImage = Some(plan),
          skipMergedPrPush = false,
          verifyCleanLabel = None,
          affected = AffectedMode.Always,
        ),
      )
      val yaml = Render.render(wf).fold(err => throw AssertionError(err), identity)
      assertTrue(
        wf.jobs.contains(SbtImageWorkflow.EnvImageJob),
        wf.jobs(SbtImageWorkflow.EnvImageJob).permissions.get("packages").contains("write"),
        wf.jobs(SbtImageWorkflow.EnvImageJob).env.get("GITHUB_TOKEN").contains("${{ github.token }}"),
        wf.jobs("test").needs.contains(SbtImageWorkflow.EnvImageJob),
        wf.jobs("test").container.exists(_.contains("needs.zipx-env-image.outputs.image")),
        wf.jobs("test").containerOptions.isEmpty,
        wf.jobs("docker").containerOptions.contains(SbtImage.SocketOptions),
        yaml.contains("container:"),
        yaml.contains("options:"),
        yaml.contains(SbtImage.SocketOptions),
        yaml.contains("docker manifest inspect"),
        !yaml.contains(s"container: ${plan.upstream}"),
      )
    },
    test("an sbt Docker task mounts the runner socket") {
      val plan    = image("21", docker = true).fold(err => throw AssertionError(err), identity)
      val publish = Capability.once(
        name = CapabilityName("image"),
        command = SbtCommand.unsafeTask("svcA/Docker/publishLocal"),
        phase = Phase.Verify,
        gate = Gate.Always,
      )
      val wf = Planner.plan(
        GraphFixture(List(ModuleNode(ModuleId("svc"), docker = true))),
        List(Capability.test, publish),
        PlanConfig(
          sbtImage = Some(plan),
          skipMergedPrPush = false,
          verifyCleanLabel = None,
          affected = AffectedMode.Always,
        ),
      )
      assertTrue(
        wf.jobs("image").containerOptions.contains(SbtImage.SocketOptions),
        wf.jobs("test").containerOptions.isEmpty,
      )
    },
    test("a permissions block gains packages read so it can pull the env image") {
      val plan = image("21", Seq(Pin("sbt-image", "cs", "2.1.26")), coursier = true)
        .fold(err => throw AssertionError(err), identity)
      val wf = Planner.plan(
        GraphFixture(List(ModuleNode(ModuleId("core")))),
        List(Capability.test.copy(permissions = Map("contents" -> "read"))),
        PlanConfig(
          sbtImage = Some(plan),
          skipMergedPrPush = false,
          verifyCleanLabel = None,
          affected = AffectedMode.Always,
        ),
      )
      assertTrue(
        wf.jobs("test").permissions.get("contents").contains("read"),
        wf.jobs("test").permissions.get("packages").contains("read"),
      )
    },
    test("inContainer is refused while the shared image is on") {
      val plan   = image("21").fold(err => throw AssertionError(err), identity)
      val failed =
        try
          Planner.plan(
            GraphFixture(List(ModuleNode(ModuleId("core")))),
            List(Capability.test.inContainer("ghcr.io/example/tool")),
            PlanConfig(sbtImage = Some(plan)),
          )
          None
        catch case e: RuntimeException => Some(e.getMessage)
      assertTrue(failed.exists(_.contains("inContainer")))
    },
    test("a GitHub remote names the published env image") {
      assertTrue(
        SbtImage.githubRepository("git@github.com:early-effect/zipx-ci-lab.git").contains("early-effect/zipx-ci-lab"),
        SbtImage.githubRepository("https://github.com/Early-Effect/Zipx.git").contains("Early-Effect/Zipx"),
        SbtImage.ghcrImage("Early-Effect/Zipx", "df-abc") == "ghcr.io/early-effect/zipx/zipx-env:df-abc",
      )
    },
    test("a layer forces a derived image, and the hash follows the rendered text") {
      val body = resolve(List(toolPin("widgets", "1.2.3")), List(widgets(widgetSteps))).toOption
      val next = resolve(List(toolPin("widgets", "1.2.4")), List(widgets(widgetSteps))).toOption
      val text = body.map(_.dockerfile).getOrElse("")
      val pkg  = text.linesIterator.find(_.contains("libwidgets=1.2.3-1")).getOrElse("")
      assertTrue(
        body.map(_.dockerfile).nonEmpty,
        body.map(_.derivedTag).contains(SbtImage.contentTag(text)),
        text.contains(s"FROM ${WolfiToolchain.reference(WolfiToolchain.BaseDigest)}"),
        text.contains("https://example.test/widgets/1.2.3/widgets-x86_64.tar.gz"),
        text.contains("https://example.test/widgets/1.2.3/widgets-aarch64.tar.gz"),
        text.contains("uname -m"),
        text.contains(": > '/opt/widgets/READY'"),
        pkg.contains("apk add --no-cache"),
        pkg.contains("'libwidgets=1.2.3-1'"),
        !pkg.contains("uname"),
        !text.contains("apt-get"),
        text.contains("""ENV WIDGET_PATH="/opt/widgets""""),
        !text.contains("nodejs.org"),
        next.map(_.derivedTag) != body.map(_.derivedTag),
      )
    },
    test("arch-specific apt versions are one install, split by the shell") {
      val layer = SbtImageLayer("widgets", noLookup).install { (version, arch) =>
        val deb = arch match
          case CpuArch.Amd64 => "1.0-amd64"
          case CpuArch.Arm64 => "1.0-arm64"
        List(
          ImageStep.Package("libwidgets", deb),
          ImageStep.Package("libother", deb),
          ImageStep.EmptyFile(s"/opt/widgets/$version/READY"),
        )
      }
      val text = resolve(List(toolPin("widgets", "1.2.3")), List(layer)).map(_.dockerfile).getOrElse("")
      assertTrue(
        text.contains("x86_64) pkgs='libwidgets=1.0-amd64 libother=1.0-amd64'"),
        text.contains("aarch64) pkgs='libwidgets=1.0-arm64 libother=1.0-arm64'"),
        text.contains("apk add --no-cache $pkgs"),
        !text.contains("\"$pkgs\""),
        text.contains("set -f"),
      )
    },
    test("every RUN parses as sh when layer URLs and apt versions differ by arch") {
      val gizmos = SbtImageLayer("gizmos", noLookup).install { (_, arch) =>
        val deb = arch match
          case CpuArch.Amd64 => "1.0-amd64"
          case CpuArch.Arm64 => "1.0-arm64"
        List(ImageStep.Package("libgizmos", deb))
      }
      val pins = List(toolPin("widgets", "1.2.3"), toolPin("gizmos", "1.0.0"))
      val text = resolve(pins, List(widgets(widgetSteps), gizmos)).map(_.dockerfile).getOrElse("")
      val runs = runBodies(text)
      ZIO
        .foreach(runs)(body => shellCheck(body).map(body -> _))
        .map { checked =>
          val broken = checked.collect { case (body, (exit, out)) if exit != 0 => s"$out\n$body" }
          assertTrue(runs.exists(_.contains("uname -m")), broken.isEmpty) ?? broken.mkString("\n\n")
        }
    },
    test("a download URL does not have to contain the pin text") {
      val layer = SbtImageLayer("widgets", noLookup).install { (version, arch) =>
        val revision = if version == "1.2.3" then "1148" else "0"
        val file     = arch match
          case CpuArch.Amd64 => "widget.zip"
          case CpuArch.Arm64 => "widget.zip"
        List(
          ImageStep.File(
            url = s"https://cdn.example.test/builds/$revision/$file",
            dest = "/usr/local/bin/widget",
            source = FileSource.Raw,
            mode = FileMode.Executable,
          )
        )
      }
      val text = resolve(List(toolPin("widgets", "1.2.3")), List(layer)).map(_.dockerfile).getOrElse("")
      assertTrue(
        text.contains("https://cdn.example.test/builds/1148/widget.zip"),
        !text.contains("1.2.3"),
        !text.contains("uname -m"),
      )
    },
    test("layer refusals name the case") {
      val badUrl = SbtImageLayer("widgets", noLookup).install { (_, _) =>
        List(ImageStep.File("http://example.test/w", "/opt/w", FileSource.Raw, FileMode.Executable))
      }
      assertTrue(
        resolve(Nil, List(widgets(widgetSteps))) == Left(SbtImageError.MissingLayerPin("widgets")),
        resolve(List(toolPin("node", "22.14.0")), List(SbtImageLayer("node", noLookup).install(widgetSteps))) == Left(
          SbtImageError.ReservedLayerId("node")
        ),
        resolve(List(toolPin("1bad", "1.0.0")), List(SbtImageLayer("1bad", noLookup).install(widgetSteps))) == Left(
          SbtImageError.BadLayerId("1bad")
        ),
        resolve(
          List(toolPin("widgets", "1.0.0")),
          List(widgets(widgetSteps), widgets(widgetSteps)),
        ) == Left(SbtImageError.DuplicateLayerId("widgets")),
        resolve(List(toolPin("widgets", "latest")), List(widgets(widgetSteps))) == Left(
          SbtImageError.LayerPinNotConcrete("widgets", "latest")
        ),
        resolve(List(toolPin("nope", "1.0.0")), Nil) == Left(SbtImageError.OrphanPin("nope")),
        resolve(List(toolPin("widgets", "1.0.0")), List(SbtImageLayer("widgets", noLookup).install((_, _) => Nil))) ==
          Left(SbtImageError.EmptyLayer("widgets")),
        resolve(List(toolPin("widgets", "1.0.0")), List(badUrl)) == Left(
          SbtImageError.BadUrl("widgets", "http://example.test/w")
        ),
        resolve(
          List(toolPin("widgets", "1.0.0")),
          List(
            SbtImageLayer("widgets", noLookup).install((_, _) => List(ImageStep.EmptyFile("/home/sbtuser/browsers")))
          ),
        ) == Left(SbtImageError.BadDest("widgets", "/home/sbtuser/browsers")),
        resolve(
          List(toolPin("widgets", "1.0.0")),
          List(SbtImageLayer("widgets", noLookup).install { (_, arch) =>
            List(ImageStep.EmptyFile(if arch == CpuArch.Amd64 then "/opt/widgets" else "/opt/widgets-arm"))
          }),
        ) == Left(
          SbtImageError.ArchMismatch(
            "widgets",
            "step 0 differs by arch in more than the URL or package version",
          )
        ),
        resolve(
          List(toolPin("widgets", "1.0.0")),
          List(
            SbtImageLayer("widgets", noLookup).install { (version, _) =>
              List(
                ImageStep.Tree(
                  s"https://example.test/w/$version.tgz",
                  "/opt/widgets",
                  ArchiveFormat.TarGz,
                  strip = -1,
                )
              )
            }
          ),
        ) == Left(SbtImageError.BadStrip("widgets", -1)),
        resolve(
          List(toolPin("widgets", "1.0.0")),
          List(SbtImageLayer("widgets", noLookup).install((_, _) => List(ImageStep.Package("libwidgets", "latest")))),
        ) == Left(SbtImageError.BadPackage("widgets", "libwidgets", "latest")),
        resolve(
          List(toolPin("widgets", "1.0.0")),
          List(widgets(widgetSteps).withEnv("HOME" -> "/opt/widgets")),
        ) == Left(SbtImageError.OwnedEnvKey("widgets", "HOME")),
        resolve(
          List(toolPin("widgets", "1.0.0")),
          List(widgets(widgetSteps).withEnv("WIDGET_PATH" -> "${{ github.workspace }}/target/ms-playwright")),
        ) == Left(SbtImageError.BadEnvValue("widgets", "WIDGET_PATH")),
        resolve(
          List(toolPin("widgets", "1.0.0"), toolPin("gadgets", "1.0.0")),
          List(widgets(widgetSteps), gadgets),
        ) == Left(SbtImageError.DuplicateEnvKey("WIDGET_PATH", "widgets", "gadgets")),
        SbtImage.shadowedFeed(Seq(PinFeed(SbtImage.Feed, VersionStrategy.exact, _ => Right(None)))) ==
          Some(SbtImageError.FeedShadowed),
        SbtImage.shadowedFeed(Nil) == None,
      )
    },
    test("a layer lookup stays on the pin's major") {
      assertTrue(
        SbtImage.layerCandidate("widgets", "1.2.3", "1.2.4") == Right(Some("1.2.4")),
        SbtImage.layerCandidate("widgets", "1.2.4", "1.2.4") == Right(None),
        SbtImage.layerCandidate("widgets", "1.2.3", "latest") == Left(SbtImageError.BadCandidate("widgets", "latest")),
        SbtImage.layerCandidate("widgets", "1.2.3", "2.0.0") == Left(
          SbtImageError.CandidateMajor("widgets", "2.0.0", 1)
        ),
      )
    },
    test("layer env is on the job, and a different job value is refused") {
      val plan = resolve(List(toolPin("widgets", "1.2.3")), List(widgets(widgetSteps)))
        .fold(err => throw AssertionError(err.message), identity)
      val wf = Planner.plan(
        GraphFixture(List(ModuleNode(ModuleId("core")))),
        List(Capability.test),
        PlanConfig(sbtImage = Some(plan), skipMergedPrPush = false, verifyCleanLabel = None),
      )
      val clashed =
        try
          Planner.plan(
            GraphFixture(List(ModuleNode(ModuleId("core")))),
            List(Capability.test),
            PlanConfig(
              sbtImage = Some(plan),
              skipMergedPrPush = false,
              verifyCleanLabel = None,
              env = Map("WIDGET_PATH" -> EnvValue.plain("/elsewhere")),
            ),
          )
          None
        catch case e: RuntimeException => Some(e.getMessage)
      assertTrue(
        wf.jobs.contains(SbtImageWorkflow.EnvImageJob),
        wf.jobs("test").env.get("WIDGET_PATH").contains("/opt/widgets"),
        wf.jobs("test").env.get("HOME").contains(SbtImage.CacheHome),
        plan.derivedTag.startsWith("df-"),
        clashed.exists(_.contains("WIDGET_PATH")),
      )
    },
  )
end SbtImageSpec
