package zipx.core

import neotype.unwrap
import zipx.workflow.ActionRef
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.matching.Regex

/** Internal YAML codec for [[ActionPins]]: jar resource in, leftover `.github/zipx/action-pins.yml` out as an error,
  * `# vX.Y.Z` stamps on generated `uses:` lines. Flat `key: owner/action@sha # vX.Y.Z` plus an indented `extra:` block.
  *
  * Not an editable source. Catalog [[Action]] rows in ZipxVersions are what you edit.
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
    """# zipx GitHub Action SHA pins (not a workflow). Generated from ZipxVersions Action rows.
      |# Not an editable source. Do not commit this file under .github/zipx/.
      |# Docs: https://www.earlyeffect.rocks/zipx/ (Action pins)
      |""".stripMargin

  private val Line: Regex =
    raw"""^([A-Za-z][A-Za-z0-9_]*)\s*:\s*(\S+?)(?:\s+#\s*(\S+))?\s*$$""".r

  /** The one line that opens the `extra:` block: a bare key with no value. Distinguishable from a `key with no value`
    * rejection only because `extra` is not a [[ActionPins.Field]] key, so nothing else can take this shape legally.
    */
  private val ExtraBlockOpen: Regex = raw"""^${ActionPins.ExtraPrefix}\s*:\s*$$""".r

  /** An indented pin inside the `extra:` block. Keys are catalog Action names (`owner/repo`, with `-` and `/`), not
    * camelCase field keys.
    */
  private val ExtraLine: Regex =
    raw"""^\s+([A-Za-z][A-Za-z0-9_.+/-]*)\s*:\s*(\S+?)(?:\s+#\s*(\S+))?\s*$$""".r

  private val UsesLine: Regex =
    raw"""^\s*-?\s*uses:\s*(\S+?)(?:\s+#\s*(\S+))?\s*$$""".r

  private val byKey: Map[String, ActionPins.Field] =
    ActionPins.Field.values.map(f => f.key -> f).toMap

  private val legalKeys: String =
    (ActionPins.Field.values.map(_.key) :+ s"${ActionPins.ExtraPrefix}:").mkString(", ")

  /** What one line of the file contributed. An enum rather than an `Option[(…)]` because the `extra:` block gives the
    * parser three outcomes beyond a field pin: opening the block, a pin inside it, and nothing at all.
    */
  private enum Entry:
    case Nothing
    case OpenExtra
    case FieldPin(field: ActionPins.Field, ref: ActionRef, version: Option[String])
    case ExtraPin(key: String, ref: ActionRef, version: Option[String])

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
    ActionPins.namesPrefix(ref, field.prefix)

  /** Every line must be a pin, the `extra:` block header, a comment, or blank. The five ways a line is rejected, in the
    * order they are checked:
    *
    *   1. it does not have the `key: value` shape at all (a stray indent, a missing value, a two-word version comment)
    *   1. it is indented but no `extra:` block is open, so its indentation means nothing
    *   1. its key is not one of [[ActionPins.Field]] (a typo, or the wrong case)
    *   1. its ref is not a valid [[zipx.workflow.ActionRef]] (unpinned, or an expression)
    *   1. its ref is valid but names a different action than the key does
    *
    * The last is the one a shape check alone would miss: `checkout: evil/malware@<sha>` is a perfectly well-formed
    * action ref, so only the key's own [[ActionPins.Field.prefix]] can tell it is the wrong action. It is the same
    * predicate [[pullFromWorkflow]] uses to decide which field a `uses:` line belongs to. It is also the check an
    * `extra:` pin cannot get, having no prefix to check against, which is the reason `extra` is a separate block rather
    * than a relaxation of the top-level keys.
    *
    * Reports the first failure rather than collecting all of them, as [[zipx.workflow.Render]] does with step problems:
    * the file is small and hand-edited, so the first line named is the line to fix.
    */
  def parse(text: String): Either[String, ActionPins] =
    val pinned = text.linesIterator.zipWithIndex.foldLeft[Either[String, Parsed]](Right(Parsed.empty)) {
      case (Left(error), _)          => Left(error)
      case (Right(acc), (line, idx)) => parseLine(line, idx + 1, acc.inExtra).map(acc.add)
    }
    pinned.map(_.toPins(ActionPins.Bootstrap, ActionPins.BootstrapVersions))

  /** `inExtra` is what makes indentation meaningful: the same indented line is a pin inside an open `extra:` block and
    * a stray indent outside one. Carried by [[Parsed]] rather than by a separate accumulator so a `Left` short-circuits
    * the whole fold as before.
    */
  private def parseLine(line: String, lineNo: Int, inExtra: Boolean): Either[String, Entry] =
    def refuse(reason: String): Either[String, Nothing] =
      Left(s"$DefaultPath:$lineNo: $reason\n  $line")

    /** Pinned-ness is all an extra key can be checked for: with no prefix there is no action it must name. */
    def extraPin(key: String, refRaw: String, ver: String): Either[String, Entry] =
      val ref = stripComment(refRaw)
      ActionRef.make(ref) match
        case Left(error)      => refuse(error)
        case Right(actionRef) => Right(Entry.ExtraPin(key, actionRef, Option(ver).filter(_.nonEmpty)))

    def fieldPin(key: String, refRaw: String, ver: String): Either[String, Entry] =
      val ref = stripComment(refRaw)
      byKey.get(key) match
        case None        => refuse(s"unknown pin '$key'; expected one of: $legalKeys")
        case Some(field) =>
          ActionRef.make(ref) match
            case Left(error)                          => refuse(error)
            case Right(_) if !namesAction(ref, field) =>
              refuse(s"pin '$key' must name ${field.prefix}, but this ref is '$ref'")
            case Right(actionRef) => Right(Entry.FieldPin(field, actionRef, Option(ver).filter(_.nonEmpty)))
    end fieldPin

    if isIgnorable(line) then Right(Entry.Nothing)
    else
      line match
        case ExtraBlockOpen()                            => Right(Entry.OpenExtra)
        case ExtraLine(key, refRaw, ver) if inExtra      => extraPin(key, refRaw, ver)
        case Line(key, refRaw, ver)                      => fieldPin(key, refRaw, ver)
        case _ if line.headOption.exists(_.isWhitespace) =>
          refuse(s"indented, but no '${ActionPins.ExtraPrefix}:' block is open above it")
        case _ =>
          refuse("not a pin, a # comment, or a blank line; expected 'key: owner/action@ref # vX.Y.Z'")
    end if
  end parseLine

  /** Pins read so far, as fields rather than raw keys where a field exists: an unknown top-level key is refused by
    * [[parseLine]], so by here every one has resolved to a [[ActionPins.Field]] and nothing can be silently dropped
    * later. Extra pins stay keyed by their raw string, which is all they have.
    */
  private final case class Parsed(
      refs: Map[ActionPins.Field, ActionRef],
      versions: Map[ActionPins.Field, String],
      extraRefs: Map[String, ActionRef],
      extraVersions: Map[String, String],
      inExtra: Boolean,
  ):
    def add(entry: Entry): Parsed = entry match
      case Entry.Nothing   => this
      case Entry.OpenExtra => copy(inExtra = true)

      // A top-level pin after the block closes it, so a file that puts `extra:` in the middle still parses the rest.
      case Entry.FieldPin(field, ref, version) =>
        copy(
          refs = refs.updated(field, ref),
          versions = version.fold(versions)(v => versions.updated(field, v)),
          inExtra = false,
        )

      case Entry.ExtraPin(key, ref, version) =>
        copy(
          extraRefs = extraRefs.updated(key, ref),
          extraVersions = version.fold(extraVersions)(v => extraVersions.updated(key, v)),
        )

    /** Layered onto `base`: a field this saw wins, a field it did not keeps whatever `base` had. For [[parse]] that
      * base is the bootstrap pins, so an absent line still yields a usable pin; for [[pullFromWorkflow]] it is the
      * caller's current pins, so a pull only moves what the workflow actually mentioned.
      *
      * A field's version label comes from the same line its ref did. A line with no `# vX.Y.Z` therefore means the
      * label is *unknown*, not whatever the base carried: keeping the base's label would let [[annotateUses]] stamp
      * `# v7.0.1` onto a SHA that is not v7.0.1, which is the same false-assurance defect as an unpinned ref. Only a
      * field the file never mentioned keeps the base's label, since it keeps the base's ref too. Extra pins follow the
      * same rule under their `extra.<key>` labels.
      */
    def toPins(base: ActionPins, baseVersions: Map[String, String]): ActionPins =
      val pins       = refs.foldLeft(base) { case (acc, (field, ref)) => acc.withField(field, ref) }
      val touched    = refs.keys.map(_.key).toSet ++ extraRefs.keys.map(ActionPins.extraVersionKey)
      val kept       = baseVersions.filterNot { case (key, _) => touched.contains(key) }
      val found      = versions.map { case (field, version) => field.key -> version }
      val foundExtra = extraVersions.map { case (key, version) => ActionPins.extraVersionKey(key) -> version }
      pins.copy(versions = kept ++ found ++ foundExtra, extra = base.extra ++ extraRefs)
  end Parsed

  private object Parsed:
    val empty: Parsed = Parsed(Map.empty, Map.empty, Map.empty, Map.empty, inExtra = false)

  def load(path: Path): Either[String, ActionPins] =
    parse(Files.readString(path, StandardCharsets.UTF_8))

  /** `None` when there is no file, `Some(Left(...))` when there is one that cannot be read as pins. Keeping those
    * distinct is what lets a caller fall back to [[ActionPins.Defaults]] for the former and fail for the latter.
    */
  def loadOption(path: Path): Option[Either[String, ActionPins]] =
    if Files.isRegularFile(path) then Some(load(path)) else None

  /** `None` when the resource is absent, which is how [[ActionPins.Defaults]] falls back to its bootstrap pins.
    *
    * A resource that is present but unparseable is also `None`: it is generated from this repo's ZipxVersions Action
    * rows by `resourceGenerators`, so a failure here is a zipx build defect, not a user's to report, and the bootstrap
    * pins are a truthful answer either way.
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

  /** `extra` is emitted last and sorted by key, since a `Map` has no order of its own and the file is committed: an
    * iteration-order-dependent render would produce a spurious diff on every generate.
    */
  def render(pins: ActionPins): String =
    def pin(key: String, ref: String, version: Option[String], indent: String): String =
      version match
        case Some(v) => s"$indent$key: $ref # $v"
        case None    => s"$indent$key: $ref"

    val fields = ActionPins.Field.values.toList.map { field =>
      pin(field.key, pins.field(field).unwrap, pins.version(field), "")
    }
    val extra =
      if pins.extra.isEmpty then Nil
      else
        s"${ActionPins.ExtraPrefix}:" +: pins.extra.toList.sortBy(_._1).map { case (key, ref) =>
          pin(key, ref.unwrap, pins.extraVersion(key), "  ")
        }
    Header + (fields ++ extra).mkString("\n") + "\n"
  end render

  def write(path: Path, pins: ActionPins): Unit =
    Option(path.getParent).foreach(p => Files.createDirectories(p))
    Files.writeString(path, render(pins), StandardCharsets.UTF_8)

  /** Pull known action pins from a generated (or Dependabot-edited) workflow YAML.
    *
    * An `Either` for the same reason [[parse]] is: a Dependabot commit that rewrote a `uses:` into something that is
    * not a valid ref is exactly the case a silent pull would launder into the pin file. Only lines whose ref already
    * matches a known [[ActionPins.Field.prefix]], or the action of an extra pin `base` already carries, are considered,
    * so an unrelated third-party action in the workflow is ignored rather than refused.
    *
    * An extra pin has no prefix, so a *new* extra action cannot be recognised here: pinning one is a deliberate act by
    * whoever wrote the step, and inventing a key for it would be a guess. Once the key exists, Dependabot bumps flow
    * through like any other pin.
    */
  def pullFromWorkflow(workflowYaml: String, base: ActionPins = ActionPins.Defaults): Either[String, ActionPins] =
    /** The action an extra pin names, `owner/action`, which is the only handle a keyed pin gives for matching. */
    val extraByAction: Map[String, String] =
      base.extra.map { case (key, ref) => actionOf(ref.unwrap) -> key }

    val found = workflowYaml.linesIterator.zipWithIndex.foldLeft[Either[String, Parsed]](Right(Parsed.empty)) {
      case (Left(error), _)          => Left(error)
      case (Right(acc), (line, idx)) =>
        def typed(ref: String, ver: String): Option[Either[String, Parsed]] =
          ActionPins.Field.values.find(f => namesAction(ref, f)).collect {
            case field if !acc.refs.contains(field) =>
              ActionRef
                .make(ref)
                .left
                .map(error => s"workflow line ${idx + 1}: $error\n  $line")
                .map(actionRef => acc.add(Entry.FieldPin(field, actionRef, Option(ver).filter(_.nonEmpty))))
          }

        def keyed(ref: String, ver: String): Option[Either[String, Parsed]] =
          extraByAction.get(actionOf(ref)).collect {
            case key if !acc.extraRefs.contains(key) =>
              ActionRef
                .make(ref)
                .left
                .map(error => s"workflow line ${idx + 1}: $error\n  $line")
                .map(actionRef => acc.add(Entry.ExtraPin(key, actionRef, Option(ver).filter(_.nonEmpty))))
          }

        line match
          case UsesLine(refRaw, ver) =>
            val ref = stripComment(refRaw)
            typed(ref, ver).orElse(keyed(ref, ver)).getOrElse(Right(acc))
          case _ => Right(acc)
    }
    found.map(_.toPins(base, base.versions))
  end pullFromWorkflow

  /** `owner/action` from a ref, dropping whatever follows the `@`. Refs with no `@` (an unpinned `uses:`) come back
    * unchanged, so a matched-but-unpinned line still reaches `ActionRef.make` and is refused there.
    */
  private def actionOf(ref: String): String = ref.takeWhile(_ != '@')

  /** Append `# vX.Y.Z` comments to `uses:` lines for pins that carry a version label.
    *
    * Matching is by exact ref rather than by prefix, so an extra pin needs no prefix to be annotated: the ref written
    * into the step and the ref in the pin file are the same string or the pin does not apply.
    */
  def annotateUses(yaml: String, pins: ActionPins): String =
    val labelled: List[(String, String)] =
      ActionPins.Field.values.toList.flatMap(f => pins.version(f).map(pins.field(f).unwrap -> _)) ++
        pins.extra.toList.flatMap { case (key, ref) => pins.extraVersion(key).map(ref.unwrap -> _) }

    val trailingNl = yaml.endsWith("\n")
    val annotated  = yaml.linesIterator.toList.map { line =>
      val trimmed = line.trim
      labelled.foldLeft(line) { case (current, (ref, ver)) =>
        if (trimmed == s"uses: $ref" || trimmed == s"- uses: $ref") && !current.contains("#") then current + s" # $ver"
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
