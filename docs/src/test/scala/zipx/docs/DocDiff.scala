package zipx.docs

import ascent.*
import ascent.dsl.*

/** GitHub-style unified diff for Specular examples (catalog bump PRs). */
object DocDiff:

  enum Kind:
    case Ctx, Del, Add, Meta

  def line(kind: Kind, text: String): UI[Any] =
    val (style, sign) =
      kind match
        case Kind.Ctx  => (Styles.Ctx, " ")
        case Kind.Del  => (Styles.Del, "-")
        case Kind.Add  => (Styles.Add, "+")
        case Kind.Meta => (Styles.Meta, " ")
    E.div(style, E.span(Styles.Sign, sign), text)

  def panel(file: String)(body: UI[Any]*): UI[Any] =
    val prefix =
      List(
        E.div(Styles.Header, file),
        line(Kind.Meta, s"diff --git a/$file b/$file"),
        line(Kind.Meta, s"--- a/$file"),
        line(Kind.Meta, s"+++ b/$file"),
      )
    val kids: Seq[Arg[Any]] =
      (prefix ++ body.toList).map(ui => Arg.ChildArg(ui))
    E.div(Styles.Panel, kids)
  end panel

  /** Several file hunks as one pull request. */
  def stack(panels: UI[Any]*): UI[Any] =
    val kids: Seq[Arg[Any]] = panels.toList.map(ui => Arg.ChildArg(ui))
    E.div(Styles.Stack, kids)

  private object Styles:
    val addBg  = Color.hex("#12261e")
    val addFg  = Color.hex("#7ee787")
    val delBg  = Color.hex("#3d1619")
    val delFg  = Color.hex("#ffa198")
    val ink    = Color.hex("#e6edf3")
    val muted  = Color.hex("#8b949e")
    val line   = Color.hex("#30363d")
    val panel  = Color.hex("#0d1117")
    val header = Color.hex("#161b22")

    object Stack
        extends CssClass(
          S.display.flex,
          Declaration("flex-direction", "column"),
          S.gap(0.75.rem),
        )

    object Panel
        extends CssClass(
          Declaration("overflow-x", "auto"),
          S.background(panel),
          S.border(Border.solid(1.px, line)),
          S.borderRadius.px(8),
          S.color(ink),
          Declaration("font-family", "'IBM Plex Mono', ui-monospace, monospace"),
          S.fontSize.px(12),
          S.lineHeight(1.45),
        )

    object Header
        extends CssClass(
          S.padding(0.55.rem, 0.75.rem),
          S.background(header),
          S.borderBottom(Border.solid(1.px, line)),
          S.color(muted),
          S.fontSize.px(12),
        )

    object Ctx
        extends CssClass(
          S.display.flex,
          S.padding(0.px, 0.75.rem),
          S.color(ink),
          Declaration("white-space", "pre"),
        )

    object Del
        extends CssClass(
          S.display.flex,
          S.padding(0.px, 0.75.rem),
          S.background(delBg),
          S.color(delFg),
          Declaration("white-space", "pre"),
        )

    object Add
        extends CssClass(
          S.display.flex,
          S.padding(0.px, 0.75.rem),
          S.background(addBg),
          S.color(addFg),
          Declaration("white-space", "pre"),
        )

    object Sign
        extends CssClass(
          Declaration("display", "inline-block"),
          Declaration("width", "1.25em"),
          Declaration("flex-shrink", "0"),
          Declaration("user-select", "none"),
        )

    object Meta
        extends CssClass(
          S.display.flex,
          S.padding(0.px, 0.75.rem),
          S.color(muted),
          Declaration("white-space", "pre"),
        )
  end Styles
end DocDiff
