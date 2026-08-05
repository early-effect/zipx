package zipx.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.matching.Regex

/** Read/write [[ActionPins]] as `.github/zipx/action-pins.yml` (flat `key: owner/action@sha # vX.Y.Z` lines).
  *
  * This is intentionally not a workflow file: it lives outside `.github/workflows/` and is named so Dependabot and
  * humans do not confuse it with generated CI YAML.
  */
object ActionPinFile:

  val DefaultPath: String = ".github/zipx/action-pins.yml"

  val ResourceName: String = "zipx/action-pins.yml"

  private val Header: String =
    """# zipx GitHub Action SHA pins (not a workflow).
      |# Source of truth for generated `uses:` refs. Prefer Dependabot + `sbt zipxActionsPull`
      |# (or the zipx-action-pins-sync workflow) over editing by hand.
      |# Docs: https://www.earlyeffect.rocks/zipx/ (Action pins)
      |""".stripMargin

  private val Line: Regex =
    raw"""^([A-Za-z][A-Za-z0-9_]*)\s*:\s*(\S+?)(?:\s+#\s*(\S+))?\s*$$""".r

  private val UsesLine: Regex =
    raw"""^\s*-?\s*uses:\s*(\S+?)(?:\s+#\s*(\S+))?\s*$$""".r

  def parse(text: String): ActionPins =
    val values   = scala.collection.mutable.Map.empty[String, String]
    val versions = scala.collection.mutable.Map.empty[String, String]
    text.linesIterator.foreach {
      case Line(key, ref, ver) =>
        values(key) = stripComment(ref)
        if ver != null && ver.nonEmpty then versions(key) = ver
      case _ => ()
    }
    fromMaps(values.toMap, versions.toMap)
  end parse

  def load(path: Path): ActionPins =
    parse(Files.readString(path, StandardCharsets.UTF_8))

  def loadOption(path: Path): Option[ActionPins] =
    if Files.isRegularFile(path) then Some(load(path)) else None

  /** `None` when the resource is absent, which is how [[ActionPins.Defaults]] falls back to its bootstrap pins. */
  def loadResource(
      name: String = ResourceName,
      classLoader: ClassLoader = getClass.getClassLoader,
  ): Option[ActionPins] =
    Option(classLoader.getResourceAsStream(name)).map { stream =>
      try parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
      finally stream.close()
    }

  def render(pins: ActionPins): String =
    val lines = ActionPins.Field.values.toList.map { field =>
      val ref = pins.field(field)
      pins.version(field) match
        case Some(v) => s"${field.key}: $ref # $v"
        case None    => s"${field.key}: $ref"
    }
    Header + lines.mkString("\n") + "\n"

  def write(path: Path, pins: ActionPins): Unit =
    Option(path.getParent).foreach(p => Files.createDirectories(p))
    Files.writeString(path, render(pins), StandardCharsets.UTF_8)

  /** Pull known action pins from a generated (or Dependabot-edited) workflow YAML. */
  def pullFromWorkflow(workflowYaml: String, base: ActionPins = ActionPins.Defaults): ActionPins =
    val foundRef = scala.collection.mutable.Map.empty[ActionPins.Field, String]
    val foundVer = scala.collection.mutable.Map.empty[String, String]
    workflowYaml.linesIterator.foreach {
      case UsesLine(refRaw, ver) =>
        val ref = stripComment(refRaw)
        ActionPins.Field.values.foreach { field =>
          if ref.startsWith(field.prefix + "@") && !foundRef.contains(field) then
            foundRef(field) = ref
            if ver != null && ver.nonEmpty then foundVer(field.key) = ver
        }
      case _ => ()
    }
    val pulled = foundRef.foldLeft(base) { case (pins, (field, ref)) => pins.withField(field, ref) }
    pulled.copy(versions = base.versions ++ foundVer.toMap)
  end pullFromWorkflow

  /** Append `# vX.Y.Z` comments to `uses:` lines for pins that carry a version label. */
  def annotateUses(yaml: String, pins: ActionPins): String =
    val trailingNl = yaml.endsWith("\n")
    val lines      = yaml.linesIterator.toList
    val annotated  = lines.map { line =>
      val trimmed = line.trim
      ActionPins.Field.values.foldLeft(line) { (current, field) =>
        pins.version(field) match
          case None      => current
          case Some(ver) =>
            val ref = pins.field(field)
            if (trimmed == s"uses: $ref" || trimmed == s"- uses: $ref") && !current.contains("#") then
              current + s" # $ver"
            else current
      }
    }
    val body = annotated.mkString("\n")
    if trailingNl then body + "\n" else body
  end annotateUses

  private def stripComment(ref: String): String =
    val idx = ref.indexOf('#')
    if idx < 0 then ref.trim else ref.substring(0, idx).trim

  private def fromMaps(values: Map[String, String], versions: Map[String, String]): ActionPins =
    val pins = ActionPins.Field.values.foldLeft(ActionPins.Bootstrap) { (acc, field) =>
      values.get(field.key).fold(acc)(acc.withField(field, _))
    }
    pins.copy(versions = ActionPins.BootstrapVersions ++ versions)

end ActionPinFile
