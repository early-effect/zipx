package zipx.syntax

import zio.test.*

object CatalogSourceSpec extends ZIOSpecDefault:

  private val mixed =
    """
      |object MyVersions:
      |  val zio    = Lib("dev.zio", "zio", "2.1.26")
      |  val core   = Ship("core", "1.4.2")
      |  val viaNew = new Ship("models", "1.4.2")
      |  val empty  = ShipGroup("empty", "1.0.0")()
      |  val one    = ShipGroup("one", "0.2.0")("cli")
      |  val foo    = ShipGroup("foo", "1.4.2")("foo-api", "foo-cli", "foo-impl")
      |""".stripMargin

  def spec = suite("CatalogSource")(
    test("parses Ship, new Ship, and curried ShipGroup with 0, 1, and many members") {
      CatalogSource.parse(mixed) match
        case Left(err) => assertTrue(err.isEmpty)
        case Right(c)  =>
          val ids = c.ships.map(r => s"${r.label}:${r.identity}:${r.memberRoots.size}")
          assertTrue(
            c.coords.map(x => x.artifact: String) == List("zio"),
            ids == List(
              "Ship:core:1",
              "Ship:models:1",
              "ShipGroup:empty:0",
              "ShipGroup:one:1",
              "ShipGroup:foo:3",
            ),
          )
    }
  )
end CatalogSourceSpec
