package zipx.core

/** Resolve a loaded sbt plugin into a catalog [[Plugin]] row. Group and artifact are always written out; zipx never
  * infers them from the session. Version is an explicit override, else `Implementation-Version` on `from`.
  */
object ZipxSelf:

  def versionOf(from: Class[?]): Option[String] =
    Option(from.getPackage).flatMap(p => Option(p.getImplementationVersion)).filter(_.nonEmpty)

  def plugin(
      group: String,
      artifact: String,
      from: Class[?],
      version: Option[String] = None,
  ): Either[String, Plugin] =
    fromVersion(group, artifact, version.filter(_.nonEmpty).orElse(versionOf(from)), from.getName)

  private[core] def fromVersion(
      group: String,
      artifact: String,
      version: Option[String],
      origin: String,
  ): Either[String, Plugin] =
    version match
      case Some(v) =>
        for
          g <- GroupId.make(group)
          a <- ArtifactId.make(artifact)
          d <- DepVersion.make(v)
        yield Plugin(g, a, d, Nil)
      case None =>
        Left(
          s"zipx: cannot emit $group % $artifact: Implementation-Version is missing. Pass version, or set it on $origin."
        )
    end match
  end fromVersion
end ZipxSelf
