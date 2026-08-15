import zipx.core.*

object ZipxVersions:
  val sbt: SbtVersion      = SbtVersion("2.0.6")
  val scala3: ScalaVersion = ScalaVersion("3.8.4")
  val zio                  = Lib("dev.zio", "zio", "2.1.26")
  val scalafmt             = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  def coords: Seq[ZipxCoord] = List(zio, scalafmt)
end ZipxVersions
