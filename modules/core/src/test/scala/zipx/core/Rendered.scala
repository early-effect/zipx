package zipx.core

/** Unwraps a render result in a test that is not about failure.
  *
  * Every `Render` entry point, and every `zipx.core` renderer built on one, returns `Either[String, String]`: a
  * hand-built `Workflow` can hold a step GitHub Actions rejects, and reporting that as a value rather than an exception
  * is what lets the sbt plugin turn it into a readable build failure. Most tests render a plan they expect to be valid
  * and care only about the bytes, so they unwrap here. The tests that are *about* rejection assert on the `Left`.
  */
object Rendered:

  extension (result: Either[String, String])
    def yaml: String =
      result.fold(error => throw AssertionError(s"unexpected render failure: $error"), identity)
