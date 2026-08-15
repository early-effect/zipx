scalaVersion   := "3.8.4"
version        := "1.0.0-ci"
zipxCacheEpoch := CacheEpoch.Fixed("1.0.0-ci")

zipxVersions  := Seq(Lib("dev.zio", "zio", "2.1.26"))
zipxCheckDeps := true
libraryDependencies += "org.slf4j" % "slf4j-simple" % "2.0.18"

lazy val root = (project in file("."))
  .settings(publish / skip := true)
