package zipx.core

/** Unwraps a render result in a test that is not about failure. */
object Rendered:

  extension (result: Either[String, String])
    def yaml: String =
      result.fold(error => throw AssertionError(s"unexpected render failure: $error"), identity)
