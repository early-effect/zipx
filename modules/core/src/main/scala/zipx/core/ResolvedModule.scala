package zipx.core

/** A module as resolution names it: the group and the crossed artifact, platform and Scala suffixes included. */
final case class ResolvedModule(group: String, name: String):
  def render: String = s"$group:$name"
