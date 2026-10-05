package zipx.core

object Rendered:

  extension (result: Either[String, String])
    def yaml: String =
      result.fold(error => throw AssertionError(s"unexpected render failure: $error"), identity)
