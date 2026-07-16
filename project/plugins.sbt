// Dogfood: zipx generates its own CI workflow. Uses the locally-published plugin.
// After changing core/workflow/plugin sources, run `sbt publishLocal` before reload to refresh.
addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % "0.1.0-SNAPSHOT")
