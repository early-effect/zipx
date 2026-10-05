import sbt.*

/** ModuleIDs for the meta-build dogfood mirror, zipx-free because project/ .sbt files cannot import zipx types.
  *
  * Versions must match project/ZipxVersions.scala: zipxDepUpdate rewrites only the catalog, so copy moved literals.
  */
object Dependencies:

  val scala3Version      = "3.9.0"
  val zioVersion         = "2.1.26"
  val zioJsonVersion     = "1.1.0"
  val zioBlocksVersion   = "0.0.51"
  val remoteCacheVersion = "2.1.0-M3"
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
  val coursier: ModuleID = ("io.get-coursier" %% "coursier" % "2.1.26")
    .exclude("org.codehaus.plexus" % "plexus-archiver")
    .exclude("org.codehaus.plexus" % "plexus-container-default")

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

  /** Bundled so consumers need one `addSbtPlugin` line. Its POM lists `sbt` itself as a compile dependency, whose
    * `compiler-interface` collides with other plugins' zinc in a consumer meta-build; the host sbt provides that stack.
    */
  val remoteCachePlugin: ModuleID =
    ("org.scala-sbt" % "sbt-remote-cache" % remoteCacheVersion)
      .excludeAll(ExclusionRule(organization = "org.scala-sbt"))

end Dependencies
