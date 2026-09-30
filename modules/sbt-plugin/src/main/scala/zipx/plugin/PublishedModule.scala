package zipx.plugin

import sbt.librarymanagement.{Artifact, ModuleID, ScalaVersion}

/** Maven artifact id sbt will publish for `module` at one Scala version.
  *
  * `projectID` already carries the platform (`sjs1`, `native0.5`) and, for an sbt 2 plugin, the `_sbt2` cross prefix.
  * `Artifact.artifactName` is the function sbt uses to name the published file. Rebuilding `moduleName` with
  * `CrossVersion.apply` alone drops both, so a registry lookup 404s a coordinate that is already published.
  */
object PublishedModule:
  private val Sentinel = "zipx-coordinate"

  def artifactId(
      module: ModuleID,
      scalaFull: String,
      scalaBinary: String,
      namer: (ScalaVersion, ModuleID, Artifact) => String = Artifact.artifactName,
  ): String =
    val stamped = module.withRevision(Sentinel)
    val file    = namer(ScalaVersion(scalaFull, scalaBinary), stamped, Artifact(stamped.name))
    val suffix  = s"-$Sentinel.${Artifact.DefaultExtension}"
    if file.endsWith(suffix) then file.stripSuffix(suffix)
    else sys.error(s"zipx: sbt named published file '$file', expected it to end with '$suffix'")
  end artifactId
end PublishedModule
