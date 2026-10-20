package zipx.core

import zipx.workflow.*
import zio.test.*

object SbtImageSpec extends ZIOSpecDefault:

  private val digest = "a" * 64
  private val newer  = "b" * 64
  private val tag21  = "eclipse-temurin-21.0.12_8_2.x"
  private val older  = "eclipse-temurin-21.0.11_10_2.x"
  private val pin    = s"$tag21@sha256:$digest"

  private val tags = List(
    older,
    tag21,
    "eclipse-temurin-21_2.x",
    "eclipse-temurin-21.0.12_8_1.11.7_3.3.6",
    "eclipse-temurin-25.0.3_9_2.x",
    "eclipse-temurin-25.0.4_7_2.x",
  )

  private def imagePin(version: String): Pin =
    Pin(PinFeedName.unsafeMake("sbt-image"), SbtImage.ImageId, DepVersion.unsafeMake(version), None, None)

  def spec = suite("Sbt image")(
    test("a light tag pins the manifest digest in the version") {
      val parsed = SbtImage.parse(pin)
      assertTrue(
        parsed.map(_.reference) == Right(s"sbtscala/scala-sbt:$pin"),
        parsed.map(_.major) == Right(21),
        SbtImage.parse(tag21).isLeft,
        SbtImage.parse(s"$tag21@sha256:${"a" * 63}").isLeft,
      )
    },
    test("the newest JDK patch wins, and fat or floating tags do not") {
      assertTrue(
        SbtImage.select(21, tags) == Right(tag21),
        SbtImage.select(25, tags) == Right("eclipse-temurin-25.0.4_7_2.x"),
        SbtImage.select(17, tags).isLeft,
        SbtImage.candidate(pin, tags, s"sha256:$digest") == Right(None),
        SbtImage.candidate(pin, tags, s"sha256:$newer") == Right(Some(s"$tag21@sha256:$newer")),
        SbtImage.candidate(s"$older@sha256:$digest", tags, s"sha256:$digest") == Right(Some(pin)),
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
    test("the upstream image is used when no extra tool is declared") {
      val plan = SbtImage.resolve(JdkVersion("21"), Seq(imagePin(pin)), Nil, coursier = false, docker = false)
      assertTrue(
        plan.map(_.upstream) == Right(s"sbtscala/scala-sbt:$pin"),
        plan.map(_.dockerfile) == Right(None),
        plan.map(_.dockerSocket) == Right(false),
      )
    },
    test("node, cs, and the docker client are baked, and the tag is the Dockerfile hash") {
      val pins = Seq(imagePin(pin), Pin("sbt-image", "cs", "2.1.24"), Pin("sbt-image", "docker-cli", "28.5.2"))
      val plan = SbtImage.resolve(
        JdkVersion("21"),
        pins,
        List(NodeVersion("22.14.0")),
        coursier = true,
        docker = true,
      )
      val body = plan.toOption.flatMap(_.dockerfile).getOrElse("")
      assertTrue(
        plan.exists(_.derivedTag.exists(_.startsWith("df-"))),
        body.contains(s"FROM sbtscala/scala-sbt:$pin"),
        body.contains("node-v22.14.0-linux-"),
        body.contains("node.tar.gz"),
        !body.contains("tar.xz"),
        body.contains("coursier/releases/download/v2.1.24/cs-"),
        body.contains("docker-28.5.2.tgz"),
        !body.contains("TOKEN"),
        !body.contains(".cache"),
        plan.exists(image => image.derivedTag.contains(SbtImage.contentTag(body))),
        plan.exists(_.dockerSocket),
      )
    },
    test("two node versions, an alias without a pin, and a JDK major mismatch are refused") {
      val pins = Seq(imagePin(pin))
      assertTrue(
        SbtImage
          .resolve(JdkVersion("21"), pins, List(NodeVersion("20.11.0"), NodeVersion("22.14.0")), false, false)
          .left
          .exists(_.contains("one Node version")),
        SbtImage.resolve(JdkVersion("21"), pins, List(NodeVersion("lts")), false, false).isLeft,
        SbtImage.resolve(JdkVersion("25"), pins, Nil, false, false).left.exists(_.contains("Temurin 21")),
        SbtImage.resolve(JdkVersion("21"), pins, Nil, coursier = true, docker = false).left.exists(_.contains("cs")),
        SbtImage.resolve(JdkVersion("21"), pins, Nil, coursier = false, docker = true).isLeft,
      )
    },
    test("an alias bakes the concrete node pin") {
      val pins = Seq(imagePin(pin), Pin("sbt-image", "node", "22.14.0"))
      val plan = SbtImage.resolve(JdkVersion("21"), pins, List(NodeVersion("lts")), false, false)
      assertTrue(plan.toOption.flatMap(_.dockerfile).exists(_.contains("node-v22.14.0-linux-")))
    },
    test("jobs use the upstream image and keep the LocalDir snapshot off the runner home") {
      val plan = SbtImage
        .resolve(JdkVersion("21"), Seq(imagePin(pin)), Nil, coursier = false, docker = false)
        .fold(err => throw AssertionError(err), identity)
      val wf = Planner.plan(
        GraphFixture(List(ModuleNode(ModuleId("core")))),
        List(Capability.test),
        PlanConfig(sbtImage = Some(plan), skipMergedPrPush = false, verifyCleanLabel = None),
      )
      val test  = wf.jobs("test")
      val setup = test.steps.find(_.uses.contains(ZipxComposites.SbtSetupRef))
      assertTrue(
        !wf.jobs.contains(SbtImageWorkflow.EnvImageJob),
        test.container.contains(plan.upstream),
        test.containerOptions.isEmpty,
        test.env.get("HOME").contains(SbtImage.CacheHome),
        test.env.get("JAVA_OPTS").exists(_.contains(s"-Duser.home=${SbtImage.CacheHome}")),
        test.env.get("GIT_CONFIG_VALUE_0").contains("*"),
        test.permissions.isEmpty,
        setup.exists(_.`with`.get("baked-toolchain").contains("true")),
        setup.exists(_.`with`.get("cache-home").contains(SbtImage.CacheHome)),
        setup.exists(_.`with`.get("node-version").contains("")),
      )
    },
    test("a derived image is published once and docker jobs mount the runner socket") {
      val pins = Seq(imagePin(pin), Pin("sbt-image", "docker-cli", "28.5.2"))
      val plan = SbtImage
        .resolve(JdkVersion("21"), pins, Nil, coursier = false, docker = true)
        .fold(err => throw AssertionError(err), identity)
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
      val pins = Seq(imagePin(pin), Pin("sbt-image", "docker-cli", "28.5.2"))
      val plan = SbtImage
        .resolve(JdkVersion("21"), pins, Nil, coursier = false, docker = true)
        .fold(err => throw AssertionError(err), identity)
      val image = Capability.once(
        name = CapabilityName("image"),
        command = SbtCommand.unsafeTask("svcA/Docker/publishLocal"),
        phase = Phase.Verify,
        gate = Gate.Always,
      )
      val wf = Planner.plan(
        GraphFixture(List(ModuleNode(ModuleId("svc"), docker = true))),
        List(Capability.test, image),
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
      val pins = Seq(imagePin(pin), Pin("sbt-image", "cs", "2.1.26"))
      val plan = SbtImage
        .resolve(JdkVersion("21"), pins, Nil, coursier = true, docker = false)
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
      val plan = SbtImage
        .resolve(JdkVersion("21"), Seq(imagePin(pin)), Nil, false, false)
        .fold(err => throw AssertionError(err), identity)
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
    test("a GitHub origin names the agent image") {
      assertTrue(
        SbtImage.githubRepository("git@github.com:early-effect/zipx-ci-lab.git").contains("early-effect/zipx-ci-lab"),
        SbtImage.githubRepository("https://github.com/Early-Effect/Zipx.git").contains("Early-Effect/Zipx"),
        SbtImage.ghcrImage("Early-Effect/Zipx", "df-abc") == "ghcr.io/early-effect/zipx/zipx-env:df-abc",
      )
    },
  )
end SbtImageSpec
