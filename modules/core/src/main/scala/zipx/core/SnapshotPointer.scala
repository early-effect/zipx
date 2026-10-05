package zipx.core

/** The `<line>-SNAPSHOT` module whose POM records the latest full sha. `update` never resolves it. */
object SnapshotPointer:

  val ShaElement: String = "zipx.snapshot.sha"

  def pomRelative(organization: String, artifact: String, version: String): String =
    s"${dir(organization, artifact, version)}/$artifact-$version.pom"

  /** sbt's unique snapshot keeps the `<line>-SNAPSHOT` directory and timestamps the file name. */
  def uniquePomRelative(organization: String, artifact: String, pointerVersion: String, stamped: String): String =
    s"${dir(organization, artifact, pointerVersion)}/$artifact-$stamped.pom"

  def metadataRelative(organization: String, artifact: String, version: String): String =
    s"${dir(organization, artifact, version)}/maven-metadata.xml"

  def pointerVersion(line: ReleaseVersion): String = s"$line${Modver.UnreleasedSuffix}"

  def shaFromPom(xml: String): Either[String, GitSha] =
    val start = s"<$ShaElement>"
    val end   = s"</$ShaElement>"
    val at    = xml.indexOf(start)
    if at < 0 then Left(s"pointer POM has no <$ShaElement>")
    else
      val from  = at + start.length
      val until = xml.indexOf(end, from)
      if until < 0 then Left(s"pointer POM has no </$ShaElement>")
      else GitSha.make(xml.substring(from, until).trim).left.map(err => s"pointer POM sha: $err")
  end shaFromPom

  /** Maven unique-snapshot metadata: `<timestamp>` and `<buildNumber>` under `<snapshot>`. */
  def timestampedVersion(line: String, metadata: String): Option[String] =
    element(metadata, "timestamp").zip(element(metadata, "buildNumber")).map { (stamp, build) =>
      s"$line-$stamp-$build"
    }

  def deleteBroken(xmlExists: Boolean, sha1Agrees: Option[Boolean], md5Agrees: Option[Boolean]): Boolean =
    val disagrees = sha1Agrees.contains(false) || md5Agrees.contains(false)
    val orphan    = !xmlExists && (sha1Agrees.isDefined || md5Agrees.isDefined)
    disagrees || orphan

  private def dir(organization: String, artifact: String, version: String): String =
    s"${organization.replace('.', '/')}/$artifact/$version"

  private def element(xml: String, name: String): Option[String] =
    val start = s"<$name>"
    val at    = xml.indexOf(start)
    if at < 0 then None
    else
      val from  = at + start.length
      val until = xml.indexOf(s"</$name>", from)
      if until < 0 then None else Some(xml.substring(from, until).trim).filter(_.nonEmpty)
end SnapshotPointer
