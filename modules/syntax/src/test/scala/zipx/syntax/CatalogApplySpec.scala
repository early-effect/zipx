package zipx.syntax

import zipx.core.*
import zio.test.*

object CatalogApplySpec extends ZIOSpecDefault:

  private val mixed =
    """
      |object MyVersions:
      |  val zio  = Lib("dev.zio", "zio", "2.1.26")
      |  val core = Ship("core", "1.4.2")
      |  val foo  = ShipGroup("foo", "1.4.2")("foo-api", "foo-cli")
      |""".stripMargin

  def spec = suite("CatalogApply")(
    test("applyBumps rewrites Lib and leaves Ship / ShipGroup constructors intact") {
      val bumps = List(DepBump(Lib("dev.zio", "zio", "2.1.26"), BumpKind.Patch, "2.1.27"))
      CatalogApply.applyBumps(mixed, bumps) match
        case Left(err)  => assertTrue(err.isEmpty)
        case Right(out) =>
          assertTrue(
            out.contains("""Lib("dev.zio", "zio", "2.1.27")"""),
            out.contains("""Ship("core", "1.4.2")"""),
            out.contains("""ShipGroup("foo", "1.4.2")("foo-api", "foo-cli")"""),
          )
    },
    test("applyShipBumps rewrites Ship and ShipGroup version literals and leaves Lib") {
      val bumps = List(
        ShipBump("core", "1.4.2", "1.4.3"),
        ShipBump("foo", "1.4.2", "1.5.0"),
      )
      CatalogApply.applyShipBumps(mixed, bumps) match
        case Left(err)  => assertTrue(err.isEmpty)
        case Right(out) =>
          assertTrue(
            out.contains("""Lib("dev.zio", "zio", "2.1.26")"""),
            out.contains("""Ship("core", "1.4.3")"""),
            out.contains("""ShipGroup("foo", "1.5.0")("foo-api", "foo-cli")"""),
            !out.contains("-ci"),
          )
    },
    test("applyShipBumps is Left when the constructor is missing") {
      CatalogApply.applyShipBumps(
        "val zio = Lib(\"dev.zio\", \"zio\", \"2.1.26\")\n",
        List(ShipBump("core", "1.4.2", "1.4.3")),
      ) match
        case Left(err) => assertTrue(err.contains("no Ship / ShipGroup constructor"), err.contains("core"))
        case Right(_)  => assertTrue(false)
    },
    test("applyShipBumps refuses to write a -ci suffix") {
      CatalogApply.applyShipBumps(mixed, List(ShipBump("core", "1.4.2", "1.4.3-ci"))) match
        case Left(err) => assertTrue(err.contains("-ci"))
        case Right(_)  => assertTrue(false)
    },
  )
end CatalogApplySpec
