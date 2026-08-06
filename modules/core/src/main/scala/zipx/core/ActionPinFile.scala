package zipx.core

import neotype.unwrap
import zipx.workflow.ActionRef
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.matching.Regex

/** Read/write [[ActionPins]] as `.github/zipx/action-pins.yml` (flat `key: owner/action@sha # vX.Y.Z` lines).
  *
  * This is intentionally not a workflow file: it lives outside `.github/workflows/` and is named so Dependabot and
  * humans do not confuse it with generated CI YAML.
  *
  * This is the boundary where a pin becomes an [[zipx.workflow.ActionRef]], so [[parse]] returns an `Either` rather
  * than silently keeping whatever it managed to read. A line it cannot use is a *reported* failure, because the
  * alternative is worse than a missing pin: every field it failed to read would fall back to the jar-baked bootstrap
  * value, silently reverting a pin a repo deliberately held back.
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

  private val byKey: Map[String, ActionPins.Field] =
    ActionPins.Field.values.map(f => f.key -> f).toMap

  private val legalKeys: String = ActionPins.Field.values.map(_.key).mkString(", ")

  /** A blank line or a `#` comment, both of which the committed file legitimately contains. */
  private def isIgnorable(line: String): Boolean =
    val trimmed = line.trim
    trimmed.isEmpty || trimmed.startsWith("#")

  /** Whether `ref` is the action this field pins, whatever it says after the `@`.
    *
    * The prefix must be followed by `@` or nothing, not merely be a prefix: `actions/cache/restore@v4` is a *different*
    * action from `actions/cache`, so a bare `startsWith` would file a subpath action under the wrong pin. Allowing the
    * bare prefix is what lets [[pullFromWorkflow]] see that `uses: actions/checkout` is the checkout pin gone unpinned,
    * rather than an unrelated action to skip.
    */
  private def namesAction(ref: String, field: ActionPins.Field): Boolean =
    ref == field.prefix || ref.startsWith(field.prefix + "@")

  /** Every line must be a pin, a comment, or blank. The four ways a line is rejected, in the order they are checked:
    *
    *   1. it does not have the `key: value` shape at all (a stray indent, a missing value, a two-word version comment)
    *   1. its key is not one of [[ActionPins.Field]] (a typo, or the wrong case)
    *   1. its ref is not a valid [[zipx.workflow.ActionRef]] (unpinned, or an expression)
    *   1. its ref is valid but names a different action than the key does
    *
    * The last is the one a shape check alone would miss: `checkout: evil/malware@<sha>` is a perfectly well-formed
    * action ref, so only the key's own [[ActionPins.Field.prefix]] can tell it is the wrong action. It is the same
    * predicate [[pullFromWorkflow]] uses to decide which field a `uses:` line belongs to.
    *
    * Reports the first failure rather than collecting all of them, as [[zipx.workflow.Render]] does with step problems:
    * the file is small and hand-edited, so the first line named is the line to fix.
    */
  def parse(text: String): Either[String, ActionPins] =
    val pinned = text.linesIterator.zipWithIndex.foldLeft[Either[String, Parsed]](Right(Parsed.empty)) {
      case (Left(error), _)          => Left(error)
      case (Right(acc), (line, idx)) => parseLine(line, idx + 1).map(acc.add)
    }
    pinned.map(_.toPins(ActionPins.Bootstrap, ActionPins.BootstrapVersions))

  /** `None` for a line that carries no pin (blank or comment); a `Left` names the line and why it was refused. */
  private def parseLine(
      line: String,
      lineNo: Int,
  ): Either[String, Option[(ActionPins.Field, ActionRef, Option[String])]] =
    def refuse(reason: String): Either[String, Nothing] =
      Left(s"$DefaultPath:$lineNo: $reason\n  $line")

    if isIgnorable(line) then Right(None)
    else
      line match
        case Line(key, refRaw, ver) =>
          val ref = stripComment(refRaw)
          byKey.get(key) match
            case None        => refuse(s"unknown pin '$key'; expected one of: $legalKeys")
            case Some(field) =>
              ActionRef.make(ref) match
                case Left(error)                          => refuse(error)
                case Right(_) if !namesAction(ref, field) =>
                  refuse(s"pin '$key' must name ${field.prefix}, but this ref is '$ref'")
                case Right(actionRef) =>
                  Right(Some((field, actionRef, Option(ver).filter(_.nonEmpty))))
        case _ =>
          refuse("not a pin, a # comment, or a blank line; expected 'key: owner/action@ref # vX.Y.Z'")
    end if
  end parseLine

  /** Pins read so far, as fields rather than raw keys: an unknown key is refused by [[parseLine]], so by here every key
    * has resolved to a [[ActionPins.Field]] and nothing can be silently dropped later.
    */
  private final case class Parsed(
      refs: Map[ActionPins.Field, ActionRef],
      versions: Map[ActionPins.Field, String],
  ):
    def add(pin: Option[(ActionPins.Field, ActionRef, Option[String])]): Parsed = pin match
      case None                        => this
      case Some((field, ref, version)) =>
        Parsed(
          refs = refs.updated(field, ref),
          versions = version.fold(versions)(v => versions.updated(field, v)),
        )

    /** Layered onto `base`: a field this saw wins, a field it did not keeps whatever `base` had. For [[parse]] that
      * base is the bootstrap pins, so an absent line still yields a usable pin; for [[pullFromWorkflow]] it is the
      * caller's current pins, so a pull only moves what the workflow actually mentioned.
      *
      * A field's version label comes from the same line its ref did. A line with no `# vX.Y.Z` therefore means the
      * label is *unknown*, not whatever the base carried: keeping the base's label would let [[annotateUses]] stamp
      * `# v7.0.1` onto a SHA that is not v7.0.1, which is the same false-assurance defect as an unpinned ref. Only a
      * field the file never mentioned keeps the base's label, since it keeps the base's ref too.
      */
    def toPins(base: ActionPins, baseVersions: Map[String, String]): ActionPins =
      val pins  = refs.foldLeft(base) { case (acc, (field, ref)) => acc.withField(field, ref) }
      val kept  = baseVersions.filterNot { case (key, _) => refs.keys.exists(_.key == key) }
      val found = versions.map { case (field, version) => field.key -> version }
      pins.copy(versions = kept ++ found)
  end Parsed

  private object Parsed:
    val empty: Parsed = Parsed(Map.empty, Map.empty)

  def load(path: Path): Either[String, ActionPins] =
    parse(Files.readString(path, StandardCharsets.UTF_8))

  /** `None` when there is no file, `Some(Left(...))` when there is one that cannot be read as pins. Keeping those
    * distinct is what lets a caller fall back to [[ActionPins.Defaults]] for the former and fail for the latter.
    */
  def loadOption(path: Path): Option[Either[String, ActionPins]] =
    if Files.isRegularFile(path) then Some(load(path)) else None

  /** `None` when the resource is absent, which is how [[ActionPins.Defaults]] falls back to its bootstrap pins.
    *
    * A resource that is present but unparseable is also `None`: it is generated from this repo's own committed pin file
    * by `resourceGenerators`, so a failure here is a zipx build defect, not a user's to report, and the bootstrap pins
    * are a truthful answer either way.
    */
  def loadResource(
      name: String = ResourceName,
      classLoader: ClassLoader = getClass.getClassLoader,
  ): Option[ActionPins] =
    Option(classLoader.getResourceAsStream(name))
      .map { stream =>
        try parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
        finally stream.close()
      }
      .flatMap(_.toOption)

  def render(pins: ActionPins): String =
    val lines = ActionPins.Field.values.toList.map { field =>
      val ref = pins.field(field).unwrap
      pins.version(field) match
        case Some(v) => s"${field.key}: $ref # $v"
        case None    => s"${field.key}: $ref"
    }
    Header + lines.mkString("\n") + "\n"

  def write(path: Path, pins: ActionPins): Unit =
    Option(path.getParent).foreach(p => Files.createDirectories(p))
    Files.writeString(path, render(pins), StandardCharsets.UTF_8)

  /** Pull known action pins from a generated (or Dependabot-edited) workflow YAML.
    *
    * An `Either` for the same reason [[parse]] is: a Dependabot commit that rewrote a `uses:` into something that is
    * not a valid ref is exactly the case a silent pull would launder into the pin file. Only lines whose ref already
    * matches a known [[ActionPins.Field.prefix]] are considered, so an unrelated third-party action in the workflow is
    * ignored rather than refused.
    */
  def pullFromWorkflow(workflowYaml: String, base: ActionPins = ActionPins.Defaults): Either[String, ActionPins] =
    val found = workflowYaml.linesIterator.zipWithIndex.foldLeft[Either[String, Parsed]](Right(Parsed.empty)) {
      case (Left(error), _)          => Left(error)
      case (Right(acc), (line, idx)) =>
        line match
          case UsesLine(refRaw, ver) =>
            val ref = stripComment(refRaw)
            ActionPins.Field.values.find(f => namesAction(ref, f)) match
              case Some(field) if !acc.refs.contains(field) =>
                ActionRef
                  .make(ref)
                  .left
                  .map(error => s"workflow line ${idx + 1}: $error\n  $line")
                  .map(actionRef => acc.add(Some((field, actionRef, Option(ver).filter(_.nonEmpty)))))
              case _ => Right(acc)
          case _ => Right(acc)
    }
    found.map(_.toPins(base, base.versions))
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
            val ref = pins.field(field).unwrap
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

end ActionPinFile
