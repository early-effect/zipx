package zipx.cli

import zipx.core.*
import zipx.syntax.{CatalogApply, CatalogConstructors, CatalogSource, PluginsSbt}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** ZIO-free so tests can drive it. */
object CatalogOps:

  final case class UpdatePlan(
      depBumps: List[DepBump],
      actionBumps: List[ActionBump],
      holds: List[String],
      nextSource: String,
      nextPlugins: String,
      nextProps: Option[String],
  )

  def planUpdate(
      versionsFile: Path,
      preRelease: PreRelease = PreRelease.Skip,
      lookup: ReleaseLookup = MavenMetadata.releases,
      lookupAction: Action => Either[String, Option[(String, String)]] = a =>
        GitHubActionLookup().latest(a.name).map(_.flatMap(r => r.sha.map(sha => (r.tag, sha)))),
  ): Either[String, UpdatePlan] =
    val source = read(versionsFile)
    for
      parsed   <- CatalogSource.parse(source, versionsFile.getFileName.toString)
      crossing <- crossingOf(parsed)
      holds    <- snapshotHolds(parsed.coords, crossing, lookup, preRelease)
      updates  <- ZipxCatalog.outdated(parsed.coords, crossing, lookup, preRelease = preRelease)
      deps = updates.bumps
      acts  <- ZipxCatalog.outdatedActions(parsed.actions, lookupAction)
      next  <- CatalogApply.applyBumps(source, deps)
      next2 <- CatalogApply.applyActionBumps(next, acts)
      plugins = existingPlugins(versionsFile)
      after <- CatalogSource.parse(next2, versionsFile.getFileName.toString)
      merged   = ZipxCatalog.mergePlugins(after.plugins, plugins)
      rendered = ZipxCatalog.renderPlugins(merged)
      props    = after.sbt.map(ZipxCatalog.renderBuildProperties)
    yield UpdatePlan(deps, acts, holds ++ updates.held.map(_.message), next2, rendered, props)
    end for
  end planUpdate

  private def crossingOf(parsed: CatalogConstructors): Either[String, CatalogCrossing] =
    (parsed.scala, parsed.sbt) match
      case (Some(scala), Some(sbt)) => Right(CatalogCrossing.of(scala, sbt))
      case _ => Left("zipx: the catalog states no ScalaVersion or SbtVersion, so its rows cannot be crossed")

  def writeUpdate(versionsFile: Path, plan: UpdatePlan): Either[String, Unit] =
    write(versionsFile, plan.nextSource)
    write(pluginsPath(versionsFile), plan.nextPlugins)
    plan.nextProps.foreach(body => write(propsPath(versionsFile), body))
    Right(())

  def generate(versionsFile: Path): Either[String, Unit] =
    val source = read(versionsFile)
    for
      parsed <- CatalogSource.parse(source, versionsFile.getFileName.toString)
      plugins = existingPlugins(versionsFile)
      merged  = ZipxCatalog.mergePlugins(parsed.plugins, plugins)
      _       = write(pluginsPath(versionsFile), ZipxCatalog.renderPlugins(merged))
      _       = parsed.sbt.foreach(v => write(propsPath(versionsFile), ZipxCatalog.renderBuildProperties(v)))
    yield ()

  def check(versionsFile: Path): Either[String, Unit] =
    val source = read(versionsFile)
    val path   = pluginsPath(versionsFile)
    for
      parsed <- CatalogSource.parse(source, versionsFile.getFileName.toString)
      text = if Files.exists(path) then read(path) else ""
      got <- PluginsSbt.parse(text)
      plugins  = existingPlugins(versionsFile)
      expected = ZipxCatalog.mergePlugins(parsed.plugins, plugins)
      _ <- ZipxCatalog.checkPlugins(path.toString, Right(got), expected)
    yield ()
  end check

  def formatPlan(plan: UpdatePlan): String =
    val deps  = ZipxCatalog.formatBumps(plan.depBumps)
    val acts  = ZipxCatalog.formatActionBumps(plan.actionBumps)
    val holds = if plan.holds.isEmpty then "" else plan.holds.mkString("", "\n", "\n")
    s"$holds$deps\n$acts"

  private def snapshotHolds(
      coords: Seq[ZipxCoord],
      crossing: CatalogCrossing,
      lookup: ReleaseLookup,
      preRelease: PreRelease,
  ): Either[String, List[String]] =
    coords.foldLeft[Either[String, List[String]]](Right(Nil)) { (acc, coord) =>
      acc.flatMap { msgs =>
        if SnapshotPinAdvice.hold(coord.version).isEmpty then Right(msgs)
        else
          CatalogReleases.available(coord, crossing, lookup).map { versions =>
            val latest = ZipxCatalog.latest(versions, preRelease = preRelease)
            msgs ++ SnapshotPinAdvice.message(coord.artifact, coord.version, latest)
          }
      }
    }

  def nothingToDo(plan: UpdatePlan): Boolean =
    plan.depBumps.isEmpty && plan.actionBumps.isEmpty

  private def existingPlugins(versionsFile: Path): List[Plugin] =
    val path = pluginsPath(versionsFile)
    if !Files.exists(path) then Nil
    else PluginsSbt.parse(read(path)).getOrElse(Nil)

  private def pluginsPath(versionsFile: Path): Path =
    sibling(versionsFile, "plugins.sbt")

  private def propsPath(versionsFile: Path): Path =
    sibling(versionsFile, "build.properties")

  private def sibling(file: Path, name: String): Path =
    Option(file.getParent).getOrElse(Path.of(".")).resolve(name)

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def write(path: Path, body: String): Unit =
    Option(path.getParent).foreach(p => Files.createDirectories(p))
    Files.write(path, body.getBytes(StandardCharsets.UTF_8))
    ()
end CatalogOps
