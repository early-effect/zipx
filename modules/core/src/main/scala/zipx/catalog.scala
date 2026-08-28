package zipx

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

export zipx.core.{AsActions, AsPins, AsShips, Action, Pin, PinFeedName, Purl, PublishedRow, Ship, ShipGroup}
