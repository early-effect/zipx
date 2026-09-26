package zipx.syntax

import zipx.core.*

/** Reads a catalog file's diff between two commits as `CatalogChange`s.
  *
  * Narrowing needs proof that nothing else moved. The parser sees constructors only, so a group edit such as adding a
  * library to `def service = library(...)` changes which modules get a row without moving any constructor. The last
  * check therefore puts each moved row's base version back into the head text and requires the base file exactly;
  * anything short of that is `CatalogChange.BuildWide`.
  */
object CatalogDiff:

  def between(base: String, head: String, filename: String): List[CatalogChange] =
    (CatalogSource.parse(base, filename), CatalogSource.parse(head, filename)) match
      case (Right(b), Right(h)) => changes(base, b, head, h)
      case _                    => List(CatalogChange.BuildWide(s"$filename does not parse at both commits"))

  private def changes(
      baseText: String,
      base: CatalogConstructors,
      headText: String,
      head: CatalogConstructors,
  ): List[CatalogChange] =
    val baseLibs    = base.libs.map(l => l.coordinate -> l).toMap
    val baseActions = base.actions.map(a => a.name -> a).toMap
    val buildWide   = List(
      Option.when(base.sbt != head.sbt)("the sbt version moved"),
      Option.when(base.scala != head.scala)("the Scala version moved"),
      Option.when(base.plugins.toSet != head.plugins.toSet)("a Plugin row moved"),
      Option.when(base.ships.toSet != head.ships.toSet)("a Ship row moved"),
      Option.when(baseLibs.size != base.libs.size || head.libs.map(_.coordinate).distinct.size != head.libs.size)(
        "a Lib row appears twice"
      ),
      Option.when(baseLibs.keySet != head.libs.map(_.coordinate).toSet)("a Lib row was added or removed"),
      Option.when(baseActions.keySet != head.actions.map(_.name).toSet)("an Action row was added or removed"),
    ).flatten
    buildWide match
      case reason :: _ => List(CatalogChange.BuildWide(reason))
      case Nil         =>
        val movedLibs    = head.libs.filter(l => baseLibs.get(l.coordinate).exists(_.version != l.version))
        val movedActions = head.actions.filter(a => baseActions.get(a.name).exists(_ != a))
        val restored     =
          movedActions.foldLeft(
            movedLibs.foldLeft(headText)((text, l) => restoreLib(text, l, baseLibs(l.coordinate)))
          ) { (text, a) =>
            text.replace(ZipxCatalog.actionConstructor(a), ZipxCatalog.actionConstructor(baseActions(a.name)))
          }
        if restored != baseText then List(CatalogChange.BuildWide("the catalog changed outside a version literal"))
        else
          movedLibs.map(l => CatalogChange.LibMoved(l.coordinate)) ++ movedActions.map(a =>
            CatalogChange.ActionMoved(a.name)
          )
    end match
  end changes

  private def restoreLib(text: String, moved: Lib, base: Lib): String =
    text.replace(
      ZipxCatalog.constructorCall("Lib", moved.group, moved.artifact, moved.version),
      ZipxCatalog.constructorCall("Lib", base.group, base.artifact, base.version),
    )

end CatalogDiff
