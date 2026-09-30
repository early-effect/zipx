package zipx

import sbt.{/, Compile, Def, LocalRootProject, ModuleID, Setting, Test}
import sbt.librarymanagement.syntax.*
import sbt.Keys.{
  baseDirectory,
  crossScalaVersions,
  libraryDependencies,
  localStaging,
  packageDoc,
  projectID,
  publishArtifact,
  publishTo,
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
        Seq(
          // sonaRelease refuses while the root's version is a snapshot, and a root in no row has sbt's default.
          version := zipx.core.Modver
            .rowForProject(thisProject.value.id, zipxShips.value)
            .fold(if baseDirectory.value == (LocalRootProject / baseDirectory).value then "0.0.0"
            else "0.1.0-SNAPSHOT")(
              session.versionOf
            ),
          projectID := zipx.core.Modver
            .rowForProject(thisProject.value.id, zipxShips.value)
            .fold(projectID.value)(row => projectID.value.withRevision(session.publishedRevisionOf(row))),
          // Test resolves packageDoc-scoped keys through Compile before its own publishArtifact, so it is pinned too.
          Compile / packageDoc / publishArtifact := session.publishesDocs && (Compile / publishArtifact).value,
          Test / packageDoc / publishArtifact    := session.publishesDocs && (Test / publishArtifact).value,
          publishTo                              := zipx.core.Modver
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
end ZipxVersions
