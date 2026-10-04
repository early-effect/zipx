package zipx

import scala.xml.{Elem, Node}
import sbt.{/, Compile, Def, LocalRootProject, ModuleID, Setting, Test}
import sbt.librarymanagement.syntax.*
import sbt.Keys.{
  baseDirectory,
  crossScalaVersions,
  libraryDependencies,
  organization,
  isSnapshot,
  localStaging,
  packageDoc,
  pomPostProcess,
  projectID,
  publishArtifact,
  publishTo,
  sonaDeploymentName,
  scalaVersion,
  thisProject,
  version,
}
import zipx.plugin.ZipxDeps
import zipx.plugin.ZipxPlugin.autoImport.{
  zipxActionRows,
  zipxCheckDeps,
  zipxPins,
  zipxReleaseWorkflow,
  zipxSbt,
  zipxScala,
  zipxShips,
  zipxVersions,
}

val ZipxSelf = zipx.plugin.ZipxSelf

/** Catalog a build writes under `project/` and extends. `.sbt` files get plugin autoImport; this package is what those
  * Scala sources import.
  *
  * Row collection (`coords` / `pins` / `actions` / `ships`) lives on [[Catalog]] in core so a process that is not the
  * target session can compile this file. `settings` / `deps` / `library` stay here because they return sbt types.
  *
  * Drop `MyVersions.settings` at the top of `build.sbt`. Extra settings belong next to that call (`MyVersions.settings
  * ++ …`).
  */
trait ZipxVersions extends Catalog:
  /** Drop at the top of `build.sbt`. Bare `scalaVersion` (sbt 2 common setting, no `ThisBuild`) plus the zipx catalog
    * keys generate and `zipxCheckDeps` read. Inline so [[coords]] / [[pins]] / [[actions]] expand against the concrete
    * object.
    */
  inline def settings: Seq[Setting[?]] = ZipxVersions.applySettings(sbt, scala, coords, pins, actions, ships)

  /** Per-module `crossScalaVersions` from [[crossScala]]. Scala-3-only modules inherit [[settings]] and skip this. */
  def cross: Seq[Setting[?]] = Seq(
    crossScalaVersions := crossScala.map(v => v: String)
  )

  def deps(libs: Lib*): Seq[ModuleID] = ZipxDeps(libs*)

  def library(libs: Lib*): Seq[Setting[?]] =
    val selected = libs.toSeq
    Seq(libraryDependencies ++= Def.uncached(ZipxDeps(selected*)))

  def moduleID(lib: Lib): ModuleID       = ZipxDeps.moduleID(lib)
  def moduleID(plugin: Plugin): ModuleID = ZipxDeps.moduleID(plugin)
end ZipxVersions

object ZipxVersions:
  def applySettings(
      sbtVer: SbtVersion,
      scalaVer: ScalaVersion,
      rows: Seq[ZipxCoord],
      pinRows: Seq[Pin] = Nil,
      actionRows: Seq[Action] = Nil,
      shipRows: Seq[PublishedRow] = Nil,
  ): Seq[Setting[?]] =
    val catalog = Seq(
      scalaVersion   := (scalaVer: String),
      zipxVersions   := rows,
      zipxPins       := pinRows,
      zipxActionRows := actionRows,
      zipxShips      := shipRows,
      zipxSbt        := Some(sbtVer),
      zipxScala      := Some(scalaVer),
      zipxCheckDeps  := true,
    )
    val versions =
      if shipRows.isEmpty then Nil
      else
        def session =
          zipx.core.BuildSession.of(sys.props).fold(err => sys.error(s"zipx: ${err.message}"), identity)
        def registryOf(workflow: Option[zipx.core.ReleaseWorkflow]): zipx.core.ArtifactRegistry =
          workflow.map(_.registry).getOrElse(zipx.core.ArtifactRegistry.Url("file:///tmp/zipx-none"))
        def artifact(row: zipx.core.PublishedRow, registry: zipx.core.ArtifactRegistry) =
          session
            .artifactVersion(row, registry, sys.props)
            .fold(err => sys.error(s"zipx: ${err.message}"), identity)
        Seq(
          // sonaRelease refuses while the root's version is a snapshot, and a root in no row has sbt's default.
          // `.value` stays in this block: a local def hides it from the setting macro.
          version := {
            val registry = registryOf((LocalRootProject / zipxReleaseWorkflow).value)
            zipx.core.Modver
              .rowForProject(thisProject.value.id, zipxShips.value)
              .fold(if baseDirectory.value == (LocalRootProject / baseDirectory).value then "0.0.0"
              else "0.1.0-SNAPSHOT")(row => artifact(row, registry))
          },
          isSnapshot := zipx.core.Modver
            .rowForProject(thisProject.value.id, zipxShips.value)
            .fold(isSnapshot.value)(_ => session != zipx.core.BuildSession.Release),
          projectID := {
            val registry = registryOf((LocalRootProject / zipxReleaseWorkflow).value)
            zipx.core.Modver
              .rowForProject(thisProject.value.id, zipxShips.value)
              .fold(projectID.value)(row => projectID.value.withRevision(artifact(row, registry)))
          },
          pomPostProcess := {
            val previous = pomPostProcess.value
            node =>
              val base = previous(node)
              sys.props.get(zipx.core.SnapshotPublishRevision.ShaProperty) match
                case Some(sha) if sys.props.get(zipx.core.SnapshotPublishRevision.PointerProperty).contains("true") =>
                  ZipxVersions.withSnapshotSha(base, sha)
                case _ => base
          },
          // Test resolves packageDoc-scoped keys through Compile before its own publishArtifact, so it is pinned too.
          Compile / packageDoc / publishArtifact := session.publishesDocs && (Compile / publishArtifact).value,
          Test / packageDoc / publishArtifact    := session.publishesDocs && (Test / publishArtifact).value,
          sonaDeploymentName                     := {
            (session, sys.props.get(zipx.core.BuildSession.ReleaseNameProperty)) match
              case (zipx.core.BuildSession.Release, Some(rows)) => s"${organization.value} $rows"
              case _                                            => sonaDeploymentName.value
          },
          publishTo := zipx.core.Modver
            .rowForProject(thisProject.value.id, zipxShips.value)
            .flatMap(_ => (LocalRootProject / zipxReleaseWorkflow).value.map(_.registry))
            .fold(publishTo.value)(registry =>
              session match
                case zipx.core.BuildSession.Development => publishTo.value
                case zipx.core.BuildSession.Release     =>
                  registry.releaseRepository.fold(localStaging.value)(url => Some("zipx-release" at url))
                case _ => Some("zipx-snapshots" at registry.snapshotRepository)
            ),
        )
    catalog ++ versions
  end applySettings

  /** The pointer POM records the full sha beside the `<line>-SNAPSHOT` coordinate. One `<properties>` element. */
  private[zipx] def withSnapshotSha(node: Node, sha: String): Node =
    node match
      case project: Elem if project.label == "project" =>
        val shaElem          = <zipx.snapshot.sha>{sha}</zipx.snapshot.sha>
        val (seen, children) = project.child.foldLeft((false, Seq.empty[Node])) {
          case ((seen, acc), elem: Elem) if elem.label == "properties" && !seen =>
            (true, acc :+ elem.copy(child = elem.child ++ shaElem))
          case ((seen, acc), child) =>
            (seen, acc :+ child)
        }
        val withSha = if seen then children else children :+ <properties>{shaElem}</properties>
        project.copy(child = withSha)
      case other => other
end ZipxVersions
