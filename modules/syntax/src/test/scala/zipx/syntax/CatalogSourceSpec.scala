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
    },
    test("a row reads the way the catalog builds it: through vals, .mod families, and cross and config modifiers") {
      val source =
        """
          |object MyVersions:
          |  val zio     = Lib("dev.zio", "zio", "2.1.26")
          |  val streams = zio.mod("zio-streams")
          |  val zioTest = zio.mod("zio-test").test
          |  val brotli  = Lib("org.brotli", "dec", "0.1.2").java.test
          |  val semdb   = Lib("org.scalameta", "semanticdb-scalac", "4.9.0").full
          |  val http    = Lib("dev.zio", "zio-http", "3.11.4")
          |  val testkit = http.mod("zio-http-testkit").test.fromGraph
          |  val plain   = Lib("dev.zio", "zio-json", "1.1.0").excluding(ZipxExclude.org("org.scala-lang", "scala3-library_3"))
          |""".stripMargin
      val rows = CatalogSource.parse(source).map(_.libs)
      assertTrue(
        rows.map(_.map(_.artifact: String)) == Right(
          List("zio", "zio-streams", "zio-test", "dec", "semanticdb-scalac", "zio-http", "zio-http-testkit", "zio-json")
        ),
        rows.map(_.map(_.family.map(a => a: String))) == Right(
          List(None, Some("zio"), Some("zio"), None, None, None, Some("zio-http"), None)
        ),
        rows.map(_.map(_.cross)) == Right(
          List(
            zipx.core.Cross.Binary,
            zipx.core.Cross.Binary,
            zipx.core.Cross.Binary,
            zipx.core.Cross.Java,
            zipx.core.Cross.Full,
            zipx.core.Cross.Binary,
            zipx.core.Cross.Binary,
            zipx.core.Cross.Binary,
          )
        ),
        rows.map(_.map(_.version: String).toSet) == Right(Set("2.1.26", "0.1.2", "4.9.0", "3.11.4", "1.1.0")),
        rows.map(_.filter(_.isAligned).map(_.artifact: String)) == Right(List("zio-http-testkit")),
      )
    },
  )
end CatalogSourceSpec
