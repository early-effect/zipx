package zipx.core

/** A module as resolution names it: the group and the crossed artifact (`heddle-mcp-apps_sjs1_3`). */
final case class ResolvedModule(group: String, name: String):
  def render: String = s"$group:$name"
