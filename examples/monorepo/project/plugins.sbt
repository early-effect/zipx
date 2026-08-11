// The committed .github/workflows/ci.yml beside this build tracks the **in-dev** zipx in this repo, not the last
// release: CI Aggregate `test` publishes zipx locally and runs `zipxWorkflowCheck` here with
// -Dzipx.version=<in-dev>, so drift between the plugin and this example fails the PR that caused it.
//
// Without the property this falls back to the current release, so cloning the repo and running `sbt` in this directory
// still works. That path can generate YAML differing from the committed file whenever main is ahead of the release;
// CI is the source of truth. Locally, `sbt publishLocal` in the zipx root then
// `sbt -Dzipx.version=$(cat ../../target/zipx-version.txt)` here reproduces what CI does.
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % sys.props.getOrElse("zipx.version", "0.1.6"))

// The paved path for images: the build describes its own docker image (base, entrypoint, ports)
// via sbt-native-packager, instead of an external Dockerfile + a hand-written `docker build` string.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")

// Formatting, so the `fmt` gate uses the real, typed `scalafmtCheckAll` task key.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
