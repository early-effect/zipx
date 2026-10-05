package zipx.core

import zio.test.*

object CatalogReleasesSpec extends ZIOSpecDefault:

  private val crossing = FakeReleases.crossing

  private val version: Gen[Any, String] =
    (Gen.int(0, 3) <*> Gen.int(0, 12) <*> Gen.int(0, 9)).map((a, b, c) => s"$a.$b.$c")

  /** A `com.example:widgets` row at a current version, and releases around it in ascending order. */
  private val releases: Gen[Any, (Lib, List[String])] =
    (version <*> Gen.listOfBounded(0, 6)(version))
      .map((current, others) => (DepVersion.make(current), (current :: others).distinct))
      .collect { case (Right(current), all) =>
        val row =
          Lib(GroupId("com.example"), ArtifactId("widgets"), current, Cross.Binary, None, Nil, None, None)
        row -> all.sortWith((a, b) => VersionStrategy.npm.classify(a, b) != BumpKind.None)
      }

  private val widgets = Lib("com.example", "widgets", "1.0.0")

  private val platforms = suite("platforms")(
    test("a row published for one platform only is read off that platform's artifact") {
      check(releases, Gen.elements(ScalaPlatform.values.toList*)) { case ((row, all), platform) =>
        val repo = FakeReleases.of(s"widgets_${platform.infix}3" -> all)
        val to   = ZipxCatalog.outdated(List(row), crossing, repo).map(_.bumps.map(_.to))
        assertTrue(
          CatalogReleases.available(row, crossing, repo) == Right(all),
          to == Right(ZipxCatalog.latest(all).filter(_ != row.version).toList),
        )
      }
    },
    test("a row moves only to a version every platform it is published for has") {
      check(releases, releases) { case ((row, jvm), (_, more)) =>
        val js   = ((row.version: String) :: more).distinct
        val repo = FakeReleases.of("widgets_3" -> jvm, "widgets_sjs1_3" -> js)
        val to   = ZipxCatalog.outdated(List(row), crossing, repo).map(_.bumps.map(_.to))
        assertTrue(to.forall(_.forall(v => jvm.contains(v) && js.contains(v))))
      }
    },
    test("a platform that never had the row's version does not hold it back") {
      val repo = FakeReleases.of("widgets_3" -> List("1.0.0", "1.1.0"), "widgets_sjs1_3" -> List("0.3.0"))
      assertTrue(ZipxCatalog.outdated(List(widgets), crossing, repo).map(_.bumps.map(_.to)) == Right(List("1.1.0")))
    },
    test("a Java row, a full-cross row, and a plugin are each read under the names sbt gives them") {
      val java   = Lib("org.brotli", "dec", "0.1.2").java
      val full   = Lib("org.scalameta", "semanticdb-scalac", "4.9.0").full
      val plugin = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
      assertTrue(
        PublishedNames.of(java, crossing) == List("dec"),
        PublishedNames.of(full, crossing).headOption.contains("semanticdb-scalac_3.9.0"),
        PublishedNames.of(plugin, crossing) == List("sbt-scalafmt_sbt2_3"),
      )
    },
  )

  private val families = suite("families")(
    test("a family moves on its literal, to the newest version every member has") {
      val js   = Lib("rocks.earlyeffect", "ascent-js", "0.7.1")
      val css  = js.mod("ascent-css")
      val repo = FakeReleases.of(
        "ascent-js_sjs1_3"  -> List("0.7.1", "0.9.0", "0.10.0"),
        "ascent-css_3"      -> List("0.7.1", "0.9.0"),
        "ascent-css_sjs1_3" -> List("0.7.1", "0.9.0"),
      )
      val updates = ZipxCatalog.outdated(List(js, css), crossing, repo)
      assertTrue(
        updates.map(_.bumps) == Right(List(DepBump(js, BumpKind.Minor, "0.9.0"))),
        updates.map(_.held) == Right(List(FamilyHold(js, "0.10.0", Some("0.9.0")))),
        updates.toOption.flatMap(_.held.headOption).exists(_.message.contains("Give it its own Lib row to take 0.10.0")),
      )
    },
    test("every bump names a literal row, which a catalog rewrite can find") {
      check(releases, releases) { case ((row, a), (_, b)) =>
        val mod  = row.mod("widgets-extra")
        val repo = FakeReleases.of("widgets_3" -> a, "widgets-extra_3" -> ((row.version: String) :: b).distinct)
        val all  = ZipxCatalog.outdated(List(row, mod), crossing, repo).map(_.bumps)
        assertTrue(all.forall(_.forall(_.coord == row)))
      }
    },
  )

  def spec = suite("CatalogReleases")(platforms, families)
end CatalogReleasesSpec
