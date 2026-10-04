package zipx.plugin

import sbt.VersionScheme
import sbt.internal.librarymanagement.mavenint.SbtPomExtraProperties
import sbt.librarymanagement.{EvictionWarningOptions, ModuleID, ScalaModuleInfo, UpdateReport}
import zipx.core.{BuildSession, Modver}

/** sbt reads a row's `<n>-ci` against a released `<n>` literally, so zipx exempts the build's own artifacts from its
  * eviction check and asks the question here instead: is the row's next release compatible, under the module's own
  * `versionScheme`, with the release a library was built against?
  */
private[plugin] object OwnEvictions:

  private type Compatible = ((ModuleID, Option[ModuleID], Option[ScalaModuleInfo])) => Boolean

  /** sbt keeps `evalPvp` private. The second-component rule is the same check, written here. */
  private def compatible(scheme: String): Option[Compatible] =
    scheme match
      case VersionScheme.EarlySemVer => Some(EvictionWarningOptions.guessEarlySemVer)
      case VersionScheme.SemVerSpec  => Some(EvictionWarningOptions.guessSemVer)
      case VersionScheme.PVP         => Some(pvpCompatible)
      case VersionScheme.Strict      => Some(EvictionWarningOptions.guessStrict)
      case VersionScheme.Always      => Some(EvictionWarningOptions.guessTrue)
      case _                         => None

  /** True when the first two components match. A patch stays compatible. A minor or major does not. */
  private def pvpCompatible: Compatible =
    (current, selected, _) =>
      selected match
        case Some(next) =>
          (
            sbt.librarymanagement.VersionNumber(current.revision).numbers,
            sbt.librarymanagement.VersionNumber(next.revision).numbers,
          ) match
            case (major +: minor +: _, otherMajor +: otherMinor +: _) =>
              major == otherMajor && minor == otherMinor
            case _ => false
        case None => false

  def incompatible(
      report: UpdateReport,
      own: Set[(String, String)],
      scalaModule: Option[ScalaModuleInfo],
  ): List[String] =
    val found = for
      config <- report.configurations
      detail <- config.details
      if own((detail.organization, detail.name))
      winner <- detail.modules.find(!_.evicted).toSeq
      line   <- unreleasedLine(winner.module.revision).toSeq
      scheme = (winner.extraAttributes ++ winner.module.extraAttributes)
        .get(SbtPomExtraProperties.VERSION_SCHEME_KEY)
        .getOrElse(VersionScheme.EarlySemVer)
      isCompatible <- compatible(scheme).toSeq
      next = winner.module.withRevision(line)
      evicted <- detail.modules.filter(_.evicted)
      if !isCompatible((evicted.module, Some(next), scalaModule))
    yield s"${detail.organization}:${detail.name}:${winner.module.revision} ($scheme) is selected over " +
      s"${evicted.module.revision}: ${next.revision} is not $scheme-compatible with it"
    found.distinct.toList
  end incompatible

  /** The release line of a development version. `<line>-ci` is what a session compiles. `<line>-SNAPSHOT` remains the
    * pointer.
    */
  private def unreleasedLine(revision: String): Option[String] =
    val suffixes = List(BuildSession.CompileSuffix, Modver.UnreleasedSuffix)
    suffixes.collectFirst { case suffix if revision.endsWith(suffix) => revision.stripSuffix(suffix) }
end OwnEvictions
