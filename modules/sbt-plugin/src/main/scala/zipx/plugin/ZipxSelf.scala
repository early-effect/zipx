package zipx.plugin

import zipx.core.Plugin

/** Paved self-emit for an sbt plugin that sits on zipx. Throws at this boundary when group, artifact, or version cannot
  * be determined.
  */
object ZipxSelf:

  def emit(group: String, artifact: String, from: Class[?], version: Option[String] = None): Plugin =
    zipx.core.ZipxSelf.plugin(group, artifact, from, version).fold(msg => sys.error(msg), identity)
