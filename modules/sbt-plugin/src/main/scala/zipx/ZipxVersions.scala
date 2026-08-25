package zipx

import sbt.{Def, ModuleID, Setting}
import sbt.Keys.{crossScalaVersions, libraryDependencies, scalaVersion}
import zipx.plugin.ZipxDeps
import zipx.plugin.ZipxPlugin.autoImport.{zipxActionRows, zipxCheckDeps, zipxPins, zipxSbt, zipxScala, zipxVersions}

val ZipxSelf = zipx.plugin.ZipxSelf

/** Catalog a build writes under `project/` and extends. `.sbt` files get plugin autoImport; this package is what those
  * Scala sources import.
  *
  * Row collection (`coords` / `pins` / `actions`) lives on [[ZipxCatalogRows]] in core so a process that is not the
  * target session can compile this file. `settings` / `deps` / `library` stay here because they return sbt types.
  *
  * Drop `MyVersions.settings` at the top of `build.sbt`. Extra settings belong next to that call (`MyVersions.settings
  * ++ …`).
  */
trait ZipxVersions extends ZipxCatalogRows:
  /** Drop at the top of `build.sbt`. Bare `scalaVersion` (sbt 2 common setting, no `ThisBuild`) plus the zipx catalog
    * keys generate and `zipxCheckDeps` read. Inline so [[coords]] / [[pins]] / [[actions]] expand against the concrete
    * object.
    */
  inline def settings: Seq[Setting[?]] = ZipxVersions.applySettings(sbt, scala, coords, pins, actions)

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
  ): Seq[Setting[?]] = Seq(
    scalaVersion   := (scalaVer: String),
    zipxVersions   := rows,
    zipxPins       := pinRows,
    zipxActionRows := actionRows,
    zipxSbt        := Some(sbtVer),
    zipxScala      := Some(scalaVer),
    zipxCheckDeps  := true,
  )
end ZipxVersions
