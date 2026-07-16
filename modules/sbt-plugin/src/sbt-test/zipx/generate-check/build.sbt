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
    publishArtifact := false, // non-publishing → test job only, no publish job
  )

lazy val root = (project in file("."))
  .aggregate(schema, api, client, service)
  .settings(publish / skip := true)

// Assertions run inside the scripted test.
val assertGraph = taskKey[Unit]("assert the graph and generated workflow are correct")
assertGraph := {
  val wf = (LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml"
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
  // Commit-stable cache key uses the epoch (version).
  assert(content.contains("ubuntu-latest-jdk21-sbt-1.0.0-ci"), "cache key should embed the epoch")
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
}
