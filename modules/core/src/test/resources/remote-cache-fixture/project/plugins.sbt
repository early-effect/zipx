// Track sbt.version. sbt-remote-cache 2.0.x only speaks gRPC and rejects http://.
addSbtPlugin(
  ("org.scala-sbt" % "sbt-remote-cache" % "2.1.0-RC1").excludeAll(ExclusionRule(organization = "org.scala-sbt"))
)
