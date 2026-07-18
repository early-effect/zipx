package zipx.docs

import ascent.*
import ascent.css.Styles.*

/** Page-global styles for GFM markdown tables (Specular renders bare `table` / `th` / `td`).
  *
  * Uses theme CSS variables so light/dark tokens stay in sync with EarlyEffectTheme. Appended into `theme.css` from
  * [[BuildSite.afterBuild]] so every page picks them up without a theme fork.
  */
object DocTables
    extends GlobalStyle(
      GlobalRule.selector(Sel.tag("table"))(
        width.pct(100),
        borderCollapse.separate,
        borderSpacing(0.px),
        margin(1.5.rem, 0.px),
        fontSize(0.925.rem),
        lineHeight(1.45),
        background(DocTablesTokens.surface),
        border(Border.solid(1.px, DocTablesTokens.border)),
        borderRadius(DocTablesTokens.radius),
        overflow.hidden,
        boxShadow(Shadow(0.px, 1.px, 2.px, Color.rgba(0, 0, 0, 0.12))),
      ),
      GlobalRule.selector(Sel.tag("thead"))(
        background(DocTablesTokens.headerBg),
      ),
      GlobalRule.selector(Sel.tag("th"))(
        textAlign.left,
        verticalAlign.bottom,
        fontWeight(600),
        fontSize(0.75.rem),
        letterSpacing(0.06.em),
        textTransform.uppercase,
        color(DocTablesTokens.muted),
        padding(0.9.rem, 1.1.rem),
        borderBottom(Border.solid(1.px, DocTablesTokens.border)),
      ),
      GlobalRule.selector(Sel.tag("td"))(
        textAlign.left,
        verticalAlign.top,
        color(DocTablesTokens.text),
        padding(0.85.rem, 1.1.rem),
        borderBottom(Border.solid(1.px, DocTablesTokens.border)),
      ),
      GlobalRule.selector(Sel.tag("tbody").child(Sel.tag("tr")).pseudoClass(PseudoClass.nthChild(Nth.even)))(
        background(DocTablesTokens.stripe),
      ),
      GlobalRule.selector(
        Sel.tag("tbody").child(Sel.tag("tr")).pseudoClass(PseudoClass.lastChild).descendant(Sel.tag("td"))
      )(
        borderBottom.none,
      ),
      GlobalRule.selector(Sel.tag("th").descendant(Sel.tag("code")).or(Sel.tag("td").descendant(Sel.tag("code"))))(
        fontSize(0.85.em),
      ),
      GlobalRule.atRule(
        "zipx-doc-tables-narrow",
        MediaQuery(
          Media.maxWidth.px(720),
          Selector(
            Sel.tag("table"),
            display.block,
            overflowX.auto,
          ),
        ),
      ),
    )

/** Color / length tokens as CSS variables (same names EarlyEffectTheme writes on `:root`). */
private object DocTablesTokens:
  val surface  = Color.keyword("var(--specular-surface)")
  val text     = Color.keyword("var(--specular-text)")
  val muted    = Color.keyword("var(--specular-muted)")
  val border   = Color.keyword("var(--specular-border)")
  val radius   = "var(--specular-radius)"
  val headerBg = Color.keyword("color-mix(in srgb, var(--specular-surface) 70%, var(--specular-border))")
  val stripe   = Color.keyword("color-mix(in srgb, var(--specular-bg) 55%, var(--specular-surface))")
end DocTablesTokens
