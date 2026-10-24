package zipx.core

import zio.test.*

object JdkFloorSpec extends ZIOSpecDefault:

  import JdkFloor.{Vendor, Verdict}

  private def assess(vendor: Vendor, raw: String): Verdict = JdkFloor.assess(vendor, raw)

  private def safe(vendor: Vendor, raw: String): Boolean =
    assess(vendor, raw) == Verdict.Safe

  def spec = suite("JdkFloor")(
    test("a major below 21 is refused for every vendor") {
      val vendors  = List(Vendor.Zulu, Vendor.Temurin, Vendor.OpenJdk)
      val versions = (1 to 20).toList.map(feature => s"$feature.0.1")
      val refused  = versions.forall { raw =>
        vendors.forall { vendor =>
          assess(vendor, raw) match
            case Verdict.BelowMinimum(feature) => feature < JdkFloor.MinimumFeature
            case _                             => false
        }
      }
      assertTrue(
        refused,
        assess(Vendor.Zulu, "8") == Verdict.BelowMinimum(8),
        assess(Vendor.Temurin, "11.0.33+9") == Verdict.BelowMinimum(11),
        assess(Vendor.OpenJdk, "17.0.21.0.2") == Verdict.BelowMinimum(17),
        assess(Vendor.Zulu, "17.0.21+9") == Verdict.BelowMinimum(17),
      )
    },
    test("Zulu honors its +build and Temurin does not") {
      assertTrue(
        safe(Vendor.Zulu, "25.0.5+8"),
        !safe(Vendor.Zulu, "25.0.5+7"),
        !safe(Vendor.Temurin, "25.0.5+8"),
        safe(Vendor.Zulu, "21.0.13+9"),
        !safe(Vendor.Zulu, "21.0.13+8"),
        !safe(Vendor.Temurin, "21.0.13+9"),
      )
    },
    test("OpenJDK is safe at the extra component, and a bare update is not") {
      val vendors = List(Vendor.Zulu, Vendor.Temurin, Vendor.OpenJdk)
      assertTrue(
        safe(Vendor.OpenJdk, "25.0.5.0.2"),
        safe(Vendor.OpenJdk, "21.0.13.0.2"),
        safe(Vendor.Temurin, "25.0.5.0.2"),
        vendors.forall(vendor => !safe(vendor, "25.0.5")),
        vendors.forall(vendor => !safe(vendor, "21.0.13")),
        vendors.forall(vendor => safe(vendor, "25.0.6")),
        vendors.forall(vendor => safe(vendor, "21.0.14")),
      )
    },
    test("a major with no line is unsupported, including a Zulu +build") {
      assertTrue(
        assess(Vendor.OpenJdk, "22.0.2") == Verdict.Unsupported(22),
        assess(Vendor.Zulu, "26.0.1+9") == Verdict.Unsupported(26),
        safe(Vendor.OpenJdk, "27.0.1.0.2"),
        !safe(Vendor.Zulu, "27.0.1+99"),
        !safe(Vendor.OpenJdk, "27.0.1"),
      )
    },
    test("vendor text does not let an unknown build inherit the Zulu column") {
      assertTrue(
        JdkFloor.vendorOf("Azul Systems, Inc.") == Vendor.Zulu,
        JdkFloor.vendorOf("Eclipse Adoptium") == Vendor.Temurin,
        JdkFloor.vendorOf("Oracle Corporation") == Vendor.OpenJdk,
        JdkFloor.vendorOf("Wolfi") == Vendor.OpenJdk,
        JdkFloor.vendorOf("Homebrew") == Vendor.OpenJdk,
        !safe(Vendor.OpenJdk, "25.0.5+8"),
      )
    },
    test("the fifth version component is the respin") {
      assertTrue(
        JdkFloor.parse("25.0.5.0.2").map(_.numbers) == Right(List(25, 0, 5, 0, 2)),
        JdkFloor.parse("25.0.5+8").map(_.build) == Right(Some(8)),
        JdkFloor.parse("25.0.5").map(_.build) == Right(None),
        JdkFloor.parse("1.8.0_402").isLeft,
        JdkFloor.fixedOpenJdk(21).contains("21.0.13.0.2"),
        JdkFloor.fixedOpenJdk(25).contains("25.0.5.0.2"),
        JdkFloor.fixedOpenJdk(17).isEmpty,
      )
    },
  )
end JdkFloorSpec
