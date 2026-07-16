// Consumes the locally-published zipx plugin. Run `sbt publishLocal` in the zipx root first.
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "0.1.0-SNAPSHOT")

// The paved path for images: the build describes its own docker image (base, entrypoint, ports)
// via sbt-native-packager, instead of an external Dockerfile + a hand-written `docker build` string.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
