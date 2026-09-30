package zipx.plugin

import sbt.State
import zipx.core.{SnapshotPins, ZipxCoord}

/** sbt keeps every dependency resolution for the life of the JVM (sbt/sbt#6512), so in a long-lived shell a republished
  * snapshot's new dependencies stay invisible even with a forced `update`. While a snapshot is pinned, `reload` and
  * `clean` forget them.
  */
private[plugin] object ResolutionCache:

  def forgetIfPinned(coords: Seq[ZipxCoord]): Unit =
    if SnapshotPins.of(coords).nonEmpty then lmcoursier.internal.SbtCoursierCache.default.clear()

  def forgetOnUnload(pinned: State => Seq[ZipxCoord]): State => State = s =>
    forgetIfPinned(pinned(s))
    s
