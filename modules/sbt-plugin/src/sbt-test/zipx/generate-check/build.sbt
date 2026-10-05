// Bare settings are common to every module and overridable per module, so no `ThisBuild /`.
scalaVersion := "3.9.0"
version      := "1.0.0-SNAPSHOT"
// Fixed so the asserts can name a literal epoch.
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-SNAPSHOT")
zipxVerify     := ZipxVerify.Strict.copy(fmt = VerifyOpt.Skip("scripted fixture has no sbt-scalafmt"))
// The asserts name per-module jobs, so collapse is off; core's MatrixCollapseSpec covers Auto.
zipxMatrixCollapse := Map(
  Capability.TestName                 -> MatrixCollapse.Off,
  Capability.PublishName              -> MatrixCollapse.Off,
  CapabilityName("compileCheck")      -> MatrixCollapse.Off,
  CapabilityName("crossPublishCheck") -> MatrixCollapse.Off,
  CapabilityName("mixedCheck")        -> MatrixCollapse.Off,
)
zipxTestTask := zipxTasks.of(testFull)

lazy val schema = project
  .settings(crossScalaVersions := Seq("2.13.16", "3.9.0"))

lazy val api = project
  .dependsOn(schema)
  .settings(crossScalaVersions := Seq("2.13.16", "3.9.0"))

lazy val client = project
  .dependsOn(api)
  .settings(
    crossScalaVersions := Seq("2.13.16", "3.9.0"),
    zipxTestTask       := zipxTasks.of(test),
  )

lazy val service = project
  .dependsOn(api)
  .settings(
    // zipx honours sbt's publish / skip, so no zipxPublish is needed.
    publish / skip := true
  )

lazy val root = (project in file("."))
  .aggregate(schema, api, client, service)
  .settings(publish / skip := true)

// A typed key through zipxTasks.once renders its bare `<label>` command.
val lintAll = taskKey[Unit]("a build-wide lint gate")
lintAll := ()
zipxCapabilities += zipxTasks.once(CapabilityName("lint"), lintAll)

// A config-scoped typed key renders `<module>/Compile/compile`.
zipxCapabilities += zipxTasks.custom(
  name = CapabilityName("compileCheck"),
  command = Compile / compile,
  participates = _.id == "schema",
  gate = Gate.Always,
)

// cmd"…" yields a `ModuleNode => SbtCommand`, so it goes to the core `Capability.custom`.
zipxCapabilities += Capability.custom(
  name = CapabilityName("crossPublishCheck"),
  command = cmd"+ ${publish}",
  participates = _.id == "schema",
  gate = Gate.Always,
)

// A String splice and a key splice in one command.
val scalaSwitch = "2.13.16"
zipxCapabilities += Capability.custom(
  name = CapabilityName("mixedCheck"),
  command = cmd"++${scalaSwitch}; ${publish}",
  participates = _.id == "api",
  gate = Gate.Always,
)

// Graph-mode test and publish; publish carries typed secrets rather than raw `${{ secrets.X }}` strings.
zipxCapabilities ++= Seq(
  Capability.testGraph,
  Capability.publishGraph
    .withEnv(
      Map(
        "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
        "SONATYPE_USERNAME" -> Secret("SONATYPE_USERNAME"),
      )
    ),
)

