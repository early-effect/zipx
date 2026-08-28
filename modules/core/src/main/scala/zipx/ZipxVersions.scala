package zipx

/** Catalog surface a process that is not the target sbt can compile: `sbt` / `scala` / [[coords]] / [[pins]] /
  * [[actions]] / [[ships]]. No sbt types.
  *
  * Consumer files still extend [[ZipxVersions]] from the plugin, which adds `settings` / `deps` / `library`. The CLI
  * compiles that file against zipx jars on *its* classpath, not against the target `plugins.sbt`.
  */
trait Catalog:
  def sbt: SbtVersion
  def scala: ScalaVersion

  /** Scala versions a cross-built module compiles. Default is only [[scala]]. Override for 2.13 + 3. */
  def crossScala: Seq[ScalaVersion] = Seq(scala)

  /** Every val on this object whose type has an [[AsCoords]] given (`Lib`, `Plugin`, or a plugin bundle). */
  inline def coords: Seq[ZipxCoord] = zipx.core.ZipxCatalog.coordsOf[this.type](this)

  /** Every val on this object whose type has an [[AsPins]] given. */
  inline def pins: Seq[Pin] = zipx.core.ZipxCatalog.pinsOf[this.type](this)

  /** Every val on this object whose type has an [[AsActions]] given. */
  inline def actions: Seq[Action] = zipx.core.ZipxCatalog.actionsOf[this.type](this)

  /** Every val on this object whose type has an [[AsShips]] given (`Ship`, `ShipGroup`, or a bundle). */
  inline def ships: Seq[PublishedRow] = zipx.core.ZipxCatalog.shipsOf[this.type](this)
end Catalog
