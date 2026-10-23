// sbt's project-id rule (`Character.isLetter`, then letters, digits, `-`, `_`) allows `café`, but a GitHub
// `jobs.<job_id>` key is ASCII, so GitHub would reject the generated workflow on push.
scalaVersion := "3.9.0"
version      := "1.0.0-SNAPSHOT"
zipxPins += Pin("sbt-image", "cs", "2.1.26")

lazy val café = project

lazy val root = (project in file("."))
  .aggregate(café)
  .settings(publish / skip := true)
