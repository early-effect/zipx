// Consumes the released zipx plugin. To try unreleased changes, run `sbt publishLocal` in the
// zipx root and swap in the version it prints (dynver-ci prints `<last-tag>-ci`).
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.0.10")

// The paved path for images: the build describes its own docker image (base, entrypoint, ports)
// via sbt-native-packager, instead of an external Dockerfile + a hand-written `docker build` string.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

// Formatting, so the `fmt` gate uses the real, typed `scalafmtCheckAll` task key.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
