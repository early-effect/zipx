// A build sbt loads happily but GitHub cannot name. sbt's project-id rule is `Character.isLetter` followed by
// `isLetterOrDigit || '-' || '_'`, so `café` is a legal project id; a GitHub `jobs.<job_id>` key is ASCII, so the
// workflow zipx would generate for it is one GitHub rejects on push.
//
// zipx therefore refuses at the boundary, naming the project. It used to get all the way into planning before throwing,
// which reported the symptom rather than the cause.
scalaVersion := "3.8.4"
version      := "1.0.0-ci"

lazy val café = project

lazy val root = (project in file("."))
  .aggregate(café)
  .settings(publish / skip := true)
