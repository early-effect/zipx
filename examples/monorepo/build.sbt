// Example monorepo exercising zipx end-to-end. The graph deliberately mirrors the
// shape zipx targets: cross-built publishing libraries in a dependency chain, plus a
// non-publishing service (a docker target once M4 lands).
//
//   models ──▶ core-lib ──▶ client   (all publish, cross 2.13 + 3)
//     └───────────────────▶ service  (non-publishing app; depends on core-lib)
//
// zipx derives everything — module set, needs edges, publish order, matrix — from this.

val scala3 = "3.8.4"
val scala2 = "2.13.16"

scalaVersion := scala3
organization := "com.example"
version := "1.4.2-ci" // stands in for sbt-dynver-ci output; drives the cache epoch (bare — a common setting)

// Build-level zipx config — plain bare settings (sbt 2.0 common settings). zipx reads these from the root project's
// scope, so no `ThisBuild /` prefix is needed.
zipxWorkflowName := "CI"
zipxJavaVersion  := "21"

lazy val models = project
  .settings(crossScalaVersions := Seq(scala2, scala3))

lazy val coreLib = (project in file("core-lib"))
  .dependsOn(models)
  .settings(crossScalaVersions := Seq(scala2, scala3))

lazy val client = project
  .dependsOn(coreLib)
  .settings(crossScalaVersions := Seq(scala2, scala3))

// A service: not a Maven library, but a docker image. Enabling DockerPlugin is the ONLY signal
// zipx needs — it auto-detects the docker capability and generates a `docker-service` publish job
// running `service/Docker/publish`. The image is described here in the build, not in a Dockerfile.
// A deploy-time promote task that re-tags the image with a tier-scoped moving tag. It reads the TIER env var that
// zipx injects from the deploy target — proving a user sbt task can consume per-target config (Gap 2).
val promote = taskKey[Unit]("Re-tag the image with a tier-scoped moving tag, using the injected TIER env var.")

// A service: not a Maven library, but a docker image. Enabling DockerPlugin is the ONLY signal
// zipx needs — it auto-detects the docker capability and generates a `docker-service` publish job
// running `service/Docker/publish`. The image is described here in the build, not in a Dockerfile.
lazy val service = (project in file("service"))
  .dependsOn(coreLib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    publishArtifact    := false, // application, not a library
    crossScalaVersions := Seq(scala3),
    Compile / mainClass    := Some("example.run"),
    dockerBaseImage        := "eclipse-temurin:21-jre",
    Docker / packageName   := "example-service",
    dockerExposedPorts     := Seq(8080),
    dockerUpdateLatest     := true,
    // In CI, the deploy job's `env:` block (from the target) sets TIER before sbt cold-starts, so this fresh JVM
    // reads it. (Locally, a long-lived sbt server predating the env may show the default — a dev-only artifact.)
    promote := {
      val tier = sys.env.getOrElse("TIER", "unknown") // injected by zipx from the deploy target's env
      val repo = (Docker / packageName).value
      streams.value.log.info(s"Promoting $repo to moving tag: $repo:main-$tier-latest")
    },
  )

lazy val root = (project in file("."))
  .aggregate(models, coreLib, client, service)
  .settings(publish / skip := true)

// A build-wide format gate that every test job waits on (Gap 3: run-once capability). One job, not per-module.
// The command is the real `scalafmtCheckAll` task key (typed, not a string) — from sbt-scalafmt.
zipxCapabilities += zipxTasks.once("fmt", scalafmtCheckAll)
zipxCapabilities += Capability.test.copy(needsCapabilities = List("fmt"))

// Multi-registry image publish (Gap 1). Overrides the built-in single-target `docker` capability (same name ⇒
// replace) to push the service image to N registries, each with its own credentials — the same targets+extraSteps
// machinery deploy uses. Registries are a typed Scala list (project/Deploy.scala). The command uses the `cmd"…"`
// interpolator with the real config-scoped `Docker / publish` key → `<module>/Docker/publish`. (cmd also carries
// command syntax around a key when you need it, e.g. `cmd"+ ${publish}"` or `cmd"++${scalaV}; ${publish}"`.)
zipxCapabilities += Capability.custom(
  name = "docker",
  command = cmd"${Docker / publish}",
  participates = _.docker,
  phase = Phase.Publish,
  targets = _ =>
    Registry.all.map(r =>
      Target(
        name = r.name,
        env = Map(
          "REGISTRY"    -> EnvValue.plain(r.host),
          "DEPLOY_ROLE" -> secret"${r.roleSecret}",
        ),
      )
    ),
  permissions = Map("id-token" -> "write", "contents" -> "read"),
).copy(
  extraSteps = _ =>
    List(
      Step(
        name = Some("Login to registry"),
        uses = Some("aws-actions/configure-aws-credentials@v6"),
        `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}"),
      )
    ),
)

// --- Deploy: staging + production, with production behind a GitHub Environment approval gate. ---
//
// Deploy targets are defined in project/Deploy.scala (a typed Scala list — the replacement for an
// external YAML config + resolver script). zipx knows nothing about clouds/tiers — it just fans out
// one job per target, binds the GitHub Environment, injects the env, and wires needs.
// Note: the deploy command is given as the real `promote` TaskKey (not a string) via `zipxTasks.deploy` — so it's
// code-completed and compile-checked. zipx renders it to `<module>/promote`. It reads the injected TIER env (Gap 2).
zipxCapabilities += zipxTasks.deploy(
  participates = _.id == "service",
  command = promote,
  targets = _ =>
    DeployEnv.all.map(e =>
      Target(
        name = e.name,
        environment = e.ghEnvironment,
        env = Map(
          "AWS_REGION"  -> EnvValue.plain(e.region),
          "DEPLOY_ROLE" -> secret"${e.roleSecret}",
          "TIER"        -> EnvValue.plain(e.tier),
        ),
        condition = Some("github.ref == 'refs/heads/main'"),
      )
    ),
  needsCapabilities = List("docker"), // deploy waits on the (multi-registry) image publish
  permissions = Map("id-token" -> "write", "contents" -> "read"), // OIDC
).copy(
  // The extension seam: assume the cloud role (from the target's env) before running the deploy command.
  extraSteps = _ =>
    List(
      Step(
        name = Some("Configure AWS credentials"),
        uses = Some("aws-actions/configure-aws-credentials@v6"),
        `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}", "aws-region" -> "${{ env.AWS_REGION }}"),
      )
    ),
)
