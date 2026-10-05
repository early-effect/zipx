package zipx.plugin

import sbt.State
import zipx.core.{SnapshotPins, ZipxCoord}

/** sbt caches every resolution for the JVM's life (sbt/sbt#6512), so a republished snapshot's new dependencies stay
  * invisible even to a forced `update`. While a snapshot is pinned, `reload` and `clean` clear that cache.
  */
private[plugin] object ResolutionCache:

  def forgetIfPinned(coords: Seq[ZipxCoord]): Unit =
    if SnapshotPins.of(coords).nonEmpty then lmcoursier.internal.SbtCoursierCache.default.clear()

  def forgetOnUnload(pinned: State => Seq[ZipxCoord]): State => State = s =>
    forgetIfPinned(pinned(s))
    s
