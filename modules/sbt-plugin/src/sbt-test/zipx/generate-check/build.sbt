// Bare settings (sbt 2.0 common settings): apply to every module, overridable per module. No `ThisBuild /` needed.
scalaVersion := "3.8.4"
version      := "1.0.0-ci"
// A build-wide default test task; `client` overrides it back to plain `test` below to prove per-module override.
zipxTestTask := "testFull"

// A small cross-published monorepo: a models lib, an api that depends on it, and a client
// that depends on api — plus a non-publishing service.
lazy val schema = project
  .settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))

lazy val api = project
  .dependsOn(schema)
  .settings(crossScalaVersions := Seq("2.13.16", "3.8.4"))

lazy val client = project
  .dependsOn(api)
  .settings(
    crossScalaVersions := Seq("2.13.16", "3.8.4"),
    zipxTestTask       := "test", // overrides the build-wide `testFull`
  )

lazy val service = project
  .dependsOn(api)
  .settings(
    publishArtifact := false // non-publishing → test job only, no publish job
  )

lazy val root = (project in file("."))
  .aggregate(schema, api, client, service)
  .settings(publish / skip := true)

// A real build-wide task, referenced as a TYPED key (not a string) via zipxTasks.once — proves the typed,
// IDE-friendly capability API renders to the same `<label>` command.
val lintAll = taskKey[Unit]("a build-wide lint gate")
lintAll := ()
zipxCapabilities += zipxTasks.once("lint", lintAll)

// A typed CONFIG-SCOPED key (`Compile / compile`) — proves zipxTasks renders the config axis: <module>/Compile/compile.
zipxCapabilities += zipxTasks.custom(
  name = "compileCheck",
  command = Compile / compile,
  participates = _.id == "schema",
  gate = Gate.Always,
)

// The cmd"…" interpolator: literal command syntax (`+ `) + a typed key splice, module-scoped → +<module>/publish.
// cmd produces a ModuleNode => String, so it's passed to the core Capability.custom (which takes that function).
zipxCapabilities += Capability.custom(
  name = "crossPublishCheck",
  command = cmd"+ ${publish}",
  participates = _.id == "schema",
  gate = Gate.Always,
)

// MIXED splices (the macro's point): a String value AND a typed key in one command → `++2.13.16; <module>/publish`.
val scalaSwitch = "2.13.16"
zipxCapabilities += Capability.custom(
  name = "mixedCheck",
  command = cmd"++${scalaSwitch}; ${publish}",
  participates = _.id == "api",
  gate = Gate.Always,
)

// A publish-style capability carrying typed secrets (M7) — no raw "${{ secrets.X }}" strings.
// Graph modes exercise affected / matrix / per-module needs (scripted asserts those shapes).
zipxCapabilities ++= Seq(
  Capability.testGraph,
  Capability.publishGraph.copy(
    env = Map(
      "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
      "SONATYPE_USERNAME" -> Secret("SONATYPE_USERNAME"),
    )
  ),
)

// Assertions run inside the scripted test.
val assertGraph = taskKey[Unit]("assert the graph and generated workflow are correct")
assertGraph := {
  val wf      = (LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml"
  val content = IO.read(wf)
  // Test jobs for every real module; the aggregating root gets no job.
  assert(content.contains("test-schema:"), "missing test-schema job")
  assert(content.contains("test-service:"), "missing test-service job")
  assert(!content.contains("test-root:"), "the aggregating root must not get a test job")
  // Publish jobs only for publishers, dependency-ordered; non-publishing service and root excluded.
  assert(content.contains("publish-schema:"), "missing publish-schema job")
  assert(!content.contains("publish-service:"), "service must not have a publish job")
  assert(!content.contains("publish-root:"), "aggregating root must not have a publish job")
  // needs wiring derived from dependsOn.
  assert(content.contains("- test-schema"), "test-api should need test-schema")
  assert(content.contains("- publish-schema"), "publish-api should need publish-schema")
  // Cross-scala matrix present for cross-built modules (zio-blocks quotes version-like scalars).
  assert(content.contains("\"2.13.16\""), "expected scala matrix entry")
  // Propagate-down with override: service inherits the build-wide `testFull`; client overrides back to `test`.
  assert(content.contains("service/testFull"), "service should inherit the build-wide testFull task")
  assert(content.contains("schema/testFull"), "schema should inherit the build-wide testFull task")
  assert(content.contains("client/test'"), "client should override back to plain test")
  assert(!content.contains("client/testFull"), "client must NOT use the inherited testFull")
  // LocalDir: epoch+run_id+job primary key (same-run accumulate + each job can save).
  assert(
    content.contains("ubuntu-latest-jdk21-sbt-1.0.0-ci-${{ github.run_id }}-test-schema"),
    "cache key should embed epoch + run_id + job id",
  )
  assert(content.contains("disk-cache: \"false\""), "LocalDir must disable setup-sbt hashFiles disk-cache")
  assert(!content.contains("cache: sbt"), "LocalDir must not enable setup-java cache:sbt")
  assert(content.contains("target"), "cache path should include target/ for compile + sona-staging reuse")
  // M3: affected-only setup job + gating on verify jobs (default AffectedOnPR).
  assert(content.contains("affected:"), "missing affected setup job")
  assert(content.contains("modules: ${{ steps.compute.outputs.modules }}"), "affected job should output modules")
  assert(content.contains("fetch-depth:"), "affected job should checkout full history")
  assert(
    content.contains("contains(fromJson(needs.affected.outputs.modules), 'api')"),
    "test-api should gate on affected membership",
  )
  assert(content.contains("!cancelled()"), "affected verify jobs must guard with !cancelled()")
  assert(
    content.contains("needs.test-schema.result != 'failure'"),
    "downstream verify jobs must tolerate skipped upstreams",
  )
  // Publish jobs are release-gated and NOT matrixed.
  assert(content.contains("startsWith(github.ref, 'refs/tags/v')"), "publish jobs should gate on a release tag")
  assert(!content.contains("++${{ matrix.scala }} +"), "publish must not combine matrix leg with +publish")
  // No module here enables DockerPlugin, so no docker stage should leak in.
  assert(!content.contains("docker-"), "docker stage must be absent when no module opts in")
  // The typed `zipxTasks.once(..., lintAll)` renders a single build-wide job running the key's label.
  assert(content.contains("lint:"), "typed once-capability should emit a build-wide `lint` job")
  assert(content.contains("sbt 'lintAll'"), "typed key should render to its bare label command")
  // A config-scoped typed key renders the config axis: <module>/Compile/compile.
  assert(content.contains("sbt 'schema/Compile/compile'"), "typed config-scoped key should render its config axis")
  // The cmd"…" interpolator: literal `+ ` syntax + a module-scoped typed key splice.
  assert(content.contains("sbt '+ schema/publish'"), "cmd interpolator should emit literal syntax + module-scoped key")
  // Mixed splices: a String (scalaSwitch) and a typed key in one cmd → `++2.13.16; api/publish`.
  assert(content.contains("sbt '++2.13.16; api/publish'"), "cmd should mix a String splice with a module-scoped key")
  // M7: typed secrets render into publish job env (capability.env).
  assert(
    content.contains("PGP_PASSPHRASE: ${{ secrets.PGP_PASSPHRASE }}"),
    "typed secret should render into publish env",
  )
  assert(content.contains("SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}"), "Secret() helper should render")
}
