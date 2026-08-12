import Dependencies.*

ThisBuild / scalaVersion         := scala3Version
ThisBuild / organization         := "rocks.earlyeffect"
ThisBuild / organizationName     := "Early Effect"
ThisBuild / organizationHomepage := Some(url("https://www.earlyeffect.rocks"))
ThisBuild / versionScheme        := Some("early-semver")
// Version comes from sbt-dynver-ci (do not set ThisBuild / version).

ThisBuild / homepage := Some(url("https://github.com/early-effect/zipx"))
ThisBuild / licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scmInfo  := Some(
  ScmInfo(
    url("https://github.com/early-effect/zipx"),
    "scm:git@github.com:early-effect/zipx.git",
  )
)
ThisBuild / developers := List(
  Developer(
    id = "russwyte",
    name = "Russ White",
    email = "356303+russwyte@users.noreply.github.com",
    url = url("https://github.com/russwyte"),
  )
)

// Sonatype Central Portal. sbt 2 has localStaging / publishSigned / sonaRelease.
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// CI-only publishing: key hex from PGP_KEY_HEX (org secret). Sentinel keeps local loads working.
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  libraryDependencies ++= zioDeps,
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  publishMavenStyle    := true,
  pomIncludeRepository := { _ => false },
  // ZIOSpecDefault suites are discovered as mains; tests don't use mainClass. Suppress the warning.
  Test / mainClass := None,
)

// The version handoff for Aggregate test post-steps: examples/monorepo needs the in-dev plugin version, and CI must
// read it from a file rather than by capturing sbt stdout (which carries log lines). Same discipline as the plugin's
// own `zipxAffectedModules`, which writes target/zipx-affected.json for exactly this reason.
lazy val zipxWriteVersion = taskKey[File]("Write the build version to target/zipx-version.txt for the example check")

lazy val root = (project in file("."))
  .aggregate(shell, workflow, core, central, aws, plugin, docs, docsJS)
  .settings(
    name           := "zipx",
    publish / skip := true,
    // `Def.uncached` because a file write is not a valid cached-task output.
    zipxWriteVersion := Def.uncached {
      val out = (LocalRootProject / baseDirectory).value / zipx.ExampleCheck.VersionFile
      IO.write(out, (ThisBuild / version).value)
      streams.value.log.info(s"zipx version ${(ThisBuild / version).value} -> ${out.getPath}")
      out
    },
    // Dogfood: Aggregate Central + Pages, fork-gated so tag pushes on forks skip publish/docs.
    // (No ZipxGitHubPackages here yet: that needs dual publishTo when PUBLISH_GITHUB_PACKAGES=true.)
    zipxCapabilities ++= {
      val upstream = JobCondition.repositoryIs("early-effect/zipx")
      Seq(
        ZipxCentral.release.withCondition(upstream),
        // andCondition keeps ZipxDocs tag|dispatch filter and layers the fork gate
        ZipxDocs.pages().andCondition(upstream),
        // Override Aggregate `test` so consumer proofs share the verify job: unit/IT tests, then plugin scripted,
        // then publishLocal + examples/monorepo zipxWorkflowCheck (former consumer-verify job).
        // extraSteps: saferis-style pre-pull so Testcontainers does not hit Hub mid-suite (Ryuk stays on).
        Capability.once(
          name = Capability.TestName,
          command = zipxTasks.session(test, LocalProject("plugin") / scripted),
          phase = Phase.Verify,
          gate = Gate.Always,
          extraSteps = RemoteCacheItSteps.prePull,
          postSteps = zipx.ExampleCheck.steps,
        ),
      )
    },
    zipxJavaVersion      := JdkVersion("25"),
    zipxWorkflowDispatch := true,
    zipxDependabotSync   := true,
    zipxScalaSteward     := true,
  )

// Scala 3. Shell AST: no zipx concepts, no zio-blocks, usable standalone.
lazy val shell = (project in file("modules/shell"))
  .settings(commonSettings)
  .settings(
    name        := "zipx-shell",
    description := "Typed, composable shell script AST with compile-time-validated primitives",
    libraryDependencies ++= shellLibraryDeps,
  )

