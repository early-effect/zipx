package zipx.plugin

import coursier.cache.{CacheDefaults, CacheEnv, CachePolicy, FileCache}
import coursier.core.{Dependency, Module, ModuleName, Organization, Repository}
import coursier.graph.Conflict
import coursier.ivy.IvyRepository
import coursier.maven.MavenRepository
import coursier.params.{Mirror, ResolutionParams}
import coursier.version.VersionConstraint
import coursier.{CoursierEnv, Resolve}
import lmcoursier.CoursierConfiguration
import sbt.librarymanagement.{ConfigurationReport, FileRepository, Patterns, Resolver, URLRepository, UpdateReport}
import zipx.core.*

import java.io.File
import java.nio.file.Paths
import scala.util.control.NonFatal

/** What the libraries in a resolved graph asked of the modules the catalog forces.
  *
  * lm-coursier leaves every conflict on a `dependencyOverrides` module out of the update report. Coursier's conflict
  * graph keeps them, so the probe resolves the report's own graph again with every module forced to what sbt selected,
  * and reads [[Conflict]]. It reads only the cache `update` just filled: no network, no jars.
  */
private[plugin] object CatalogProbe:

  enum ProbeError:
    case Unresolved(config: String, reason: String)

    def message: String = this match
      case Unresolved(config, reason) =>
        s"could not read what the $config graph asks of the catalog rows, so staleness is unchecked: $reason"

  private final case class Graph(
      selected: Map[ResolvedModule, String],
      callers: Map[ResolvedModule, Set[ResolvedModule]],
  )

  def wanted(
      report: UpdateReport,
      forced: Set[ResolvedModule],
      inRepo: Set[ResolvedModule],
      conf: CoursierConfiguration,
  ): Either[ProbeError, Map[ResolvedModule, List[Wanted]]] =
    val graphs = report.configurations.toList
      .map(config => config.configuration.name -> graphOf(config))
      .filter((_, graph) => graph.callers.exists((module, by) => forced.contains(module) && !by.subsetOf(inRepo)))
      .distinctBy((_, graph) => graph)
    graphs
      .foldLeft[Either[ProbeError, List[(ResolvedModule, Wanted)]]](Right(Nil)) { case (found, (config, graph)) =>
        found.flatMap(sofar => conflictsOf(config, graph, forced, inRepo, conf).map(sofar ++ _))
      }
      .map(_.distinct.groupMap((module, _) => module)((_, want) => want))
  end wanted

  private def graphOf(config: ConfigurationReport): Graph =
    val modules = config.modules.toList.filterNot(_.evicted)
    Graph(
      modules.map(module => CatalogResolution.of(module.module) -> module.module.revision).toMap,
      modules
        .map(module =>
          CatalogResolution.of(module.module) -> module.callers.map(c => CatalogResolution.of(c.caller)).toSet
        )
        .toMap,
    )
  end graphOf

  /** Rooted at the external modules this build depends on directly, with every external module forced to its selected
    * revision and the in-repo ones left out (no registry holds them). Only conflicts on an edge the real graph has
    * count, so an exclusion the probe does not repeat cannot invent one.
    */
  private def conflictsOf(
      config: String,
      graph: Graph,
      forced: Set[ResolvedModule],
      inRepo: Set[ResolvedModule],
      conf: CoursierConfiguration,
  ): Either[ProbeError, List[(ResolvedModule, Wanted)]] =
    val external = graph.selected.filterNot((module, _) => inRepo.contains(module))
    val roots    = external.toList.collect {
      case (module, revision) if graph.callers.get(module).exists(_.exists(inRepo.contains)) =>
        Dependency(coursierModule(module), VersionConstraint(revision))
    }
    val params = ResolutionParams().copy(
      forceVersion0 = external.map((module, revision) => coursierModule(module) -> VersionConstraint(revision)),
      exclusions = (inRepo.toList.map(module => (module.group, module.name)) ++ conf.excludeDependencies).map {
        (group, name) => (Organization(group), ModuleName(name))
      }.toSet,
      profiles = conf.mavenProfiles.toSet,
    )
    val resolve = Resolve().copy(
      cache = FileCache().copy(
        location = conf.cache.getOrElse(CacheDefaults.location),
        cachePolicies = Seq(CachePolicy.LocalOnly),
      ),
      dependencies = roots,
      repositories = conf.resolvers.flatMap(repository(_, ivyProperties(conf.ivyHome))),
      mirrors = mirrors,
      resolutionParams = params,
    )
    val resolved =
      try resolve.either().left.map(err => ProbeError.Unresolved(config, err.getMessage))
      catch case NonFatal(err) => Left(ProbeError.Unresolved(config, err.toString))
    resolved.map { resolution =>
      Conflict(resolution).toList.flatMap { conflict =>
        val module = of(conflict.module)
        val by     = of(conflict.dependeeModule)
        val onEdge = graph.callers.get(module).exists(_.contains(by))
        wantedOf(conflict.wantedVersionConstraint)
          .filter(_ => forced.contains(module) && onEdge)
          .map(raw => module -> Wanted(DepRevision.of(raw), by))
      }
    }
  end conflictsOf

  /** The version a library named, or the floor of the range it named. */
  private def wantedOf(constraint: VersionConstraint): Option[String] =
    constraint.preferred.orElse(constraint.interval.from).map(_.asString)

  private def of(module: Module): ResolvedModule = ResolvedModule(module.organization.value, module.name.value)

  private def coursierModule(module: ResolvedModule): Module =
    Module(Organization(module.group), ModuleName(module.name), Map.empty)

  /** sbt's mirror rule: coursier's own configuration, and Maven's settings.xml only when it is named explicitly, so a
    * `*` mirror there does not shadow the build's resolvers (sbt#9821). Matching it keeps the probe on the cache
    * entries `update` wrote.
    */
  private lazy val mirrors: Seq[Mirror] =
    val settings = CoursierEnv.mavenSettings.read()
    val explicit = settings.env.orElse(settings.prop).exists(_.trim.nonEmpty)
    CoursierEnv.defaultMirrors(
      CoursierEnv.mirrors.read(),
      CoursierEnv.mirrorsExtra.read(),
      CoursierEnv.scalaCliConfig.read(),
      CacheEnv.configDir.read(),
    ) ++ (
      if explicit then
        CoursierEnv.defaultMavenSettingsMirrors(
          settings,
          CoursierEnv.mavenHome.read(),
          CoursierEnv.mavenHomeFallback.read(),
        )
      else Nil
    )
  end mirrors

  private def ivyProperties(ivyHome: Option[File]): Map[String, String] =
    val home = sys.props
      .get("ivy.home")
      .orElse(ivyHome.map(_.getAbsoluteFile.toURI.getPath))
      .getOrElse(new File(new File(sys.props.getOrElse("user.home", ".")), ".ivy2").toURI.getPath)
    Map("ivy.home" -> home, "sbt.ivy.home" -> sys.props.getOrElse("sbt.ivy.home", home)) ++ sys.props

  /** The repository lm-coursier builds for `resolver`. sbt's converter returns its shaded coursier, so the rule is
    * repeated here: a Maven root, or one ivy pattern pair that is a Maven layout or an Ivy one.
    */
  private def repository(resolver: Resolver, properties: Map[String, String]): Option[Repository] =
    resolver match
      case maven: sbt.librarymanagement.MavenRepository => Some(MavenRepository(withSlash(maven.root)))
      case file: FileRepository                         => patterned(file.patterns, properties, local = true)
      case url: URLRepository                           => patterned(url.patterns, properties, local = false)
      case _                                            => None

  private def patterned(patterns: Patterns, properties: Map[String, String], local: Boolean): Option[Repository] =
    (patterns.ivyPatterns.toList, patterns.artifactPatterns.toList) match
      case (ivy :: Nil, artifact :: Nil) =>
        def uri(pattern: String)  = if local then fileUri(pattern) else pattern
        def base(pattern: String) = pattern.takeWhile(c => c != '[' && c != '(')
        if patterns.isMavenCompatible && base(ivy) == base(artifact) then
          Some(MavenRepository(withSlash(uri(base(ivy)))))
        else
          IvyRepository
            .parse(
              uri(artifact),
              metadataPatternOpt = Some(uri(ivy)),
              changing = Option.when(local)(true),
              properties = properties,
              dropInfoAttributes = true,
            )
            .toOption
        end if
      case _ => None

  private def fileUri(path: String): String =
    path.indexWhere(c => c == '[' || c == '$' || c == '(') match
      case 0  => s"file://$path"
      case -1 => Paths.get(path).toUri.toASCIIString
      case at =>
        val (dir, pattern) = path.splitAt(at)
        Paths.get(dir).toUri.toASCIIString + pattern

  private def withSlash(root: String): String = if root.endsWith("/") then root else s"$root/"
end CatalogProbe
