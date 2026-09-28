package zipx

import sbt.{Def, LocalRootProject, ModuleID, Setting}
import sbt.Keys.{
  baseDirectory,
  crossScalaVersions,
  libraryDependencies,
  organization,
  pomPostProcess,
  scalaVersion,
  thisProject,
  version,
}
import zipx.plugin.ZipxDeps
import zipx.plugin.ZipxPlugin.autoImport.{
  zipxActionRows,
  zipxCheckDeps,
  zipxModverUnreleased,
  zipxPins,
  zipxPushBranches,
  zipxSbt,
  zipxScala,
  zipxShips,
  zipxVersions,
  zipxVersionsFile,
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
        Seq(
          version := Def.uncached {
            zipx.plugin.ModverRelease.versionString(
              thisProject.value.id,
              zipxShips.value,
              zipxPushBranches.value,
              zipxVersionsFile.value,
              (LocalRootProject / baseDirectory).value,
              sys.env,
              zipxModverUnreleased.value,
            )
          },
          pomPostProcess := {
            val ships = zipxShips.value
            val org   = organization.value
            (node: scala.xml.Node) => releasedPomVersions(node, org, ships)
          },
        )
    catalog ++ versions
  end applySettings

  /** A POM goes to a registry, and only CI publishes there, naming an unreleased sibling `<row>-ci`. Each such revision
    * in this build's organization (the POM's own `<version>` and each in-repo dependency) takes its row's catalog
    * number. Everything else stays as written: another organization's `-ci`, and any `-SNAPSHOT`, since a local build's
    * POM names what it built.
    */
  private def releasedPomVersions(node: scala.xml.Node, org: String, ships: Seq[PublishedRow]): scala.xml.Node =
    def child(e: scala.xml.Elem, label: String): Option[String] =
      e.child.collectFirst { case c: scala.xml.Elem if c.label == label => c.text }
    def released(e: scala.xml.Elem): scala.xml.Elem =
      child(e, "groupId").fold(e) { groupId =>
        e.copy(child = e.child.map {
          case v: scala.xml.Elem if v.label == "version" =>
            v.copy(child = Seq(scala.xml.Text(zipx.core.Modver.releasedRevision(groupId, v.text, org, ships))))
          case other => other
        })
      }
    def walk(n: scala.xml.Node): scala.xml.Node =
      n match
        case e: scala.xml.Elem if e.label == "dependency" => released(e)
        case e: scala.xml.Elem                            => e.copy(child = e.child.map(walk))
        case other                                        => other
    node match
      // The project's own coordinates sit on the root element, beside its dependencies.
      case e: scala.xml.Elem if e.label == "project" => walk(released(e))
      case other                                     => walk(other)
  end releasedPomVersions
end ZipxVersions