// Scala 3. GitHub Actions AST + deterministic YAML renderer.
lazy val workflow = (project in file("modules/workflow"))
  .dependsOn(shell)
  .settings(commonSettings)
  .settings(
    name        := "zipx-workflow",
    description := "GitHub Actions AST and deterministic YAML printer for zipx",
    libraryDependencies ++= workflowLibraryDeps,
  )

// Scala 3. Module-graph model, toposort, capabilities, and the planner.
lazy val core = (project in file("modules/core"))
  .dependsOn(workflow)
  .settings(commonSettings)
  .settings(
    name        := "zipx-core",
    description := "Pure planner: module graph, capabilities, EnvValue, ModuleGraph => Workflow",
    // Embed `.github/zipx/action-pins.yml` so ActionPins.Defaults matches dogfood / published pins.
    Compile / resourceGenerators += Def.task {
      val repo = (LocalRootProject / baseDirectory).value
      val src  = repo / ".github" / "zipx" / "action-pins.yml"
      val out  = (Compile / resourceManaged).value / "zipx" / "action-pins.yml"
      if (!src.exists) sys.error(s"Missing action pin file: ${src.getPath}")
      IO.copyFile(src, out)
      Seq(out)
    }.taskValue,
    // Live remote-cache proof (plain Testcontainers, saferis-style). Fixture sbt runs in an sbt
    // Docker image; host setup-sbt PATH is irrelevant. Docker is required when these tests run.
    // Leave Testcontainers Ryuk enabled (do not set TESTCONTAINERS_RYUK_DISABLED): cleans up containers
    // after aborted runs locally and is fine on GHA.
    libraryDependencies ++= testcontainersDeps,
  )

// Early-effect / Maven Central paved path (typed secrets + GPG import + publishSigned + sonaRelease).
lazy val central = (project in file("modules/central"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "zipx-central",
    description := "zipx capability pack for CI-only Maven Central publishing (early-effect org secrets)",
  )

// AWS paved path: OIDC role assumption, ECR registries that cannot omit their region, image tag sets.
lazy val aws = (project in file("modules/aws"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "zipx-aws",
    description := "zipx capability pack for AWS: OIDC login, ECR registries, image tags",
  )

// The sbt 2.x AutoPlugin, the only module that touches sbt.*. Publish + scripted live here;
// the root build dogfoods via the meta-build source mirror in project/dogfood.sbt (no publishLocal).
lazy val plugin = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core, central, aws)
  .settings(
    name        := "sbt-zipx",
    description := "sbt 2 AutoPlugin: the build describes its own GitHub Actions CI",
    scalacOptions ++= commonScalacOptions,
    publishMavenStyle    := true,
    pomIncludeRepository := { _ => false },
    // Bundle the remote-cache transport so consumers need one addSbtPlugin line. RemoteCachePlugin triggers on
    // AllRequirements but is a no-op until Global/remoteCache is set (which zipx does only from the CI env).
    addSbtPlugin(remoteCachePlugin),
    // sbt-pgp so ZipxCentral.release can take the real publishSigned TaskKey (Option C).
    addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1"),
    // JVM args for the sbt subprocess that runs scripted tests: suppress Unsafe/JNA warnings.
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024m",
      "--add-opens=java.base/sun.misc=ALL-UNNAMED",
      "--enable-native-access=ALL-UNNAMED",
      s"-Dplugin.version=${version.value}",
    ),
    scriptedBufferLog := false,
  )

// Docs-as-tests site (Specular + early-effect theme). Deployed via ZipxDocs.pages in generated CI.
lazy val specularPreview =
  taskKey[Unit]("Build specularSite then serve with sbt-reload (prefer alias: docsDev)")

