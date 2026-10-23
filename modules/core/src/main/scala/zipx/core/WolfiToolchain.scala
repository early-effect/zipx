package zipx.core

/** Pinned public Wolfi base and the apk packages the toolchain image installs. A catalog pin overrides a constant and
  * still has to pass [[JdkFloor]].
  */
object WolfiToolchain:

  val BaseImage: String  = "cgr.dev/chainguard/wolfi-base"
  val BaseDigest: String = "sha256:f15fea2f49cb14dceaea491fcbde0d35e0c190126a744ac05a9838440d47d994"
  val JavaHome: String   = "/usr/lib/jvm/default-jvm"

  val Bash: String = "5.3-r14"
  val Git: String  = "2.56.0-r3"
  val Curl: String = "8.23.0-r2"

  val FuseOverlayfs: String = "1.18-r1"
  val Iptables: String      = "1.8.13-r5"

  final case class Jdk(major: Int, version: String):
    def id: String         = s"openjdk-$major"
    def runtime: String    = s"openjdk-$major"
    def defaultJdk: String = s"openjdk-$major-default-jdk"

  val Jdk21: Jdk      = Jdk(21, "21.0.13.0.2-r0")
  val Jdk25: Jdk      = Jdk(25, "25.0.5.0.2-r0")
  val Jdks: List[Jdk] = List(Jdk21, Jdk25)

  def jdk(major: Int): Option[Jdk] = Jdks.find(_.major == major)

  def reference(digest: String): String = s"$BaseImage@$digest"

  final case class Apk(numbers: List[Int], epoch: Int, raw: String):
    def upstream: String = numbers.mkString(".")

  def parseDigest(raw: String): Option[String] =
    if raw.matches("sha256:[a-f0-9]{64}") then Some(raw) else None

  def parseApk(raw: String): Option[Apk] = raw match
    case s"$upstream-r$epoch" =>
      val parts   = upstream.split('.').toList
      val numbers = parts.flatMap(_.toIntOption)
      if parts.nonEmpty && numbers.length == parts.length then epoch.toIntOption.map(n => Apk(numbers, n, raw))
      else None
    case _ => None

  /** Newest apk string in `available` when it is newer than `current`. An unreadable `current` is replaced. */
  def newestApk(current: String, available: List[String]): Either[String, Option[String]] =
    val parsed = available.flatMap(parseApk)
    parsed.maxOption(using apkOrder) match
      case None =>
        Left("zipx: no apk version is published")
      case Some(best) =>
        parseApk(current) match
          case None =>
            Right(Some(best.raw))
          case Some(cur) if apkOrder.gt(best, cur) =>
            Right(Some(best.raw))
          case Some(_) =>
            Right(None)
    end match
  end newestApk

  /** OpenJDK upstream of an apk version, without the `-r` epoch. */
  def jdkUpstream(raw: String): Option[String] = parseApk(raw).map(_.upstream)

  private val apkOrder: scala.math.Ordering[Apk] =
    import scala.math.Ordering.Implicits.seqOrdering
    scala.math.Ordering.by(apk => (apk.numbers, apk.epoch))
end WolfiToolchain
