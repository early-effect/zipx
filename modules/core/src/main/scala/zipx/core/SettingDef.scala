package zipx.core

import neotype.Subtype
import scala.quoted.{Expr, Quotes, Type as QuotedType, quotes}

/** Public `zipx*` setting / task key name as it appears in `build.sbt` and the Settings docs table. */
type SettingName = SettingName.Type
object SettingName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a setting name must be non-empty"
    else if input.matches("zipx[A-Za-z][A-Za-z0-9]*") then true
    else s"invalid setting name '$input': must look like zipxCamelCase"

/** Display type for the Settings docs table, derived from the setting's Scala type via [[TypeLabel.of]]. */
type TypeLabel = TypeLabel.Type
object TypeLabel extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.trim.isEmpty then "a type label must be non-empty" else true

  /** Pretty type name for docs, from the actual type argument (not a hand-written string). */
  inline def of[A]: TypeLabel = ${ ofImpl[A] }

  private def ofImpl[A: QuotedType](using Quotes): Expr[TypeLabel] =
    import quotes.reflect.*
    val raw    = TypeRepr.of[A].dealias.simplified.show
    val pretty = TypeLabel.shorten(raw)
    '{ TypeLabel.unsafeMake(${ Expr(pretty) }) }

  /** Drop noisy package prefixes so docs show `Map[CapabilityName, MatrixCollapse]`, not FQNs. */
  private[core] def shorten(shown: String): String =
    shown
      .replace("scala.collection.immutable.", "")
      .replace("scala.collection.", "")
      .replace("scala.", "")
      .replace("zipx.core.", "")
      .replace("zipx.workflow.", "")
end TypeLabel

/** Purpose / `settingKey` description prose shared by the plugin and docs. */
type SettingPurpose = SettingPurpose.Type
object SettingPurpose extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.trim.isEmpty then "a setting purpose must be non-empty" else true

/** Where the key is documented and typically assigned. */
enum SettingScope:
  case Build, Project

/** sbt key kind (Input is listed with Tasks on the Settings page). */
enum SettingKind:
  case Setting, Task, Input

/** Documented default: a typed value (show text from the expression source), or a derived summary. */
sealed trait SettingDefault[+A]:
  def render: String

object SettingDefault:
  /** Typed default; `show` is the source of the expression passed to [[value]]. */
  final case class Value[+A](value: A, show: String) extends SettingDefault[A]:
    def render: String = s"`$show`"

  /** Docs-only summary when the real default is computed in the plugin (DockerPlugin, publish/skip, …). */
  final case class Derived(summary: String) extends SettingDefault[Nothing]:
    def render: String = summary

  /** Capture both the value and its source text, e.g. `SettingDefault.value(Seq.empty)`. */
  inline def value[A](inline default: A): Value[A] = ${ valueImpl('default) }

  private def valueImpl[A: QuotedType](default: Expr[A])(using Quotes): Expr[Value[A]] =
    import quotes.reflect.*
    val show =
      default.asTerm.pos.sourceCode.map(_.trim).getOrElse(default.show)
    '{ SettingDefault.Value($default, ${ Expr(show) }) }
end SettingDefault

/** One public zipx setting or task: the single source of truth for plugin descriptions and Settings docs rows. */
final case class SettingDef[A](
    name: SettingName,
    typeLabel: TypeLabel,
    default: SettingDefault[A],
    purpose: SettingPurpose,
    scope: SettingScope,
    kind: SettingKind,
):
  def settingMarkdownRow: String =
    s"| `${name: String}` | `${typeLabel: String}` | ${SettingDef.cell(default.render)} | ${SettingDef.cell(purpose)} |"

  def taskMarkdownRow: String =
    s"| `${name: String}` | ${SettingDef.cell(purpose)} |"

  def description: String = purpose
end SettingDef

object SettingDef:
  private def cell(raw: String): String =
    raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace("|", "\\|").trim

  /** `typeLabel` from `A`; `default` is real code whose docs label is its source. */
  inline def setting[A](
      name: SettingName,
      inline default: A,
      purpose: SettingPurpose,
      scope: SettingScope,
  ): SettingDef[A] =
    SettingDef(
      name = name,
      typeLabel = TypeLabel.of[A],
      default = SettingDefault.value(default),
      purpose = purpose,
      scope = scope,
      kind = SettingKind.Setting,
    )

  /** When the plugin derives the default (no single expression to store in core). */
  inline def settingDerived[A](
      name: SettingName,
      derived: String,
      purpose: SettingPurpose,
      scope: SettingScope,
  ): SettingDef[A] =
    SettingDef(
      name = name,
      typeLabel = TypeLabel.of[A],
      default = SettingDefault.Derived(derived),
      purpose = purpose,
      scope = scope,
      kind = SettingKind.Setting,
    )

  inline def task(name: SettingName, purpose: SettingPurpose): SettingDef[Unit] =
    SettingDef(
      name = name,
      typeLabel = TypeLabel.of[Unit],
      default = SettingDefault.Derived("—"),
      purpose = purpose,
      scope = SettingScope.Build,
      kind = SettingKind.Task,
    )

  inline def input(name: SettingName, purpose: SettingPurpose): SettingDef[Unit] =
    SettingDef(
      name = name,
      typeLabel = TypeLabel.of[Unit],
      default = SettingDefault.Derived("—"),
      purpose = purpose,
      scope = SettingScope.Build,
      kind = SettingKind.Input,
    )

  def settingsTable(defs: List[SettingDef[?]]): String =
    val header =
      "| Setting | Type | Default | Purpose |\n|---|---|---|---|"
    (header +: defs.map(_.settingMarkdownRow)).mkString("\n")

  def tasksTable(defs: List[SettingDef[?]]): String =
    val header = "| Task | Purpose |\n|---|---|"
    (header +: defs.map(_.taskMarkdownRow)).mkString("\n")
end SettingDef
