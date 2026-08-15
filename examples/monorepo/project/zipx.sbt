// The committed .github/workflows/ci.yml beside this build tracks the **in-dev** zipx in this repo, not the last
// release: CI Aggregate `test` publishes zipx locally and runs `zipxWorkflowCheck` here with
// -Dzipx.version=<in-dev>, so drift between the plugin and this example fails the PR that caused it.
//
// Without the property this falls back to the current release, so cloning the repo and running `sbt` in this directory
// still works. That path can generate YAML differing from the committed file whenever main is ahead of the release;
// CI is the source of truth. Locally, `sbt publishLocal` in the zipx root then
// `sbt -Dzipx.version=$(cat ../../target/zipx-version.txt)` here reproduces what CI does.
//
// Catalog plugins (native-packager, scalafmt) live in MyVersions and are written to plugins.sbt by generate.
// zipxEmitSelf is false so that generated file does not also emit a static sbt-zipx GAV.
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % sys.props.getOrElse("zipx.version", "0.1.6"))
