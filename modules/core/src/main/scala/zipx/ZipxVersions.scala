package zipx

/** Collected where the catalog object compiles: no caller's compile cache can hold a stale row list. Read lazily, after
  * the object's vals are set.
  */
final class CatalogContents(read: () => CatalogContents.Rows):
  lazy val rows: CatalogContents.Rows = read()

object CatalogContents:
  final case class Rows(coords: Seq[ZipxCoord], pins: Seq[Pin], actions: Seq[Action], ships: Seq[PublishedRow])

  inline given CatalogContents = ${ zipx.core.ZipxCatalog.contentsImpl }

/** No sbt types, so the CLI compiles a catalog without the target build. Catalogs extend the plugin's `ZipxVersions`.
  */
trait Catalog(using contents: CatalogContents):
  def sbt: SbtVersion
  def scala: ScalaVersion

  def crossScala: Seq[ScalaVersion] = Seq(scala)

  def coords: Seq[ZipxCoord]   = contents.rows.coords
  def pins: Seq[Pin]           = contents.rows.pins
  def actions: Seq[Action]     = contents.rows.actions
  def ships: Seq[PublishedRow] = contents.rows.ships
end Catalog