/** Scala.js docs client: remounts `.interactive` ascent / mermoid examples after SSR. */
lazy val docsJS = project
  .in(file("docs-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name           := "zipx-docsJS",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions :+ "-language:implicitConversions",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / mainClass := Some("zipx.docs.ClientMain"),
    Compile / unmanagedSourceDirectories += (LocalProject("docs") / baseDirectory).value / "shared" / "scala",
    libraryDependencies ++= Seq(
      // sbt 2 + ScalaJSPlugin: `%%` already appends `_sjs1` (no `%%%`).
      // specular-site is JVM-only; the client needs core + mermoid + ascent-js.
      "rocks.earlyeffect" %% "specular-core"    % specularVersion,
      "rocks.earlyeffect" %% "specular-mermoid" % specularVersion,
      "rocks.earlyeffect" %% "ascent-js"        % ascentVersion,
      "rocks.earlyeffect" %% "ascent-css"       % ascentVersion,
      "dev.zio"           %% "zio"              % zioVersion,
      "dev.zio"           %% "zio-test"         % zioVersion,
    ),
  )

lazy val docs = project
  .in(file("docs"))
  .dependsOn(core, central, aws)
  .enablePlugins(SpecularPlugin)
  .settings(
    name            := "zipx-docs",
    publish / skip  := true,
    publishArtifact := false, // also honored; prefer publish/skip for opt-out
    scalacOptions ++= commonScalacOptions :+ "-language:implicitConversions",
    Test / unmanagedSourceDirectories += baseDirectory.value / "shared" / "scala",
    libraryDependencies ++= Seq(
      "rocks.earlyeffect" %% "specular-core"           % specularVersion % Test,
      "rocks.earlyeffect" %% "specular-zio-test"       % specularVersion % Test,
      "rocks.earlyeffect" %% "specular-site"           % specularVersion % Test,
      "rocks.earlyeffect" %% "specular-mermoid"        % specularVersion % Test,
      "rocks.earlyeffect" %% "early-effect-docs-theme" % specularVersion % Test,
      "rocks.earlyeffect" %% "ascent-core"             % ascentVersion   % Test,
      "rocks.earlyeffect" %% "ascent-html"             % ascentVersion   % Test,
    ) ++ zioDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / mainClass       := Some("specular.site.DocsServe"),
    Test / run / mainClass := (Test / mainClass).value,
    Test / runReloadArgs   := Seq(specularPort.value.toString),
    // runReload forks with the docs project as cwd, so relative target/site would miss the
    // repo-root site written by specularSite. Point DocsServe at specularSiteDirectory.
    Test / run / javaOptions ++= {
      val dir = specularSiteDirectory.value.getAbsolutePath
      Seq(
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
        s"-Dspecular.site.dir=$dir",
        s"-Dspecular.site.port=${specularPort.value}",
      )
    },
    specularBuildMain     := "zipx.docs.BuildSite",
    specularMetaProject   := Some(LocalProject("plugin")),
    specularArtifactKind  := "plugin",
    specularSiteDirectory := (ThisBuild / baseDirectory).value / "target" / "site",
    specularJsLink        := Def.uncached {
      (docsJS / Compile / fastLinkJS).value
      val outDir = (docsJS / Compile / fastLinkJSOutput).value
      val mainJs = outDir / "main.js"
      if (!mainJs.exists) then
      sys.error(
        s"Expected $mainJs after fastLinkJS; directory contains: " +
          Option(outDir.list).toSeq.flatten.mkString(", ")
      )
      val marker = (ThisBuild / baseDirectory).value / "target" / "specular-client-js.path"
      IO.write(marker, mainJs.getAbsolutePath)
    },
    // Docs-only (workflow_dispatch) builds are dynver `-ci`; don't advertise that as a Central coord.
    // Empty string → Specular uses build version (clean v* tags).
    specularDisplayVersion := {
      val v = (ThisBuild / version).value
      if (v.endsWith("-ci") || v.endsWith("-SNAPSHOT")) then {
        previousStableVersion.value.getOrElse("<version>")
      }
      else {
        ""
      }
    },
    // Rebuild site then (re)start DocsServe; use alias docsPreview for continuous watch.
    specularPreview := Def.uncached {
      specularSite.value
      (Test / runReload).value
    },
  )

addCommandAlias("docsPreview", "~docs/specularPreview")
addCommandAlias("docsDev", "docsPreview")
addCommandAlias("release", "; publishSigned; sonaRelease")
