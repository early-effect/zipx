package example

object Core:
  def greet(u: User): String = s"Hello, ${u.name} (#${u.id})"
