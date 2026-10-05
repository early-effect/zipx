import ZipxVersions as V

V.settings
ThisBuild / organization         := "rocks.earlyeffect"
ThisBuild / organizationName     := "Early Effect"
ThisBuild / organizationHomepage := Some(uri("https://www.earlyeffect.rocks"))
ThisBuild / versionScheme        := Some("early-semver")

ThisBuild / homepage := Some(uri("https://github.com/early-effect/zipx"))
ThisBuild / licenses := Seq("Apache-2.0" -> uri("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scmInfo  := Some(
  ScmInfo(
    uri("https://github.com/early-effect/zipx"),
    "scm:git@github.com:early-effect/zipx.git",
  )
)
ThisBuild / developers := List(
  Developer(
    id = "russwyte",
    name = "Russ White",
    email = "356303+russwyte@users.noreply.github.com",
    url = uri("https://github.com/russwyte"),
  )
)

// CI-only publishing: key hex from PGP_KEY_HEX (org secret). Sentinel keeps local loads working.
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

val commonSettings = Seq(
  scalacOptions ++= V.commonScalacOptions,
  libraryDependencies ++= V.zioDeps,
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  publishMavenStyle    := true,
  pomIncludeRepository := { _ => false },
  // ZIOSpecDefault suites are discovered as mains; this suppresses that warning.
  Test / mainClass := None,
)

// examples/monorepo needs the in-dev plugin version; CI reads it from a file because sbt's stdout carries log lines.
lazy val zipxWriteVersion = taskKey[File]("Write the build version to target/zipx-version.txt for the example check")

lazy val root = (project in file("."))
  .aggregate(shell, workflow, core, syntax, cli, central, aws, plugin, docs, docsJS)
  .settings(
    name           := "zipx",
    publish / skip := true,
    // `Def.uncached` because a file write is not a valid cached-task output.
    zipxWriteVersion := Def.uncached {
      val out = (LocalRootProject / baseDirectory).value / zipx.ExampleCheck.VersionFile
      IO.write(out, (plugin / version).value)
      streams.value.log.info(s"zipx version ${(plugin / version).value} -> ${out.getPath}")
      out
    },
    zipxReleaseWorkflow := Some(ZipxCentral.releases),
    zipxDriftGate       := DriftGate.Fail,
    zipxCapabilities ++= {
      val upstream = JobCondition.repositoryIs("early-effect/zipx")
      Seq(
        ZipxCentral.snapshots.andCondition(upstream),
        ZipxCentral.pullRequestSnapshots("snapshots"),
        // andCondition keeps ZipxDocs tag|dispatch filter and layers the fork gate
        ZipxDocs.pages().andCondition(upstream),
        Capability
          .once(
            name = Capability.TestName,
            command = zipxTasks.of(test),
            phase = Phase.Verify,
            gate = Gate.Always,
            extraSteps = RemoteCacheItSteps.prePull,
            postSteps = zipx.ExampleCheck.steps,
          )
          // Replaces the builtin test by name, so it has to claim the LocalDir snapshot itself.
          .withLocalCache(LocalCacheMode.Save),
        // Its own job, parallel to `test`: in series the two suites are the whole critical path.
        zipxTasks.once(CapabilityName("scripted"), LocalProject("plugin") / scripted),
      )
    },
    zipxJavaVersion                := JdkVersion("25"),
    zipxWorkflowDispatch           := true,
    zipxEmitSelf                   := false,
    zipxVersionUpdatesPreSteps     := zipx.ExampleCheck.companionPreSteps,
    zipxVersionUpdatesExtraSteps   := zipx.ExampleCheck.companionSteps,
  )

// No zipx concepts and no zio-blocks, so it stands alone.
lazy val shell = (project in file("modules/shell"))
  .settings(commonSettings)
  .settings(
    name        := "zipx-shell",
    description := "Typed, composable shell script AST with compile-time-validated primitives",
    libraryDependencies ++= V.shellLibraryDeps,
  )

lazy val workflow = (project in file("modules/workflow"))
  .dependsOn(shell)
  .settings(commonSettings)
  .settings(
    name        := "zipx-workflow",
    description := "GitHub Actions AST and deterministic YAML printer for zipx",
    libraryDependencies ++= V.workflowLibraryDeps,
  )

lazy val core = (project in file("modules/core"))
  .dependsOn(workflow)
  .settings(commonSettings)
  .settings(
    name        := "zipx-core",
    description := "Pure planner: module graph, capabilities, EnvValue, ModuleGraph => Workflow",
    // ActionPins.Defaults come from the catalog's Action rows as a jar resource, not a committed pin file.
    Compile / resourceGenerators += Def.task {
      val out  = (Compile / resourceManaged).value / "zipx" / "action-pins.yml"
      val pins = zipx.core.ActionPins
        .overlay(zipx.core.ActionPins(), V.actions)
        .fold(err => sys.error(err), identity)
      IO.write(out, zipx.core.ActionPinFile.render(pins))
      Seq(out)
    }.taskValue,
    // The live remote-cache proof needs Docker; its fixture sbt runs in an sbt image, not the host's. Leave Ryuk
    // enabled: it cleans up containers after aborted local runs.
    libraryDependencies ++= V.testcontainersDeps ++ V.deps(V.zioJson),
  )

// The plugin depends on this so zipxWorkflowCheck parses plugins.sbt the way the CLI does.
lazy val syntax = (project in file("modules/syntax"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "zipx-syntax",
    description := "Scala 3 compiler trees for ZipxVersions.scala and generated plugins.sbt",
    libraryDependencies += "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
  )

// Published so the companion can `cs launch` it. No Typelevel.
lazy val cli = (project in file("modules/cli"))
  .dependsOn(syntax, core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "zipx-cli",
    description := "zipx catalog CLI: apply and generate without loading the target sbt session",
    Compile / mainClass := Some("zipx.cli.Main"),
  )

lazy val central = (project in file("modules/central"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "zipx-central",
    description := "zipx capability pack for CI-only Maven Central publishing (early-effect org secrets)",
  )

lazy val aws = (project in file("modules/aws"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "zipx-aws",
    description := "zipx capability pack for AWS: OIDC login, ECR registries, image tags",
  )

// The only module that touches sbt.*. The root build dogfoods it through project/dogfood.sbt's source mirror.
lazy val plugin = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core, syntax, central, aws)
  .settings(
    name        := "sbt-zipx",
    description := "sbt 2 AutoPlugin: the build describes its own GitHub Actions CI",
    scalacOptions ++= V.commonScalacOptions,
    publishMavenStyle    := true,
    pomIncludeRepository := { _ => false },
    libraryDependencies ++= V.zioDeps ++ V.deps(V.mimaCore, V.coursier),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / mainClass := None,
    // Bundle the remote-cache transport so consumers need one addSbtPlugin line. RemoteCachePlugin triggers on
    // AllRequirements but is a no-op until Global/remoteCache is set (which zipx does only from the CI env).
    addSbtPlugin(V.remoteCachePlugin),
    // sbt-pgp so ZipxCentral.release can take the real publishSigned TaskKey.
    addSbtPlugin(V.moduleID(V.pgp)),
    // JVM args for the sbt subprocess that runs scripted tests: suppress Unsafe/JNA warnings.
    scriptedLaunchOpts ++= Seq(
      "-Xmx1024m",
      // Every test boots a cold sbt, four at a time on a four-core runner, so JIT compilation competes with the tests
      // for CPU. C1 alone warms up fastest, and no scripted launch lives long enough to repay C2.
      "-XX:TieredStopAtLevel=1",
      "-XX:+IgnoreUnrecognizedVMOptions",
      "--add-opens=java.base/sun.misc=ALL-UNNAMED",
      "--sun-misc-unsafe-memory-access=allow",
      "--enable-native-access=ALL-UNNAMED",
      s"-Dplugin.version=${version.value}",
      // Scripted gives each launch its own sbt.global.base, and the launcher's boot directory defaults under it, so
      // every launch would fetch sbt and Scala into an empty directory. Share the running sbt's instead.
      s"-Dsbt.boot.directory=${appConfiguration.value.provider.scalaProvider.launcher.bootDirectory}",
    ),
    // Scripted's default leaves batch mode off here because it misreads this sbt's binary version. Without batch mode
    // every test gets a fresh JVM and scriptedParallelInstances is ignored. 4 matches a standard runner.
    scriptedBatchExecution    := true,
    scriptedParallelInstances := 4,
    // Buffered, because parallel instances interleave: a failing test prints its whole log in one piece.
    scriptedBufferLog := true,
  )

/** Scala.js docs client: remounts `.interactive` ascent examples after SSR. */
lazy val docsJS = project
  .in(file("docs-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name           := "zipx-docsJS",
    publish / skip := true,
    scalacOptions ++= V.commonScalacOptions :+ "-language:implicitConversions",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / mainClass := Some("zipx.docs.ClientMain"),
    Compile / unmanagedSourceDirectories += (LocalProject("docs") / baseDirectory).value / "shared" / "scala",
    libraryDependencies ++= V.deps(
      // Under ScalaJSPlugin, `%%` already appends the Scala.js suffix (no `%%%`).
      V.specular,
      V.specularMermoid,
      V.zio,
      V.zio.mod("zio-test"),
    ),
  )

lazy val docs = project
  .in(file("docs"))
  .dependsOn(core, central, aws)
  .enablePlugins(SpecularPlugin)
  .settings(
    name            := "zipx-docs",
    publish / skip  := true,
    publishArtifact := false,
    scalacOptions ++= V.commonScalacOptions :+ "-language:implicitConversions",
    Test / unmanagedSourceDirectories += baseDirectory.value / "shared" / "scala",
    libraryDependencies ++= V.deps(
      V.specular.test,
      V.specularZioTest,
      V.specularSite,
      V.specularMermoid.test,
      V.specularTheme,
    ) ++ V.zioDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    specularBuildMain      := "zipx.docs.BuildSite",
    specularMetaProject    := Some(LocalProject("plugin")),
    specularArtifactKind   := "plugin",
    specularSiteDirectory  := (ThisBuild / baseDirectory).value / "target" / "site",
    specularJsProject      := Some(LocalProject("docsJS")),
    specularJsLink         := Def.uncached {
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
    specularJsLinkDev      := specularJsLink.value,
    specularDisplayVersion := (_.stripSuffix("-SNAPSHOT").stripSuffix("-ci")),
  )

// Plugin task. It watches on its own. Do not prefix ~.
addCommandAlias("docsDev", "docs/specularPreview")
addCommandAlias("docsPreview", "docs/specularPreview")
