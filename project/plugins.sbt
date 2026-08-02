// Dogfood: zipx is loaded from source via project/dogfood.sbt (meta-* mirrors).
// After changing modules/{workflow,core,central,sbt-plugin} sources used by the plugin: `reload`.
// Versions / library deps: project/Dependencies.scala (shared with build.sbt via project/project/build.sbt).

addSbtPlugin("rocks.earlyeffect" % "sbt-dynver-ci" % "0.2.2")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"  % "2.6.2")
addSbtPlugin("com.github.sbt"    % "sbt-pgp"       % "2.3.1")
addSbtPlugin("rocks.earlyeffect" % "sbt-specular"  % "0.11.0")
addSbtPlugin("com.jamesward"     % "sbt-reload"    % "0.0.7")
