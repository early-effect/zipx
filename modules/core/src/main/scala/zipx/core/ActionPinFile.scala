package zipx.core

import neotype.unwrap
import zipx.workflow.ActionRef
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.matching.Regex

/** Internal YAML codec for [[ActionPins]], not an editable source: catalog [[Action]] rows in ZipxVersions are.
  *
  * An unusable line is a reported failure, not skipped: a skipped field would fall back to the bootstrap pin and
  * silently revert a pin the repo deliberately held back.
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

  /** Unambiguous only because `extra` is not an [[ActionPins.Field]] key. */
  private val ExtraBlockOpen: Regex = raw"""^${ActionPins.ExtraPrefix}\s*:\s*$$""".r

  /** Keys are catalog Action names (`owner/repo`), not camelCase field keys. */
  private val ExtraLine: Regex =
    raw"""^\s+([A-Za-z][A-Za-z0-9_.+/-]*)\s*:\s*(\S+?)(?:\s+#\s*(\S+))?\s*$$""".r

  private val UsesLine: Regex =
    raw"""^\s*-?\s*uses:\s*(\S+?)(?:\s+#\s*(\S+))?\s*$$""".r

  private val byKey: Map[String, ActionPins.Field] =
    ActionPins.Field.values.map(f => f.key -> f).toMap

  private val legalKeys: String =
    (ActionPins.Field.values.map(_.key) :+ s"${ActionPins.ExtraPrefix}:").mkString(", ")

  private enum Entry:
    case Nothing
    case OpenExtra
    case FieldPin(field: ActionPins.Field, ref: ActionRef, version: Option[String])
    case ExtraPin(key: String, ref: ActionRef, version: Option[String])

  private def isIgnorable(line: String): Boolean =
    val trimmed = line.trim
    trimmed.isEmpty || trimmed.startsWith("#")

  private def namesAction(ref: String, field: ActionPins.Field): Boolean =
    ActionPins.namesPrefix(ref, field.prefix)

  /** A well-formed ref under the wrong key (`checkout: evil/malware@<sha>`) is refused by the field's prefix. `extra:`
    * pins have no prefix to check, which is why they live in a separate block.
    */
  def parse(text: String): Either[String, ActionPins] =
    val pinned = text.linesIterator.zipWithIndex.foldLeft[Either[String, Parsed]](Right(Parsed.empty)) {
      case (Left(error), _)          => Left(error)
      case (Right(acc), (line, idx)) => parseLine(line, idx + 1, acc.inExtra).map(acc.add)
    }
    pinned.map(_.toPins(ActionPins.Bootstrap, ActionPins.BootstrapVersions))

  private def parseLine(line: String, lineNo: Int, inExtra: Boolean): Either[String, Entry] =
    def refuse(reason: String): Either[String, Nothing] =
      Left(s"$DefaultPath:$lineNo: $reason\n  $line")

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

    /** Seen pins override `base`; unseen ones keep base's ref and label. A seen pin with no `# vX.Y.Z` drops the label:
      * keeping base's would let [[annotateUses]] stamp a version onto a SHA that is not that version.
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

  def loadOption(path: Path): Option[Either[String, ActionPins]] =
    if Files.isRegularFile(path) then Some(load(path)) else None

  /** Unparseable is also `None`: the resource is generated from zipx's own Action rows, so a failure is a zipx build
    * defect and the bootstrap pins are still a truthful answer.
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
    * Only `uses:` lines naming a field prefix, or the action of an extra pin `base` already carries, are read; other
    * actions are ignored. A new extra action is never inferred, since it has no key to file it under.
    */
  def pullFromWorkflow(workflowYaml: String, base: ActionPins = ActionPins.Defaults): Either[String, ActionPins] =
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

  /** A ref with no `@` comes back whole, so an unpinned `uses:` still reaches `ActionRef.make` and is refused. */
  private def actionOf(ref: String): String = ref.takeWhile(_ != '@')

  /** Appends `# vX.Y.Z` to `uses:` lines whose exact ref has a version label. */
  def annotateUses(yaml: String, pins: ActionPins): String =
    val labelled: List[(String, String)] =
      ActionPins.Field.values.toList.flatMap(f => pins.version(f).map(pins.field(f).unwrap -> _)) ++
        pins.version(ActionPins.Field.Cache).map(pins.cacheRestore.unwrap -> _).toList ++
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
