// Example monorepo exercising zipx end-to-end. The graph deliberately mirrors the
// shape zipx targets: cross-built publishing libraries in a dependency chain, plus a
// non-publishing service (a docker target once M4 lands).
//
//   models ──▶ core-lib ──▶ client   (all publish, cross 2.13 + 3)
//     └───────────────────▶ service  (non-publishing app; depends on core-lib)
//
// zipx derives everything (module set, needs edges, publish order, matrix) from this.

val scala3 = "3.8.4"
val scala2 = "2.13.16"

scalaVersion := scala3
organization := "com.example"
version      := "1.4.2-ci" // stands in for sbt-dynver-ci output; drives the cache epoch (bare, a common setting)

// Build-level zipx config: plain bare settings (sbt 2.0 common settings). zipx reads these from the root project's
// scope, so no `ThisBuild /` prefix is needed.
zipxWorkflowName := WorkflowName("CI")
zipxJavaVersion  := JdkVersion("21")

lazy val models = project
  .settings(crossScalaVersions := Seq(scala2, scala3))

lazy val coreLib = (project in file("core-lib"))
  .dependsOn(models)
  .settings(crossScalaVersions := Seq(scala2, scala3))

lazy val client = project
  .dependsOn(coreLib)
  .settings(crossScalaVersions := Seq(scala2, scala3))

// A deploy-time promote task that re-tags the image with a tier-scoped moving tag. It reads the TIER env var that
// zipx injects from the deploy target, proving a user sbt task can consume per-target config (Gap 2).
val promote = taskKey[Unit]("Re-tag the image with a tier-scoped moving tag, using the injected TIER env var.")

// A service: not a Maven library, but a docker image. Enabling DockerPlugin is the ONLY signal
// zipx needs: it auto-detects the docker capability and generates a `docker` publish job running
// `service/Docker/publish`. The image is described here in the build, not in a Dockerfile.
lazy val service = (project in file("service"))
  .dependsOn(coreLib)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    publishArtifact      := false, // application, not a library
    crossScalaVersions   := Seq(scala3),
    Compile / mainClass  := Some("example.run"),
    dockerBaseImage      := "eclipse-temurin:21-jre",
    Docker / packageName := "example-service",
    dockerExposedPorts   := Seq(8080),
    dockerUpdateLatest   := true,
    // One build, every destination. `Docker / publish` builds the image once and pushes each of these, which is what
    // lets the multi-registry capability below be a single job. The registry hosts are *derived* from the account id
    // and region (project/Deploy.scala), so they cannot drift from the region the login step passes.
    dockerAliases := {
      val base   = dockerAlias.value
      val latest = dockerUpdateLatest.value
      Registry.all.flatMap { r =>
        val tagged = base.withRegistryHost(Some(r.registry.host))
        if (latest) List(tagged, tagged.withTag(Some("latest"))) else List(tagged)
      }
    },
    // In CI, the deploy job's `env:` block (from the target) sets TIER before sbt cold-starts, so this fresh JVM
    // reads it. (Locally, a long-lived sbt server predating the env may show the default, a dev-only artifact.)
    promote := {
      val tier = sys.env.getOrElse("TIER", "unknown") // injected by zipx from the deploy target's env
      val repo = (Docker / packageName).value
      streams.value.log.info(s"Promoting $repo to moving tag: $repo:main-$tier-latest")
    },
  )

lazy val root = (project in file("."))
  .aggregate(models, coreLib, client, service)
  .settings(publish / skip := true)

// Format gate, then Layer-mode test/publish (dependency-ordered waves, few sbt sessions).
// Deploy stays Aggregate-by-target (one job per staging/prod; modules batched). Multi-registry
// docker below is a single Aggregate job: the registries are destinations of one image build, not
// separate environments.
// `Fmt` is named once and referred to twice: a capability name is a validated `CapabilityName` (it becomes the
// `jobs.fmt` key), so naming the val is also how the dependent capability avoids repeating the literal.
val Fmt = CapabilityName("fmt")
zipxCapabilities += zipxTasks.once(Fmt, scalafmtCheckAll)
zipxCapabilities ++= Seq(
  Capability.testLayers.copy(needsCapabilities = List(Fmt)),
  Capability.publishLayers,
)

// Multi-registry image publish (Gap 1). Overrides the built-in single-target `docker` capability (same name ⇒
// replace) to push the service image to N registries, each with its own credentials.
//
// **One** job, not one per registry: `Docker / publish` builds the image once and pushes every `dockerAliases` entry
// (enumerated above from the same `Registry.all`), so a job per registry would rebuild the same image N times and stop
// guaranteeing the registries hold identical bytes. `ZipxAws.dockerPublishAll` is that shape: shared targets, OIDC
// then ECR docker login per destination, `id-token: write` already declared. Registries stay a typed Scala list
// (project/Deploy.scala), and each login step passes both `role-to-assume` and the registry's own `aws-region`, which
// is not omittable because `EcrRegistry` has no constructor without a region.
zipxCapabilities += ZipxAws.dockerPublishAll(Registry.destinations)

// --- Deploy: staging + production, with production behind a GitHub Environment approval gate. ---
//
// Deploy targets are defined in project/Deploy.scala (a typed Scala list, the replacement for an
// external YAML config + resolver script). zipx knows nothing about clouds/tiers; it just fans out
// one job per target, binds the GitHub Environment, injects the env, and wires needs.
// Note: the deploy command is given as the real `promote` TaskKey (not a string) via `zipxTasks.deploy`, so it's
// code-completed and compile-checked. zipx renders it to `<module>/promote`. It reads the injected TIER env (Gap 2).
zipxCapabilities += zipxTasks
  .deploy(
    participates = _.id == "service",
    command = promote,
    targets = _ =>
      DeployEnv.all.map(e =>
        Target(
          name = e.name,
          environment = e.ghEnvironment,
          // The env keys the OIDC login bundle reads, named by the pack rather than spelled here, so the step and the
          // block cannot disagree about a name. `TIER` is this build's own, read by the `promote` task above.
          env = Map(
            ZipxAws.RegionEnv -> EnvValue.plain(e.region),
            ZipxAws.RoleEnv   -> e.roleSecret,
            "TIER"            -> EnvValue.plain(e.tier),
          ),
        )
      ),
    needsCapabilities = List(Capability.DockerName), // deploy waits on the (multi-registry) image publish
    permissions = ZipxAws.oidcPermissions,           // OIDC: id-token write, contents read
  )
  .copy(
    // The extension seam: assume the cloud role (from the target's env) before running the deploy command. A named
    // `Steps` bundle from zipx-aws rather than a hand-written step, so the action is SHA-pinned from the pin file and
    // `aws-region` is not something this build can forget to pass.
    extraSteps = ZipxAws.oidcLoginSteps
  )
