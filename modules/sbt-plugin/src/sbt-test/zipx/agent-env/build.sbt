scalaVersion   := "3.9.0"
version        := "1.0.0-SNAPSHOT"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-SNAPSHOT")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
zipxAgentEnv   := ZipxAgentEnv.cursor(docker = true)
zipxEnv        := Map("REGISTRY_TOKEN" -> secret"REGISTRY_TOKEN")
zipxPins       := Seq(
  Pin("agent-base", "ubuntu", "sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac"),
  Pin("agent-docker", "docker-ce", "5:28.5.2-1~ubuntu.24.04~noble"),
  Pin("agent-sbt", "sbt-runner", "2.0.7"),
)

lazy val root = (project in file("."))
  .settings(publish / skip := true)

val assertAgentEnv = taskKey[Unit]("assert the rendered cloud agent environment")
assertAgentEnv := {
  val base    = (LocalRootProject / baseDirectory).value
  val json    = IO.read(base / ".cursor" / "environment.json")
  val docker  = IO.read(base / ".cursor" / "Dockerfile")
  val install = IO.read(base / ".github" / "zipx-agent" / "install.sh")
  assert(!json.contains("\"context\""), json)
  assert(json.contains("sudo service docker start"), json)
  assert(json.contains("bash .github/zipx-agent/install.sh"), json)
  assert(docker.contains("fuse-overlayfs"), docker)
  assert(docker.contains("5:28.5.2-1~ubuntu.24.04~noble"), docker)
  assert(!docker.contains("x86_64"), docker)
  assert(!docker.toLowerCase.contains("temurin"), docker)
  assert(install.contains("REGISTRY_TOKEN"), install)
  assert(install.contains("sbt --server --batch"), install)
  assert(install.contains(". project/zipx-ci.env"), install)
  assert(install.contains("COURSIER_TTL"), install)
  assert(!install.contains("x86_64"), install)
}
