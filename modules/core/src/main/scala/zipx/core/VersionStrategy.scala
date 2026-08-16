package zipx.core

import scala.math.Ordering.Implicits.infixOrderingOps

/** Given current and candidate versions, what kind of bump this is, and which candidate is latest stable. */
trait VersionStrategy:
  def classify(current: String, candidate: String): BumpKind
  def latestStable(candidates: List[String]): Option[String]
  def isPreRelease(version: String): Boolean

object VersionStrategy:

  /** npm / semver: `major.minor.patch` with optional `-pre` suffix. Unparseable pairs are [[BumpKind.None]]. */
  val npm: VersionStrategy = SemverStrategy

  /** Exact string match. Equal is [[BumpKind.None]]; any other pair is [[BumpKind.Major]]. */
  val exact: VersionStrategy = ExactStrategy

  private object ExactStrategy extends VersionStrategy:
    def classify(current: String, candidate: String): BumpKind =
      if current == candidate then BumpKind.None else BumpKind.Major

    def latestStable(candidates: List[String]): Option[String] =
      candidates.lastOption

    def isPreRelease(version: String): Boolean = false

  private final case class SemVer(major: Int, minor: Int, patch: Int, pre: Option[String]):
    def isPre: Boolean                 = pre.isDefined
    def core: (Int, Int, Int)          = (major, minor, patch)
    def numeric: (Int, Int, Int, Byte) = (major, minor, patch, if isPre then 0 else 1)

  private object SemverStrategy extends VersionStrategy:
    def classify(current: String, candidate: String): BumpKind =
      (parse(current), parse(candidate)) match
        case (Some(a), Some(b)) if a == b                        => BumpKind.None
        case (Some(a), Some(b)) if b.numeric < a.numeric         => BumpKind.None
        case (Some(_), Some(b)) if b.isPre                       => BumpKind.PreRelease
        case (Some(a), Some(b)) if a.major != b.major            => BumpKind.Major
        case (Some(a), Some(b)) if a.minor != b.minor            => BumpKind.Minor
        case (Some(a), Some(b)) if a.patch != b.patch || a.isPre => BumpKind.Patch
        case _                                                   => BumpKind.None

    def latestStable(candidates: List[String]): Option[String] =
      val parsed = candidates.flatMap(c => parse(c).filterNot(_.isPre).map(c -> _))
      parsed.maxByOption(_._2.numeric).map(_._1)

    def isPreRelease(version: String): Boolean = parse(version).exists(_.isPre)

    private def parse(raw: String): Option[SemVer] =
      val trimmed     = raw.stripPrefix("v")
      val dash        = trimmed.indexOf('-')
      val (core, pre) =
        if dash < 0 then (trimmed, None)
        else (trimmed.substring(0, dash), Some(trimmed.substring(dash + 1)))
      val parts = core.split("\\.", -1)
      if parts.length != 3 then None
      else
        for
          major <- parts(0).toIntOption
          minor <- parts(1).toIntOption
          patch <- parts(2).toIntOption
        yield SemVer(major, minor, patch, pre.filter(_.nonEmpty))
    end parse
  end SemverStrategy
end VersionStrategy
