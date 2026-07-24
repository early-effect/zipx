ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "zipx.it.fixture"

lazy val root = (project in file("."))
  .settings(
    name := "remote-cache-fixture",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test,
  )