val assertGraph = taskKey[Unit]("assert the graph and generated workflow are correct")
assertGraph := {
  val wf      = (LocalRootProject / baseDirectory).value / ".github" / "workflows" / "ci.yml"
  val content = IO.read(wf)
  assert(content.contains("fmt:"), "missing fmt verify job")
  assert(content.contains("zipx: skipping fmt:"), "Skip fmt should still emit the job")
  assert(content.contains("workflow-check:"), "missing workflow-check job")
  assert(content.contains("advisories:"), "missing advisories job")
  assert(
    content.contains("zipxWorkflowCheck") || content.contains("zipxWorkflowCheck'"),
    "workflow-check should run zipxWorkflowCheck",
  )
  assert(
    content.contains("zipxAdvisoryCheck") || content.contains("zipxAdvisoryCheck'"),
    "advisories should run zipxAdvisoryCheck",
  )
  assert(content.contains("test-schema:"), "missing test-schema job")
  assert(content.contains("test-service:"), "missing test-service job")
  assert(!content.contains("test-root:"), "the aggregating root must not get a test job")
  assert(content.contains("publish-schema:"), "missing publish-schema job")
  assert(!content.contains("publish-service:"), "service must not have a publish job")
  assert(!content.contains("publish-root:"), "aggregating root must not have a publish job")
  assert(content.contains("- test-schema"), "test-api should need test-schema")
  assert(content.contains("- publish-schema"), "publish-api should need publish-schema")
  // zio-blocks quotes version-like scalars.
  assert(content.contains("\"2.13.16\""), "expected scala matrix entry")
  assert(content.contains("service/testFull"), "service should inherit the build-wide testFull task")
  assert(content.contains("schema/testFull"), "schema should inherit the build-wide testFull task")
  assert(content.contains("client/test'"), "client should override back to plain test")
  assert(!content.contains("client/testFull"), "client must NOT use the inherited testFull")
  assert(content.contains("uses: ./.github/actions/zipx-sbt-setup"), "expected zipx-sbt-setup composite")
  assert(content.contains("cache-key-suffix: test-schema"), "cache-key-suffix should be the job id")
  assert(content.contains("cache-epoch: \"1.0.0-SNAPSHOT\""), "Fixed epoch should be passed into the composite")
  assert(content.contains("cache-mode: restore"), "Graph test jobs must restore the LocalDir snapshot, never save it")
  assert(
    content.split("cache-mode: save", -1).length - 1 == 1,
    "with Graph test replacing the builtin test, cache-rehydrate must be the only job that saves",
  )
  assert(
    content.contains("contains(github.event.pull_request.labels.*.name, 'purge')"),
    "the purge label should gate LocalDir restore",
  )
  assert(content.contains("sbt-disk-cache: \"false\""), "LocalDir must disable setup-sbt hashFiles disk-cache")
  assert(!content.contains("cache: sbt"), "LocalDir must not enable setup-java cache:sbt")
  val setup =
    IO.read((LocalRootProject / baseDirectory).value / ".github" / "actions" / "zipx-sbt-setup" / "action.yml")
  assert(
    setup.contains(
      "key: ${{ inputs.runner-os }}-jdk${{ inputs.java-version }}-sbt-${{ inputs.cache-epoch }}-build-${{ github.run_id }}-${{ inputs.cache-key-suffix }}"
    ),
    "composite cache key should embed epoch + build role + run_id + job suffix",
  )
  assert(setup.contains("target"), "cache path should include target/ for compile + sona-staging reuse")
  assert(setup.contains("inputs.purge == 'true'"), "a purged save must be its own cache step")
  assert(setup.contains("inputs.purge != 'true'"), "an ordinary restore stays when purge is false")
  assert(setup.contains("name: Save sbt cache"), "the cold save step must exist")
  val awsLogin = (LocalRootProject / baseDirectory).value / ".github" / "actions" / "zipx-aws-login"
  assert(!awsLogin.exists, "non-AWS consumer must not get zipx-aws-login")
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
  assert(content.contains("startsWith(github.ref, 'refs/tags/v')"), "publish jobs should gate on a release tag")
  assert(!content.contains("++${{ matrix.scala }} +"), "publish must not combine matrix leg with +publish")
  assert(!content.contains("docker-"), "docker stage must be absent when no module opts in")
  assert(content.contains("lint:"), "typed once-capability should emit a build-wide `lint` job")
  assert(content.contains("sbt 'lintAll'"), "typed key should render to its bare label command")
  assert(content.contains("sbt 'schema/Compile/compile'"), "typed config-scoped key should render its config axis")
  assert(content.contains("sbt '+ schema/publish'"), "cmd interpolator should emit literal syntax + module-scoped key")
  assert(content.contains("sbt '++2.13.16; api/publish'"), "cmd should mix a String splice with a module-scoped key")
  assert(
    content.contains("PGP_PASSPHRASE: ${{ secrets.PGP_PASSPHRASE }}"),
    "typed secret should render into publish env",
  )
  assert(content.contains("SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}"), "Secret() helper should render")
}

// `[]` would gate every Verify job out while the PR reports green. Scripted's temp dir is not a git repo, so no diff
// can run here.
val assertAffectedFailsOpen = taskKey[Unit]("a diff that cannot run emits the all-sentinel")
assertAffectedFailsOpen := {
  val json = IO.read((LocalRootProject / baseDirectory).value / "target" / "zipx-affected.json").trim
  assert(json == """["all"]""", s"""expected ["all"] when the diff fails, got $json""")
}

val assertAffectedEmptyStaysEmpty = taskKey[Unit]("a successful empty diff stays empty")
assertAffectedEmptyStaysEmpty := {
  val json = IO.read((LocalRootProject / baseDirectory).value / "target" / "zipx-affected.json").trim
  assert(json == "[]", s"expected [] for a successful diff with no changes, got $json")
}
