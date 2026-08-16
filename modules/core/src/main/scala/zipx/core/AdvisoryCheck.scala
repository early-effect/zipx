package zipx.core

import neotype.unwrap

/** OSV Package URLs and fail messages for catalog Libs and resolved Action pins. */
object AdvisoryCheck:

  def mavenPurl(lib: Lib): Either[String, Purl] =
    val raw = s"pkg:maven/${lib.group}/${lib.artifact}@${lib.version}"
    Purl.make(raw).left.map(err => s"zipx: Lib ${lib.group}:${lib.artifact} PURL '$raw': $err")

  def githubPurl(name: String, version: String): Either[String, Purl] =
    val repo = name.split('/').take(2).mkString("/")
    val raw  = s"pkg:github/$repo@$version"
    Purl.make(raw).left.map(err => s"zipx: Action $name@$version PURL '$raw': $err")

  def actionVersion(pins: ActionPins, field: ActionPins.Field): String =
    pins.version(field).getOrElse {
      val ref = pins.field(field).unwrap
      ref.split('@').lastOption.getOrElse(ref)
    }

  def actionQueries(pins: ActionPins): List[Either[String, (String, String, Purl)]] =
    val fields = ActionPins.Field.values.toList.map { field =>
      val ver = actionVersion(pins, field)
      githubPurl(field.prefix, ver).map(p => (field.prefix, ver, p))
    }
    val extras = pins.extra.toList.map { (key, ref) =>
      val name = ref.unwrap.takeWhile(_ != '@')
      val ver  = pins.extraVersion(key).getOrElse(ref.unwrap.split('@').lastOption.getOrElse(""))
      githubPurl(name, ver).map(p => (name, ver, p))
    }
    fields ++ extras
  end actionQueries

  def findingMessage(
      kind: String,
      id: String,
      current: String,
      advisory: Advisory,
      update: String,
  ): String =
    val fixed = if advisory.summary.nonEmpty then s" ${advisory.summary}." else ""
    s"zipx: $kind $id@$current: ${advisory.id} (${advisory.severity})$fixed Run $update, then sbt reload and sbt zipxWorkflowGenerate."
end AdvisoryCheck
