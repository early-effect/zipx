// A cross-built module: the case `baseDir` alone cannot answer for (#73).
//
// sbt 2 has `projectMatrix` built in, and it bases every platform row at a synthetic `.sbt/matrix/<id>`
// rather than at `shared/`. So the only thing that can map `shared/src/main/scala/Foo.scala` back to a
// module is `unmanagedSourceDirectories`, which is what `ModuleNode.sourcePaths` records. Asserted through
// `target/zipx-affected.json`, the handoff CI actually reads, never by capturing sbt stdout.
scalaVersion   := "3.8.4"
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))

lazy val shared = (projectMatrix in file("shared"))
  .jvmPlatform(scalaVersions = Seq("3.8.4"))
  .jsPlatform(scalaVersions = Seq("3.8.4"))

// Depends on the JVM row only, so a JS-only change must not reach it.
lazy val consumer = project
  .dependsOn(shared.jvm("3.8.4"))

lazy val root = (project in file("."))
  .aggregate((shared.projectRefs ++ Seq[ProjectReference](consumer)) *)
  .settings(publish / skip := true)

// withNodeVersion also proves the NodeVersion newtype and the setupNode pin reach a real build.sbt.
zipxCapabilities += Capability.testGraph.withNodeVersion(NodeVersion("22"))

val assertBothRowsGetJobs = taskKey[Unit]("each platform row gets its own gated test job")
assertBothRowsGetJobs := {
  val content = IO.read((LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml")
  assert(content.contains("test-shared:"), "missing test-shared job")
  assert(content.contains("test-sharedJS:"), "missing test-sharedJS job")
  assert(content.contains("test-consumer:"), "missing test-consumer job")
  assert(!content.contains("test-root:"), "the aggregating root must not get a test job")
  assert(
    content.contains("contains(fromJson(needs.affected.outputs.modules), 'sharedJS')"),
    "the JS row must gate on its own module id",
  )
  // Node is wired through zipx-sbt-setup (pin lives in the generated composite, not the job body).
  assert(content.contains("uses: ./.github/actions/zipx-sbt-setup"), "expected zipx-sbt-setup composite")
  assert(content.contains("node-version: \"22\""), "expected the node-version input on the composite")
  assert(!content.contains("actions/setup-node@"), "setup-node must not be inlined in the workflow")
  assert(!content.contains("Setup Node 22"), "Setup Node step name lives in the composite")
  val setup =
    IO.read((LocalRootProject / baseDirectory).value / ".github" / "actions" / "zipx-sbt-setup" / "action.yml")
  assert(setup.contains("actions/setup-node@"), "composite must SHA-pin setup-node")
  assert(setup.contains("Setup Node"), "composite must name the Setup Node step")
  val awsLogin = (LocalRootProject / baseDirectory).value / ".github" / "actions" / "zipx-aws-login"
  assert(!awsLogin.exists, "non-AWS consumer must not get zipx-aws-login")
}

val assertSharedAffectsBothRows = taskKey[Unit]("a shared source change affects both platform rows")
assertSharedAffectsBothRows := {
  val json = IO.read((LocalRootProject / baseDirectory).value / "target" / "zipx-affected.json").trim
  assert(json.contains("\"shared\""), s"a shared change must affect the JVM row, got $json")
  assert(json.contains("\"sharedJS\""), s"a shared change must affect the JS row, got $json")
  assert(json.contains("\"consumer\""), s"a shared change must reach the JVM row's dependent, got $json")
}

val assertJsSourceAffectsJsRowOnly = taskKey[Unit]("a scalajs change affects only the JS row")
assertJsSourceAffectsJsRowOnly := {
  val json = IO.read((LocalRootProject / baseDirectory).value / "target" / "zipx-affected.json").trim
  assert(json.contains("\"sharedJS\""), s"a scalajs change must affect the JS row, got $json")
  assert(!json.contains("\"shared\""), s"a scalajs change must not affect the JVM row, got $json")
  assert(!json.contains("\"consumer\""), s"a scalajs change must not reach the JVM row's dependent, got $json")
}

val assertJvmSourceAffectsJvmRowOnly = taskKey[Unit]("a scalajvm change affects only the JVM row")
assertJvmSourceAffectsJvmRowOnly := {
  val json = IO.read((LocalRootProject / baseDirectory).value / "target" / "zipx-affected.json").trim
  assert(json.contains("\"shared\""), s"a scalajvm change must affect the JVM row, got $json")
  assert(json.contains("\"consumer\""), s"a scalajvm change must reach the JVM row's dependent, got $json")
  assert(!json.contains("\"sharedJS\""), s"a scalajvm change must not affect the JS row, got $json")
}
