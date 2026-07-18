package example

object Client:
  def describe(u: User): String = Core.greet(u)
