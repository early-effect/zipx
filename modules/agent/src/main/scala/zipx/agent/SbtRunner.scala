package zipx.agent

import zipx.core.{BumpKind, VersionStrategy}

/** sbt launcher releases on the 2.x line. `sbt/setup-sbt` installs this zip and the runner reads
  * `project/build.properties`, so the pin is the launcher, not the build's sbt version.
  */
object SbtRunner:

  val Major: Int = 2

  private val Tag = """"tag_name"\s*:\s*"v(\d+\.\d+\.\d+)"""".r

  def versions(body: String): List[String] =
    Tag
      .findAllMatchIn(body)
      .map(_.group(1))
      .filter(_.takeWhile(_.isDigit) == Major.toString)
      .toList
      .distinct

  def candidate(current: String, body: String): Option[String] =
    VersionStrategy.npm.latestStable(versions(body)).filter { latest =>
      val kind = VersionStrategy.npm.classify(current, latest)
      kind != BumpKind.None && kind != BumpKind.PreRelease
    }
end SbtRunner
