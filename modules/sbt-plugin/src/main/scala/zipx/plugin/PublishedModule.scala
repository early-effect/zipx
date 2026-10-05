package zipx.plugin

import sbt.librarymanagement.{CrossVersion, ModuleID, ScalaModuleInfo}

/** The Maven artifact id sbt publishes `module` under at one Scala version. */
object PublishedModule:

  /** sbt's own cross function: the platform (`sjs1`, `native0.5`) from `module.platformOpt` or the project's
    * `scalaModuleInfo`, then the module's cross version, which carries an sbt 2 plugin's `_sbt2` prefix.
    */
  def artifactId(module: ModuleID, scala: Option[ScalaModuleInfo]): String =
    CrossVersion(module, scala).fold(module.name)(_(module.name))
end PublishedModule
