import zipx.*

/** Typed catalog: library and plugin versions this example may use. `zipxDepUpdate` rewrites constructors here.
  *
  * sbt-zipx itself is not a row: CI injects the in-dev plugin via `-Dzipx.version` in `project/zipx.sbt`.
  *
  * Every `Lib` / `Plugin` val is a catalog row. Each module selects a group (`libraries`, `client`, `service`); unused
  * rows stay legal.
  */
object MyVersions extends ZipxVersions:

  val sbt: SbtVersion      = SbtVersion("2.0.6")
  val scala: ScalaVersion  = ScalaVersion("3.8.4")
  val scala2: ScalaVersion = ScalaVersion("2.13.16")

  override def crossScala: Seq[ScalaVersion] = Seq(scala2, scala)

  val zio   = Lib("dev.zio", "zio", "2.1.26")
  val slf4j = Lib("org.slf4j", "slf4j-simple", "2.0.18").java

  val nativePackager = Plugin("com.github.sbt", "sbt-native-packager", "1.11.7")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")

  def libraries = library(zio)
  def client    = library(zio)
  def service   = library(zio, slf4j)
end MyVersions
