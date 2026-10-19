package zipx.agent

/** The agent base image is Ubuntu 24.04. The pin is that tag's digest, so a republish moves the digest and a new LTS
  * does not.
  */
object UbuntuBase:

  val Tag: String = "24.04"

  private val Digest = """"digest"\s*:\s*"(sha256:[a-f0-9]{64})"""".r

  def digest(body: String): Either[String, String] =
    Digest.findFirstMatchIn(body) match
      case Some(found) => Right(found.group(1))
      case None        => Left(s"zipx: ubuntu $Tag tag response has no digest")

  def candidate(current: String, body: String): Either[String, Option[String]] =
    digest(body).map(found => Option.when(found != current)(found))
end UbuntuBase
