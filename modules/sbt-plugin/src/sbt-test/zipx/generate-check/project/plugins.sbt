sys.props.get("plugin.version") match
  case Some(v) => addSbtPlugin("rocks.earlyeffect" % "zipx-sbt" % v)
  case _       => sys.error("plugin.version not set; pass it via scriptedLaunchOpts -Dplugin.version=...")
