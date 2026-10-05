package ascent.dom

object Facade:
  def render(tag: String): String = s"<$tag>"
  def mount(tag: String): String  = s"mount ${render(tag)}"
