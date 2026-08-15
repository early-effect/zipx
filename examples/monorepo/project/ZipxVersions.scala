import zipx.core.*

/** Typed catalog: library and plugin versions this example may use. `zipxDepUpdate` rewrites constructors here.
  *
  * sbt-zipx itself is not a row: CI injects the in-dev plugin via `-Dzipx.version` in `project/zipx.sbt`.
  */
object ZipxVersions:

  val sbt: SbtVersion      = SbtVersion("2.0.6")
  val scala3: ScalaVersion = ScalaVersion("3.8.4")
  val scala2: ScalaVersion = ScalaVersion("2.13.16")

  val nativePackager = Plugin("com.github.sbt", "sbt-native-packager", "1.11.7")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")

  def coords: Seq[ZipxCoord] = List(nativePackager, scalafmt)
end ZipxVersions
