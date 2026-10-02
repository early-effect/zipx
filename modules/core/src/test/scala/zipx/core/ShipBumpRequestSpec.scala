package zipx.core

import zio.test.*

object ShipBumpRequestSpec extends ZIOSpecDefault:

  private val ids = Set("client", "libs", "patch")

  def spec = suite("ShipBumpRequest")(
    test("no tokens opens every released row at patch") {
      assertTrue(ShipBumpRequest.parse(Nil, ids) == Right(ShipBumpRequest.Released(ReleaseBump.Patch)))
    },
    test("a kind word opens every released row at that kind") {
      assertTrue(
        ShipBumpRequest.parse(List("minor"), ids) == Right(ShipBumpRequest.Released(ReleaseBump.Minor)),
        ShipBumpRequest.parse(List("MAJOR"), Set("client")) == Right(ShipBumpRequest.Released(ReleaseBump.Major)),
      )
    },
    test("a ship id is that row at patch, and a second token is the kind") {
      assertTrue(
        ShipBumpRequest.parse(List("client"), ids) == Right(ShipBumpRequest.One("client", ReleaseBump.Patch)),
        ShipBumpRequest.parse(List("client", "minor"), ids) == Right(
          ShipBumpRequest.One("client", ReleaseBump.Minor)
        ),
      )
    },
    test("a ship whose name is a kind word wins the single token") {
      check(Gen.elements("patch", "minor", "major")) { name =>
        assertTrue(
          ShipBumpRequest.parse(List(name), Set(name, "client")) == Right(
            ShipBumpRequest.One(name, ReleaseBump.Patch)
          ),
          ShipBumpRequest.parse(List(name, "minor"), Set(name)) == Right(ShipBumpRequest.One(name, ReleaseBump.Minor)),
        )
      }
    },
    test("an unknown ship or kind is a typed error, and a third token is refused") {
      val unknown = ShipBumpRequest.parse(List("nope"), ids)
      val kind    = ShipBumpRequest.parse(List("client", "micro"), ids)
      val extra   = ShipBumpRequest.parse(List("client", "minor", "now"), ids)
      val named   = unknown match
        case Left(err) => err.message.contains("client") && err.message.contains("libs")
        case Right(_)  => false
      assertTrue(
        unknown == Left(ShipBumpError.UnknownIdentity("nope", ids.toList)),
        named,
        kind == Left(ShipBumpError.UnknownKind("micro")),
        extra == Left(ShipBumpError.Extra(List("client", "minor", "now"))),
      )
    },
    test("patch moves the third component, including a zero major") {
      val version = ReleaseVersion.unsafeMake("0.3.0")
      assertTrue(
        ReleaseBump.Patch.next(version) == ReleaseVersion.unsafeMake("0.3.1"),
        ReleaseBump.Minor.next(version) == ReleaseVersion.unsafeMake("0.4.0"),
        ReleaseBump.Major.next(version) == ReleaseVersion.unsafeMake("1.0.0"),
      )
    },
  )
end ShipBumpRequestSpec
