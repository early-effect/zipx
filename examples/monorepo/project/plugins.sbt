// Consumes the locally-published zipx plugin. Run `sbt publishLocal` in the zipx root first
// (use the version publishLocal prints, e.g. dynver `0.0.3-ci`).
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "0.0.3-ci")

// The paved path for images: the build describes its own docker image (base, entrypoint, ports)
// via sbt-native-packager, instead of an external Dockerfile + a hand-written `docker build` string.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

// Formatting — so the `fmt` gate uses the real, typed `scalafmtCheckAll` task key.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
