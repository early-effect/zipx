package zipx.workflow

import zio.blocks.chunk.Chunk
import zio.blocks.schema.yaml.{Yaml, YamlTag}

/** Not zio-blocks' `YamlWriter`, which escapes a multi-line value into one quoted scalar that `actions/cache` reads as
  * a single literal path; GitHub needs a block scalar there. Layout and quoting otherwise mirror `YamlWriter`, so
  * single-line output stays byte-identical.
  */
object YamlPrinter:
  private val indentStep = 2

  def print(yaml: Yaml): String =
    val sb = new java.lang.StringBuilder
    writeNode(sb, yaml, 0, isTopLevel = true)
    sb.toString

  /** A pre-pass so [[print]] stays total without threading an `Either` through the writer. See `writeBlockScalar` for
    * why these cases are fatal rather than merely ugly.
    */
  def problem(yaml: Yaml): Option[String] = yaml match
    case Yaml.Mapping(entries)   => entries.iterator.flatMap((k, v) => problem(k).orElse(problem(v))).nextOption()
    case Yaml.Sequence(elements) => elements.iterator.flatMap(problem).nextOption()
    case Yaml.Scalar(value, _)   => multiLineProblem(value)
    case Yaml.NullValue          => None

  /** A single-line value never reaches a block scalar; `needsQuoting` quotes its control characters correctly. */
  private def multiLineProblem(value: String): Option[String] =
    if !value.contains('\n') then None
    else
      value
        .split("\n", -1)
        .iterator
        .zipWithIndex
        .flatMap { (line, i) =>
          if line.exists(c => c == '\r' || (c.isControl && c != '\t')) then
            Some(
              s"multi-line YAML value line ${i + 1} contains a carriage return or control character, " +
                s"which would be quote-escaped into a single line: ${line.take(60)}"
            )
          else if line.startsWith("\t") then
            Some(s"multi-line YAML value line ${i + 1} starts with a tab; block scalar indentation must be spaces")
          else None
        }
        .nextOption()

  private def writeNode(sb: java.lang.StringBuilder, yaml: Yaml, indent: Int, isTopLevel: Boolean): Unit =
    yaml match
      case Yaml.Mapping(entries)   => writeBlockMapping(sb, entries, indent, isTopLevel)
      case Yaml.Sequence(elements) => writeBlockSequence(sb, elements, indent, isTopLevel)
      case Yaml.Scalar(value, tag) => writeScalar(sb, value, tag)
      case Yaml.NullValue          => sb.append("null")

  private def writeBlockMapping(
      sb: java.lang.StringBuilder,
      entries: Chunk[(Yaml, Yaml)],
      indent: Int,
      isTopLevel: Boolean,
  ): Unit =
    if entries.isEmpty then sb.append("{}")
    else
      var first = true
      entries.foreach { (key, value) =>
        if !first || !isTopLevel then
          sb.append('\n')
          appendIndent(sb, indent)
        writeScalarKey(sb, key)
        sb.append(':')
        writeMappedValue(sb, value, indent)
        first = false
      }

  private def writeMappedValue(sb: java.lang.StringBuilder, value: Yaml, indent: Int): Unit =
    value match
      case Yaml.Mapping(sub) if sub.nonEmpty     => writeBlockMapping(sb, sub, indent + indentStep, isTopLevel = false)
      case Yaml.Sequence(el) if el.nonEmpty      => writeBlockSequence(sb, el, indent + indentStep, isTopLevel = false)
      case Yaml.Scalar(v, _) if v.contains('\n') => writeBlockScalar(sb, v, indent + indentStep)
      case _                                     =>
        sb.append(' ')
        writeNode(sb, value, indent + indentStep, isTopLevel = false)

  private def writeBlockSequence(
      sb: java.lang.StringBuilder,
      elements: Chunk[Yaml],
      indent: Int,
      isTopLevel: Boolean,
  ): Unit =
    if elements.isEmpty then sb.append("[]")
    else
      var first = true
      elements.foreach { elem =>
        if !first || !isTopLevel then
          sb.append('\n')
          appendIndent(sb, indent)
        sb.append("- ")
        elem match
          case Yaml.Mapping(entries) if entries.nonEmpty =>
            var firstEntry = true
            entries.foreach { (k, v) =>
              if !firstEntry then
                sb.append('\n')
                appendIndent(sb, indent + 2)
              writeScalarKey(sb, k)
              sb.append(':')
              writeMappedValue(sb, v, indent + 2)
              firstEntry = false
            }
          case _ => writeNode(sb, elem, indent + 2, isTopLevel = false)
        end match
        first = false
      }

  /** Plain `|` clips to a single final newline, which an `actions/cache` path list wants.
    *
    * A `\r` or C0 control character (which `needsQuoting` collapses onto one escaped line) or a tab-indented line would
    * silently produce YAML GitHub cannot read; [[problem]] rejects both before printing.
    */
  private def writeBlockScalar(sb: java.lang.StringBuilder, value: String, contentIndent: Int): Unit =
    val lines = value.split("\n", -1)
    sb.append(" |")
    lines.foreach { line =>
      sb.append('\n')
      if line.nonEmpty then appendIndent(sb, contentIndent)
      sb.append(line)
    }
  end writeBlockScalar

  private def writeScalarKey(sb: java.lang.StringBuilder, key: Yaml): Unit = key match
    case Yaml.Scalar(value, tag) => writeScalar(sb, value, tag)
    case _                       => sb.append("null")

  // Everything below is replicated from zio-blocks' YamlWriter, so single-line output stays byte-identical.

  private def writeScalar(sb: java.lang.StringBuilder, value: String, tag: Option[YamlTag]): Unit =
    if needsQuoting(value, tag) then
      sb.append('"')
      var idx = 0
      while idx < value.length do
        value.charAt(idx) match
          case '"'           => sb.append("\\\"")
          case '\\'          => sb.append("\\\\")
          case '\n'          => sb.append("\\n")
          case '\t'          => sb.append("\\t")
          case '\r'          => sb.append("\\r")
          case '\b'          => sb.append("\\b")
          case c if c < 0x20 => sb.append("\\u").append(String.format("%04x", Int.box(c.toInt)))
          case c             => sb.append(c)
        idx += 1
      end while
      sb.append('"')
    else sb.append(value)

  private def needsQuoting(value: String, tag: Option[YamlTag]): Boolean =
    if value.isEmpty then return true
    tag match
      case Some(YamlTag.Bool) | Some(YamlTag.Int) | Some(YamlTag.Float) | Some(YamlTag.Null) => return false
      case _                                                                                 => ()
    if isSpecialValue(value) then return true
    val c0 = value.charAt(0)
    if c0 == '\'' || c0 == '"' || c0 == '{' || c0 == '[' || c0 == '|' || c0 == '>' ||
      c0 == '%' || c0 == '@' || c0 == '`' || c0 == '&' || c0 == '*' || c0 == '!' || c0 == '?'
    then return true
    if looksNumeric(value) then return true
    var idx = 0
    while idx < value.length do
      val c = value.charAt(idx)
      if c < 0x20 && c != '\t' then return true
      if c == '\n' || c == '\r' then return true
      if c == ':' && idx + 1 < value.length && value.charAt(idx + 1) == ' ' then return true
      if c == '#' && idx > 0 && value.charAt(idx - 1) == ' ' then return true
      idx += 1
    false
  end needsQuoting

  private def isSpecialValue(value: String): Boolean = value match
    case "null" | "~" | "Null" | "NULL"                         => true
    case "true" | "false" | "True" | "False" | "TRUE" | "FALSE" => true
    case "yes" | "no" | "Yes" | "No" | "YES" | "NO"             => true
    case "on" | "off" | "On" | "Off" | "ON" | "OFF"             => true
    case ".inf" | "-.inf" | ".Inf" | "-.Inf" | ".INF" | "-.INF" => true
    case ".nan" | ".NaN" | ".NAN"                               => true
    case _                                                      => false

  private def looksNumeric(value: String): Boolean =
    val c0 = value.charAt(0)
    if c0 >= '0' && c0 <= '9' then return true
    if (c0 == '+' || c0 == '-') && value.length > 1 then
      val c1 = value.charAt(1)
      if c1 >= '0' && c1 <= '9' then return true
      if c1 == '.' then return true
    if c0 == '.' && value.length > 1 then
      val c1 = value.charAt(1)
      if c1 >= '0' && c1 <= '9' then return true
    false
  end looksNumeric

  private def appendIndent(sb: java.lang.StringBuilder, indent: Int): Unit =
    var idx = 0
    while idx < indent do
      sb.append(' ')
      idx += 1

end YamlPrinter
