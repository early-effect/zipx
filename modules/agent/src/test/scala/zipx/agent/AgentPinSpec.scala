package zipx.agent

import zipx.core.Pin
import zio.test.*

object AgentPinSpec extends ZIOSpecDefault:

  private val docker28 =
    """Package: docker-ce
      |Version: 5:28.5.1-1~ubuntu.24.04~noble
      |
      |Package: docker-ce-cli
      |Version: 5:29.0.0-1~ubuntu.24.04~noble
      |
      |Package: docker-ce
      |Version: 5:28.5.2-1~ubuntu.24.04~noble
      |
      |Package: docker-ce
      |Version: 5:29.1.0-1~ubuntu.24.04~noble
      |""".stripMargin

  private val dockerArmMissingLatest =
    """Package: docker-ce
      |Version: 5:28.5.1-1~ubuntu.24.04~noble
      |
      |Package: docker-ce
      |Version: 5:29.1.0-1~ubuntu.24.04~noble
      |""".stripMargin

  def spec = suite("Agent pins")(
    test("docker stays on the newest 28 published for both architectures") {
      val picked = DockerCe.newest(docker28, docker28)
      assertTrue(picked == Right(Some(AgentPins.DefaultDocker)))
    },
    test("a 28 that only one architecture publishes is not the candidate") {
      val picked = DockerCe.candidate(AgentPins.DefaultDocker, docker28, dockerArmMissingLatest)
      assertTrue(picked == Right(Some("5:28.5.1-1~ubuntu.24.04~noble")))
    },
    test("docker 29 is listed in the index and is not the candidate") {
      val versions = DockerCe.packageVersions(docker28, "docker-ce")
      assertTrue(
        versions.contains("5:29.1.0-1~ubuntu.24.04~noble"),
        DockerCe.newest(docker28, docker28) == Right(Some(AgentPins.DefaultDocker)),
      )
    },
    test("docker-cli stays on the newest 28 published for both architectures") {
      val body =
        """Package: docker-ce-cli
          |Version: 5:28.5.1-1~ubuntu.24.04~noble
          |
          |Package: docker-ce-cli
          |Version: 5:28.5.2-1~ubuntu.24.04~noble
          |
          |Package: docker-ce-cli
          |Version: 5:29.1.0-1~ubuntu.24.04~noble
          |""".stripMargin
      assertTrue(
        DockerCe.newestCli(body, body) == Right(Some("28.5.2")),
        DockerCe.newestCli(body, dockerArmMissingLatest).isLeft,
      )
    },
    test("an already current docker pin is not a bump") {
      assertTrue(DockerCe.candidate(AgentPins.DefaultDocker, docker28, docker28) == Right(None))
    },
    test("a docker pin off the 28 line is refused at render") {
      assertTrue(AgentPins.validateDocker("5:29.0.0-1~ubuntu.24.04~noble").isLeft)
    },
    test("ubuntu candidate is a new digest for the 24.04 tag") {
      val body =
        """{"name":"24.04","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
      assertTrue(
        UbuntuBase.candidate(AgentPins.DefaultBase, body) ==
          Right(Some("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
        UbuntuBase.candidate(
          "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          body,
        ) == Right(None),
      )
    },
    test("sbt runner candidates stay on the 2.x line and skip the current release") {
      val body =
        """[{"tag_name":"v1.12.0"},{"tag_name":"v2.0.7"},{"tag_name":"v2.0.8"},{"tag_name":"v2.1.0-RC1"},{"tag_name":"v3.0.0"}]"""
      assertTrue(
        SbtRunner.versions(body) == List("2.0.7", "2.0.8"),
        SbtRunner.candidate("2.0.7", body).contains("2.0.8"),
        SbtRunner.candidate("2.0.8", body).isEmpty,
      )
    },
    test("resolve names every missing pin") {
      val missing = AgentPins.resolve(Seq(Pin("agent-sbt", "sbt-runner", "2.0.7")))
      assertTrue(
        missing.left.exists(_.contains("agent-base")),
        missing.left.exists(_.contains("agent-docker")),
      )
    },
    test("resolve accepts the three catalog pins") {
      val pins = Seq(
        Pin("agent-base", "ubuntu", "sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac"),
        Pin("agent-docker", "docker-ce", "5:28.5.2-1~ubuntu.24.04~noble"),
        Pin("agent-sbt", "sbt-runner", "2.0.7"),
      )
      assertTrue(
        AgentPins.resolve(pins) == Right(
          AgentPins(AgentPins.DefaultBase, AgentPins.DefaultDocker, AgentPins.DefaultSbtRunner)
        )
      )
    },
  )
end AgentPinSpec
