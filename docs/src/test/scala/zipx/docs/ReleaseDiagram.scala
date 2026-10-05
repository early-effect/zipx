package zipx.docs

import ascent.*
import ascent.ast.UI
import ascent.dsl.*

/** Column diagrams for the snapshot cycle. They are ordinary ascent trees, so they take the width of the prose column
  * and wrap instead of painting a fixed scene.
  */
object ReleaseDiagram:

  private enum Role:
    case Step, Mark, Aside

  private enum Block:
    case Card(label: String, role: Role = Role.Step)
    case Edge(label: String)
    case Split(arms: List[List[Block]])

  private val ink    = Color.keyword("var(--specular-text)")
  private val muted  = Color.keyword("var(--specular-muted)")
  private val line   = Color.keyword("var(--specular-border)")
  private val fill   = Color.keyword("var(--specular-surface)")
  private val accent = Color.keyword("var(--specular-accent)")

  private object Styles:
    object Frame
        extends CssClass(
          S.display.flex,
          S.flexDirection.column,
          S.width.pct(100),
          S.maxWidth.pct(100),
          S.minWidth.px(0),
          S.boxSizing.borderBox,
        )

    object Lane
        extends CssClass(
          S.display.flex,
          S.flexDirection.column,
          S.minWidth.px(0),
        )

    object Fork
        extends CssClass(
          S.display.grid,
          Declaration("grid-template-columns", "repeat(auto-fit, minmax(min(100%, 16rem), 1fr))"),
          S.gap(0.75.rem),
          S.width.pct(100),
          S.minWidth.px(0),
        )

    private def edged(edge: Declaration): CssClass =
      new CssClass(
        S.boxSizing.borderBox,
        S.width.pct(100),
        S.padding(0.7.rem, 0.9.rem),
        S.background(fill),
        S.color(ink),
        S.border(Border.solid(1.px, line)),
        S.borderRadius.px(8),
        S.fontSize.rem(0.95),
        S.lineHeight(1.35),
        Declaration("overflow-wrap", "anywhere"),
        edge,
      ) {}

    val CardStep: CssClass  = edged(Declaration("border-left", "3px solid var(--specular-muted)"))
    val CardMark: CssClass  = edged(Declaration("border-left", "3px solid var(--specular-accent)"))
    val CardAside: CssClass = edged(Declaration("border-style", "dashed"))

    object Arrow
        extends CssClass(
          S.display.flex,
          S.flexDirection.column,
          S.alignItems.center,
          S.gap(0.1.rem),
          S.padding(0.3.rem, 0.px),
          S.color(muted),
        )

    object Caption
        extends CssClass(
          S.fontSize.rem(0.75),
          S.lineHeight(1.3),
          S.textAlign.center,
          Declaration("overflow-wrap", "anywhere"),
        )

    object Chevron
        extends CssClass(
          S.fontSize.rem(0.9),
          S.lineHeight(1),
          S.color(accent),
        )
  end Styles

  /** The words painted in `ui`, for a doc assertion. Reactive nodes contribute nothing. */
  def prose(ui: UI[Any]): String =
    ui match
      case UI.Text(value)             => value
      case UI.Element(_, _, children) => children.map(prose).mkString
      case UI.Fragment(children)      => children.map(prose).mkString
      case UI.Empty                   => ""
      case UI.ReactiveText(_)         => ""
      case UI.ReactiveChild(_)        => ""
      case UI.When(_, _)              => ""
      case _: UI.ForEach[?, ?]        => ""
      case _: UI.ForEachSignal[?, ?]  => ""
      case UI.Scoped(_)               => ""
      case UI.ServerRegion(_, _)      => ""

  def strings: UI[Any] =
    diagram(
      Block.Card("1.4.2-ci · every compile"),
      Block.Card("1.4.2-sha-SNAPSHOT · the pin"),
      Block.Split(
        List(
          List(Block.Card("1.4.2 · the release", Role.Mark)),
          List(
            Block.Edge("not a pin"),
            Block.Card("1.4.2-SNAPSHOT · the pointer", Role.Aside),
          ),
        )
      ),
    )

  def cycle: UI[Any] =
    diagram(
      Block.Card("compile 1.4.2-ci"),
      Block.Card("zipxSnapshotPublish"),
      Block.Split(
        List(
          List(Block.Card("downstream pins 1.4.2-sha-SNAPSHOT")),
          List(
            Block.Card("pointer 1.4.2-SNAPSHOT", Role.Aside),
            Block.Card("zipxSnapshotStatus"),
            Block.Edge("newer sha"),
            Block.Card("zipxSnapshotAdvance, then reload"),
            Block.Edge("updates the pin"),
          ),
        )
      ),
      Block.Card("zipxReleasePlan"),
      Block.Split(
        List(
          List(
            Block.Edge("a sha pin blocks a ship"),
            Block.Card("release that line upstream"),
            Block.Card("zipxPinRelease"),
            Block.Edge("then plan again"),
          ),
          List(
            Block.Edge("Ready"),
            Block.Card("zipx release · one deployment", Role.Mark),
            Block.Card("bump pull request · next line 1.4.3", Role.Mark),
            Block.Card("compile 1.4.3-ci", Role.Mark),
          ),
        )
      ),
    )

  def shipReady: UI[Any] =
    diagram(
      Block.Card("widgets publishes 1.4.2-sha"),
      Block.Card("client pins that sha"),
      Block.Card("zipxReleasePlan · Not ready"),
      Block.Card("widgets releases 1.4.2"),
      Block.Card("client runs zipxPinRelease widgets"),
      Block.Card("zipxReleasePlan · Ready", Role.Mark),
    )

  def afterRelease: UI[Any] =
    diagram(
      Block.Card("tag libs/v1.4.2 stays put"),
      Block.Split(
        List(
          List(Block.Card("1.4.2 hides later snapshots of that line", Role.Aside)),
          List(
            Block.Card("pull request opens 1.4.3", Role.Mark),
            Block.Card("main compiles 1.4.3-ci", Role.Mark),
          ),
        )
      ),
    )

  private def diagram(blocks: Block*): UI[Any] =
    E.div(Styles.Frame, lane(blocks.toList))

  private def lane(blocks: List[Block]): UI[Any] =
    val kids: Seq[Arg[Any]] = render(blocks, afterCard = false).map(Arg.ChildArg[Any])
    E.div(Styles.Lane, kids)

  private def render(blocks: List[Block], afterCard: Boolean): List[UI[Any]] =
    blocks match
      case Nil                       => Nil
      case Block.Edge(label) :: rest =>
        arrow(Some(label), terminal = rest.isEmpty) :: render(rest, afterCard = false)
      case Block.Card(label, role) :: rest =>
        join(afterCard) ++ (card(label, role) :: render(rest, afterCard = true))
      case Block.Split(arms) :: rest =>
        join(afterCard) ++ (fork(arms) :: render(rest, afterCard = true))

  private def join(afterCard: Boolean): List[UI[Any]] =
    if afterCard then List(arrow(None)) else Nil

  private def card(label: String, role: Role): UI[Any] =
    val style =
      role match
        case Role.Step  => Styles.CardStep
        case Role.Mark  => Styles.CardMark
        case Role.Aside => Styles.CardAside
    E.div(style, label)

  private def arrow(label: Option[String], terminal: Boolean = false): UI[Any] =
    val caption =
      label match
        case Some(text) => E.div(Styles.Caption, text)
        case None       => UI.Empty
    val mark = if terminal then UI.Empty else E.div(Styles.Chevron, "↓")
    E.div(Styles.Arrow, caption, mark)

  private def fork(arms: List[List[Block]]): UI[Any] =
    val kids: Seq[Arg[Any]] = arms.map(arm => Arg.ChildArg[Any](lane(arm)))
    E.div(Styles.Fork, kids)
end ReleaseDiagram
