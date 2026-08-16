package zipx

import sbt.{Def, ModuleID, Setting}
import sbt.Keys.{crossScalaVersions, libraryDependencies, scalaVersion}
import zipx.plugin.ZipxDeps
import zipx.plugin.ZipxPlugin.autoImport.{zipxCheckDeps, zipxSbt, zipxScala, zipxVersions}

type ZipxCoord = zipx.core.ZipxCoord
type Lib       = zipx.core.Lib
val Lib = zipx.core.Lib
type Plugin = zipx.core.Plugin
val Plugin = zipx.core.Plugin
type Cross = zipx.core.Cross
val Cross = zipx.core.Cross
type SbtVersion = zipx.core.SbtVersion
val SbtVersion = zipx.core.SbtVersion
type ScalaVersion = zipx.core.ScalaVersion
val ScalaVersion = zipx.core.ScalaVersion
type ZipxExclude = zipx.core.ZipxExclude
val ZipxExclude = zipx.core.ZipxExclude
type AsCoords[A] = zipx.core.AsCoords[A]
val AsCoords = zipx.core.AsCoords
val ZipxSelf = zipx.plugin.ZipxSelf

/** Catalog a build writes under `project/` and extends. `.sbt` files get plugin autoImport; this package is what those
  * Scala sources import.
  *
  * Drop `MyVersions.settings` at the top of `build.sbt` for `scalaVersion` plus the zipx catalog keys. Use
  * `MyVersions.cross` on 2.13+3 modules. Per-module deps are named groups on your object (`def service = library(zio,
  * slf4j)`), not a zipx service type. Every `Lib` / `Plugin` val is a catalog row. A plugin bundle uses the same
  * [[AsCoords]] given.
  */
trait ZipxVersions:
  def sbt: SbtVersion
  def scala: ScalaVersion

  /** Scala versions a cross-built module compiles. Default is only [[scala]]. Override for 2.13 + 3. */
  def crossScala: Seq[ScalaVersion] = Seq(scala)

  /** Every val on this object whose type has an [[AsCoords]] given (`Lib`, `Plugin`, or a plugin bundle). */
  inline def coords: Seq[ZipxCoord] = zipx.core.ZipxCatalog.coordsOf[this.type](this)

  /** Drop at the top of `build.sbt`. Bare `scalaVersion` (sbt 2 common setting, no `ThisBuild`) plus the zipx catalog
    * keys generate and `zipxCheckDeps` read. Inline so [[coords]] expands against the concrete object. Override with
    * `inline override def settings = super.settings ++ …`.
    */
  inline def settings: Seq[Setting[?]] = zipx.ZipxVersions.applySettings(sbt, scala, coords)

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
  def applySettings(sbtVer: SbtVersion, scalaVer: ScalaVersion, rows: Seq[ZipxCoord]): Seq[Setting[?]] = Seq(
    scalaVersion  := (scalaVer: String),
    zipxVersions  := rows,
    zipxSbt       := Some(sbtVer),
    zipxScala     := Some(scalaVer),
    zipxCheckDeps := true,
  )
end ZipxVersions
