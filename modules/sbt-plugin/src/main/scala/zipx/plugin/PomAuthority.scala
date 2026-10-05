package zipx.plugin

import lmcoursier.FromSbt
import lmcoursier.definitions.Configuration
import sbt.ExclusionRule
import sbt.librarymanagement.{Configurations, CrossVersion, ModuleID, ScalaModuleInfo}
import zipx.core.*

/** What a published POM states for itself, read the way lm-coursier reads the build: `FromSbt` turns each dependency's
  * configuration mapping into typed (scope, dependency) pairs, so no mapping string is read here.
  */
private[plugin] object PomAuthority:

  /** What `FromSbt` crosses a dependency with. */
  final case class Crossing(scala: String, binary: String, platform: Option[String])

  object Crossing:
    def of(scala: String, binary: String, info: Option[ScalaModuleInfo]): Crossing =
      Crossing(scala, binary, info.flatMap(_.platform))

  private val scopes: Map[Configuration, PomScope] = Map(
    Configuration(Configurations.Compile.name)  -> PomScope.Compile,
    Configuration(Configurations.Runtime.name)  -> PomScope.Runtime,
    Configuration(Configurations.Provided.name) -> PomScope.Provided,
    Configuration(Configurations.Optional.name) -> PomScope.Optional,
    Configuration(Configurations.Test.name)     -> PomScope.Test,
  )

  /** `scala-tool`, `zinc-tool`, and the other toolchain configurations publish nothing a consumer inherits. */
  private def scopeOf(config: Configuration): PomScope = scopes.getOrElse(config, PomScope.Tool)

  private def parsed(modules: Seq[ModuleID], crossing: Crossing) =
    modules.toList.flatMap { module =>
      FromSbt.dependencies(module, crossing.scala, crossing.binary, optionalCrossVer = true, crossing.platform)
    }

  def edges(projectDependencies: Seq[ModuleID], crossing: Crossing): List[PomEdge] =
    parsed(projectDependencies, crossing).map { (config, dependency) =>
      PomEdge(scopeOf(config), ResolvedModule(dependency.module.organization.value, dependency.module.name.value))
    }

  def declared(libraryDependencies: Seq[ModuleID], crossing: Crossing): List[PomDependency] =
    parsed(libraryDependencies.filterNot(AutoPlatform.ignore), crossing).map { (config, dependency) =>
      PomDependency(
        scopeOf(config),
        ResolvedModule(dependency.module.organization.value, dependency.module.name.value),
        DepRevision.of(dependency.version),
      )
    }

  /** `module` kept from bringing every excluded module but itself. The names are already crossed. */
  def excluding(module: ModuleID, excluded: List[ResolvedModule], info: Option[ScalaModuleInfo]): ModuleID =
    val self  = ResolvedModule(module.organization, PublishedModule.artifactId(module, info))
    val rules = excluded.filterNot(_ == self).map { other =>
      ExclusionRule(other.group, other.name).withCrossVersion(CrossVersion.disabled)
    }
    if rules.isEmpty then module else module.excludeAll(rules*)
end PomAuthority
