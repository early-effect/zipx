package zipx.core

import java.lang.Runtime.Version

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Whether a JDK is a fixed build of the compromised trust store.
  *
  * A `+build` counts only for Zulu. Every other vendor needs the OpenJDK extra component, or a later update. A feature
  * below the minimum is refused before that check. A feature with no line is unsupported, not safe.
  */
object JdkFloor:

  val MinimumFeature: Int = 21

  enum Vendor:
    case Zulu, Temurin, OpenJdk

  enum Verdict:
    case Safe
    case BelowMinimum(feature: Int)
    case Vulnerable(vendor: Vendor, rendered: String)
    case Unsupported(feature: Int)
    case Unreadable(raw: String)

    def message: String = this match
      case Safe =>
        "zipx: JDK is a fixed build"
      case BelowMinimum(feature) =>
        s"zipx: JDK $feature is below the minimum $MinimumFeature"
      case Vulnerable(vendor, rendered) =>
        s"zipx: $vendor JDK $rendered is not a fixed build"
      case Unsupported(feature) =>
        s"zipx: JDK $feature has no fixed-build line"
      case Unreadable(raw) =>
        s"zipx: JDK version '$raw' could not be read"
  end Verdict

  final case class Release(numbers: List[Int], build: Option[Int]):
    def feature: Option[Int] = numbers.lift(0)
    def update: Int          = numbers.lift(2).getOrElse(0)
    def extra: Int           = numbers.lift(4).getOrElse(0)

    def render: String =
      val body = if numbers.isEmpty then "?" else numbers.mkString(".")
      build.fold(body)(n => s"$body+$n")

  /** `vulnerableUpdate` is the affected update. A later update is safe. The same update is safe with OpenJDK extra
    * component `openJdkExtra`, or, for Zulu only, a `+build` at least `zuluBuild`.
    */
  final case class Line(
      feature: Int,
      vulnerableUpdate: Int,
      openJdkExtra: Int,
      zuluBuild: Option[Int],
  )

  val Lines: List[Line] = List(
    Line(21, vulnerableUpdate = 13, openJdkExtra = 2, zuluBuild = Some(9)),
    Line(25, vulnerableUpdate = 5, openJdkExtra = 2, zuluBuild = Some(8)),
    Line(27, vulnerableUpdate = 1, openJdkExtra = 2, zuluBuild = None),
  )

  def fixedOpenJdk(feature: Int): Option[String] =
    Lines.find(_.feature == feature).map { line =>
      s"${line.feature}.0.${line.vulnerableUpdate}.0.${line.openJdkExtra}"
    }

  def vendorOf(javaVendor: String): Vendor =
    val name = javaVendor.toLowerCase
    if name.contains("azul") || name.contains("zulu") then Vendor.Zulu
    else if name.contains("adoptium") || name.contains("temurin") then Vendor.Temurin
    else Vendor.OpenJdk

  def parse(raw: String): Either[Verdict.Unreadable, Release] =
    try Right(releaseOf(Version.parse(raw)))
    catch case _: IllegalArgumentException => Left(Verdict.Unreadable(raw))

  def releaseOf(version: Version): Release =
    val numbers = version.version().asScala.toList.map(_.intValue)
    val build   = version.build().toScala.map(_.intValue)
    Release(numbers, build)

  def assess(vendor: Vendor, release: Release): Verdict =
    release.feature match
      case None =>
        Verdict.Unreadable(release.render)
      case Some(feature) if feature < MinimumFeature =>
        Verdict.BelowMinimum(feature)
      case Some(feature) =>
        Lines.find(_.feature == feature) match
          case None =>
            Verdict.Unsupported(feature)
          case Some(line) =>
            if safe(vendor, line, release) then Verdict.Safe
            else Verdict.Vulnerable(vendor, release.render)

  def assess(vendor: Vendor, raw: String): Verdict =
    parse(raw).fold(identity, release => assess(vendor, release))

  def running(javaVendor: String, version: Version): Verdict =
    assess(vendorOf(javaVendor), releaseOf(version))

  private def safe(vendor: Vendor, line: Line, release: Release): Boolean =
    if release.update > line.vulnerableUpdate then true
    else if release.update < line.vulnerableUpdate then false
    else
      val respin = release.extra >= line.openJdkExtra
      val zulu   = vendor == Vendor.Zulu && line.zuluBuild.exists(floor => release.build.exists(_ >= floor))
      respin || zulu
end JdkFloor
