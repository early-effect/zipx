import sbt.*

/** One home for every dep shared by build.sbt and the meta-build dogfood mirror, so the two cannot drift.
  *
  * The `.sbt` files in `project/` sit one level above the `.scala` files there, so dogfood cannot import this object by
  * default; `project/project/build.sbt` adds this file to its `unmanagedSources` instead. Docs-only deps stay in
  * build.sbt.
  */
object Dependencies:

  val scala3Version      = "3.8.4"
  val zioVersion         = "2.1.26"
  val specularVersion    = "0.12.0"
  val zioBlocksVersion   = "0.0.51"
  val remoteCacheVersion = "2.0.5"
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

  val workflowLibraryDeps: Seq[ModuleID] = Seq(
    "dev.zio" %% "zio-blocks-schema"      % zioBlocksVersion,
    "dev.zio" %% "zio-blocks-schema-yaml" % zioBlocksVersion,
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

  val testcontainersVersion = "2.0.5"

  val testcontainersDeps: Seq[ModuleID] = Seq(
    "org.testcontainers" % "testcontainers" % testcontainersVersion % Test
  )

end Dependencies
