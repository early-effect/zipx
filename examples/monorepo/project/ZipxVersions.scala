import zipx.*

/** Typed catalog: inbound `Lib` / `Plugin` rows plus outbound `Ship` / `ShipGroup` rows.
  *
  * sbt-zipx itself is not a row: CI injects the in-dev plugin via `-Dzipx.version` in `project/zipx.sbt`.
  *
  * `libs` locksteps `models` and `coreLib`. `client` ships on its own number. `service` is unpublished, so it has no
  * row. `zipxDepUpdate` rewrites `Lib` / `Plugin` only.
  */
object MyVersions extends ZipxVersions:

  val sbt: SbtVersion      = SbtVersion("2.0.8")
  val scala: ScalaVersion  = ScalaVersion("3.8.4")
  val scala2: ScalaVersion = ScalaVersion("2.13.16")

  override def crossScala: Seq[ScalaVersion] = Seq(scala2, scala)

  val zio   = Lib("dev.zio", "zio", "2.1.26")
  val slf4j = Lib("org.slf4j", "slf4j-simple", "2.0.18").java

  val nativePackager = Plugin("com.github.sbt", "sbt-native-packager", "1.11.7")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")

  val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
  val client = Ship("client", "0.3.0")

  def libraries = library(zio)
  def service   = library(zio, slf4j)
end MyVersions
