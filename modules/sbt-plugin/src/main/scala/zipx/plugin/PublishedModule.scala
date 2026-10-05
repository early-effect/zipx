package zipx.plugin

import sbt.librarymanagement.{CrossVersion, ModuleID, ScalaModuleInfo}

/** The Maven artifact id sbt publishes `module` under at one Scala version. */
object PublishedModule:

  /** sbt's own cross function, so the platform suffix and an sbt plugin's cross prefix match what sbt publishes. */
  def artifactId(module: ModuleID, scala: Option[ScalaModuleInfo]): String =
    CrossVersion(module, scala).fold(module.name)(_(module.name))
