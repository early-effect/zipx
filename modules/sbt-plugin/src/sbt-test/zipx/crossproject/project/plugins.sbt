// sbt 2's `projectMatrix` reflectively enables `ScalaJSPlugin` for a `jsPlatform` row, so sbt-scalajs
// must be on the meta classpath even though this fixture never links any JS.
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")

sys.props.get("plugin.version") match
  case Some(v) => addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % v)
  case _       => sys.error("plugin.version not set; pass it via scriptedLaunchOpts -Dplugin.version=...")
