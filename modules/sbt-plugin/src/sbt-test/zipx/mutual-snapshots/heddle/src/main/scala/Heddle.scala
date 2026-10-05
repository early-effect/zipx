package heddle

object Heddle:
  def respond(body: String): String = ascent.dom.Facade.render(body)
