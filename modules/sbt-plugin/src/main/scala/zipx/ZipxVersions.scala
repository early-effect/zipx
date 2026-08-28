package zipx

import sbt.{Def, ModuleID, Setting}
import sbt.Keys.{crossScalaVersions, libraryDependencies, pomPostProcess, scalaVersion, thisProject, version}
import zipx.plugin.ZipxDeps
import zipx.plugin.ZipxPlugin.autoImport.{
  zipxActionRows,
  zipxCheckDeps,
  zipxPins,
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
        Seq(
          version := Def.uncached {
            val id = thisProject.value.id
            zipx.core.Modver.rowForProject(id, shipRows) match
              case Some(pub) => s"${pub.version}-ci"
              case None      => "0.1.0-SNAPSHOT"
          },
          pomPostProcess := { (node: scala.xml.Node) => stripCiPomVersions(node) },
        )
    catalog ++ versions
  end applySettings

  /** Never emit `-ci` into a POM. Sibling `dependsOn` revisions become catalog release numbers. */
  private def stripCiPomVersions(node: scala.xml.Node): scala.xml.Node =
    node match
      case e: scala.xml.Elem if e.label == "version" && e.text.endsWith("-ci") =>
        e.copy(child = Seq(scala.xml.Text(e.text.dropRight(3))))
      case e: scala.xml.Elem => e.copy(child = e.child.map(stripCiPomVersions))
      case other             => other
end ZipxVersions
