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
)

lazy val root = (project in file("."))
  .aggregate(workflow, core, central, plugin, docs)
  .settings(
    name           := "zipx",
    publish / skip := true,
    // Dogfood: Aggregate Central release (one job) + Specular Pages.
    zipxCapabilities ++= Seq(ZipxCentral.release, ZipxDocs.pages()),
    zipxJavaVersion      := "25",
    zipxWorkflowDispatch := true,
    zipxDependabotSync   := true,
    zipxScalaSteward     := true,
  )

// Scala 3. GitHub Actions AST + deterministic YAML renderer.
lazy val workflow = (project in file("modules/workflow"))
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
  )

// Early-effect / Maven Central paved path (typed secrets + GPG import + publishSigned + sonaRelease).
lazy val central = (project in file("modules/central"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name        := "zipx-central",
    description := "zipx capability pack for CI-only Maven Central publishing (early-effect org secrets)",
  )

// The sbt 2.x AutoPlugin — the only module that touches sbt.*. Publish + scripted live here;
// the root build dogfoods via the meta-build source mirror in project/dogfood.sbt (no publishLocal).
lazy val plugin = (project in file("modules/sbt-plugin"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core, central)
  .settings(
    name        := "zipx-sbt",
    description := "sbt 2 AutoPlugin: the build describes its own GitHub Actions CI",
    scalacOptions ++= commonScalacOptions,
    publishMavenStyle    := true,
    pomIncludeRepository := { _ => false },
    // Bundle the remote-cache transport so consumers need one addSbtPlugin line — RemoteCachePlugin triggers on
    // AllRequirements but is a no-op until Global/remoteCache is set (which zipx does only from the CI env).
    addSbtPlugin(remoteCachePlugin),
    libraryDependencySchemes ++= pluginLibraryDependencySchemes,
    scriptedLaunchOpts ++= Seq("-Xmx1024m", s"-Dplugin.version=${version.value}"),
    scriptedBufferLog := false,
  )

// Docs-as-tests site (Specular + early-effect theme). Deployed via ZipxDocs.pages in generated CI.
lazy val docs = project
  .in(file("docs"))
  .dependsOn(core, central)
  .enablePlugins(SpecularPlugin)
  .settings(
    name            := "zipx-docs",
    publish / skip  := true,
    publishArtifact := false, // zipx derives publish jobs from publishArtifact
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "rocks.earlyeffect" %% "specular-core"           % specularVersion % Test,
      "rocks.earlyeffect" %% "specular-zio-test"       % specularVersion % Test,
      "rocks.earlyeffect" %% "specular-site"           % specularVersion % Test,
      "rocks.earlyeffect" %% "early-effect-docs-theme" % specularVersion % Test,
    ) ++ zioDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / mainClass      := Some("specular.site.DocsServe"),
    specularBuildMain     := "zipx.docs.BuildSite",
    specularMetaProject   := Some(LocalProject("plugin")),
    specularArtifactKind  := "plugin",
    specularSiteDirectory := (ThisBuild / baseDirectory).value / "target" / "site",
  )

addCommandAlias("release", "; publishSigned; sonaRelease")
