// The Native plugin injects its runtime at its own version. Resolution only: nothing here links.
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")

sys.props.get("plugin.version") match
  case Some(v) => addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % v)
  case _       => sys.error("plugin.version not set; pass it via scriptedLaunchOpts -Dplugin.version=...")
