package zipx.core

import zio.test.*

object LibraryVersionSchemeSpec extends ZIOSpecDefault:

  def spec = suite("LibraryVersionScheme")(
    test("early-semver is the default, and sbt's five schemes parse") {
      assertTrue(
        LibraryVersionScheme.Default == LibraryVersionScheme.EarlySemVer,
        LibraryVersionScheme.parse("early-semver") == Right(LibraryVersionScheme.EarlySemVer),
        LibraryVersionScheme.parse("SEMVER-SPEC") == Right(LibraryVersionScheme.SemVerSpec),
        LibraryVersionScheme.parse("pvp") == Right(LibraryVersionScheme.Pvp),
        LibraryVersionScheme.parse("strict") == Right(LibraryVersionScheme.Strict),
        LibraryVersionScheme.parse("always") == Right(LibraryVersionScheme.Always),
      )
    },
    test("an empty scheme, the ambiguous word semver, and an unknown word fail") {
      val missing   = LibraryVersionScheme.parse("  ")
      val ambiguous = LibraryVersionScheme.parse("semver")
      val unknown   = LibraryVersionScheme.parse("calendar")
      val named     = missing match
        case Left(err) => err.message.contains("early-semver") && err.message.contains("pvp")
        case Right(_)  => false
      assertTrue(
        missing == Left(LibraryVersionSchemeError.Missing),
        named,
        ambiguous == Left(LibraryVersionSchemeError.AmbiguousSemver),
        unknown == Left(LibraryVersionSchemeError.Unknown("calendar")),
      )
    },
  )
end LibraryVersionSchemeSpec
