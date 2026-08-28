import sbt.*

/** ModuleIDs for the meta-build dogfood mirror. The .sbt files in project/ cannot import zipx types (Lib / Plugin), so
  * this file stays zipx-free and is pulled onto that classpath via project/project/build.sbt unmanagedSources.
  *
  * Version literals here must match project/ZipxVersions.scala. zipxDepUpdate rewrites the catalog; copy those literals
  * here when a dogfood dep moves. Docs-only deps stay in build.sbt.
  */
object Dependencies:

  val scala3Version      = "3.8.4"
  val zioVersion         = "2.1.26"
  val zioJsonVersion     = "0.10.0"
  val zioBlocksVersion   = "0.0.51"
  val remoteCacheVersion = "2.0.8"
  val neotypeVersion     = "0.7.0"

  val commonScalacOptions: Seq[String] = Seq(
    "-deprecation",
    "-feature",
    "-Wunused:all",
  )

  val zioDeps: Seq[ModuleID] = Seq(
    "dev.zio" %% "zio"          % zioVersion,
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  )

  val zioJson: ModuleID  = "dev.zio"      %% "zio-json"  % zioJsonVersion
  val mimaCore: ModuleID = "com.typesafe" %% "mima-core" % "1.1.5"

  val workflowLibraryDeps: Seq[ModuleID] = Seq(
    "dev.zio" %% "zio-blocks-schema"      % zioBlocksVersion,
    "dev.zio" %% "zio-blocks-schema-yaml" % zioBlocksVersion,
  )

  val scala3Compiler: Seq[ModuleID] = Seq(
    "org.scala-lang" %% "scala3-compiler" % scala3Version
  )

  // neotype's compile scope is light enough to put on a consumer's meta-build classpath: its own jar, comptime, and
  // scala3-library. Its zio-test integration is test-only.
  val shellLibraryDeps: Seq[ModuleID] = Seq(
    "io.github.kitlangton" %% "neotype" % neotypeVersion
  )

  /** Bundled so consumers need one `addSbtPlugin` line for zipx.
    *
    * The `org.scala-sbt` transitives are dropped because the published POM re-lists `sbt` itself as a compile
    * dependency, which drags `compiler-interface` into every consumer meta-build and collides with zinc 1.x schemas
    * from other plugins. Host sbt already provides that stack.
    */
  val remoteCachePlugin: ModuleID =
    ("org.scala-sbt" % "sbt-remote-cache" % remoteCacheVersion)
      .excludeAll(ExclusionRule(organization = "org.scala-sbt"))

end Dependencies
