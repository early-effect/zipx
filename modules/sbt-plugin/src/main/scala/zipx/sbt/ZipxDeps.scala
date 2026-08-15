package zipx.sbt

import _root_.sbt.*
import zipx.core.{Cross as ZipxCross, Lib, Plugin as ZipxPluginCoord, ZipxExclude}

/** Convert catalog rows to sbt `ModuleID`s. `libraryDependencies ++= ZipxDeps(V.zio, V.zioTest)`. */
object ZipxDeps:

  def apply(libs: Lib*): Seq[ModuleID] = libs.map(moduleID).toSeq

  def moduleID(lib: Lib): ModuleID =
    val mid = lib.cross match
      case ZipxCross.Java   => (lib.group: String)  % (lib.artifact: String) % (lib.version: String)
      case ZipxCross.Binary => (lib.group: String) %% (lib.artifact: String) % (lib.version: String)
      case ZipxCross.Full   =>
        ((lib.group: String) % (lib.artifact: String) % (lib.version: String)).cross(CrossVersion.full)
    val withCfg = lib.config.fold(mid)(cfg => mid % cfg)
    lib.excludes.foldLeft(withCfg)(applyExclude)

  def moduleID(plugin: ZipxPluginCoord): ModuleID =
    val mid = (plugin.group: String) % (plugin.artifact: String) % (plugin.version: String)
    plugin.excludes.foldLeft(mid)(applyExclude)

  private def applyExclude(mid: ModuleID, ex: ZipxExclude): ModuleID =
    ex.artifact match
      case None    => mid.excludeAll(ExclusionRule(organization = ex.organization))
      case Some(a) => mid.excludeAll(ExclusionRule(ex.organization, a))
end ZipxDeps
