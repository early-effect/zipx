import zipx.*

object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.8")
  val scala: ScalaVersion = ScalaVersion("3.8.4")
  val zio                 = Lib("dev.zio", "zio", "2.1.26")
  val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
  val client = Ship("client", "0.3.0")

  def libraries = library(zio)
end MyVersions
