package zipx.plugin

import sbt.*
import zipx.core.{Cross as ZipxCross, Lib, Plugin as ZipxPluginCoord, ZipxExclude}

/** Convert catalog rows to sbt `ModuleID`s. Prefer `MyVersions.deps(zio, zioTest)` on a [[zipx.ZipxVersions]] catalog.
  */
object ZipxDeps:

  def apply(libs: Lib*): Seq[ModuleID] = libs.map(moduleID).toSeq

  val FromGraphAttr: String = "e:zipx.fromGraph"
  val FromGraphRev: String  = "zipx-from-graph"

  def isFromGraph(m: ModuleID): Boolean =
    m.extraAttributes.contains(FromGraphAttr) || m.revision == FromGraphRev

  def moduleID(lib: Lib): ModuleID =
    val revision = if lib.isAligned then FromGraphRev else (lib.version: String)
    val mid      = lib.cross match
      case ZipxCross.Java   => (lib.group: String)  % (lib.artifact: String) % revision
      case ZipxCross.Binary => (lib.group: String) %% (lib.artifact: String) % revision
      case ZipxCross.Full   =>
        ((lib.group: String) % (lib.artifact: String) % revision).cross(CrossVersion.full)
    val withCfg = lib.config.fold(mid)(cfg => mid % cfg)
    val withEx  = lib.excludes.foldLeft(withCfg)(applyExclude)
    lib.alignTo.fold(withEx)(src => withEx.withExtraAttributes(Map(FromGraphAttr -> (src: String))))
  end moduleID

  def moduleID(plugin: ZipxPluginCoord): ModuleID =
    val mid = (plugin.group: String) % (plugin.artifact: String) % (plugin.version: String)
    plugin.excludes.foldLeft(mid)(applyExclude)

  private def applyExclude(mid: ModuleID, ex: ZipxExclude): ModuleID =
    ex.artifact match
      case None    => mid.excludeAll(ExclusionRule(organization = ex.organization))
      case Some(a) => mid.excludeAll(ExclusionRule(ex.organization, a))
end ZipxDeps
