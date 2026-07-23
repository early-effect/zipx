// Dogfood: zipx is loaded from source via project/dogfood.sbt (meta-* mirrors).
// After changing modules/{workflow,core,central,sbt-plugin} sources used by the plugin: `reload`.
// Versions / library deps: project/Dependencies.scala (shared with build.sbt).

addSbtPlugin("rocks.earlyeffect" % "sbt-dynver-ci" % "0.2.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"  % "2.6.2")
addSbtPlugin("com.github.sbt"    % "sbt-pgp"       % "2.3.1")
addSbtPlugin("rocks.earlyeffect" % "sbt-specular"  % "0.6.0")
addSbtPlugin("com.jamesward"     % "sbt-reload"    % "0.0.7")

// zipx bundles sbt-remote-cache; compiler-interface is versioned on both the sbt-2.x and zinc-1.x schemes.
libraryDependencySchemes ++= Dependencies.pluginLibraryDependencySchemes
