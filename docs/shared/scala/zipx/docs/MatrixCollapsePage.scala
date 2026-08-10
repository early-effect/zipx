package zipx.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

/** Interactive illustrations for MatrixCollapse (JS-safe: no zipx.core). */
object MatrixCollapsePage extends DocSpec:

  /** One Actions job node in the toy monorepo workflow. */
  private final case class Job(
      id: String,
      name: String,
      detail: String,
      status: String, // ok | matrix | warn | gate
  )

  /** Column of sibling jobs (one stage in the Actions graph). */
  private final case class Column(title: String, jobs: List[Job])

  private final case class GraphState(
      mode: String,
      batchNeedsApi: Boolean,
      banner: Option[String],
      columns: List[Column],
  )

  private def buildGraph(mode: String, batchNeedsApi: Boolean): GraphState =
    val modules       = List("api", "web", "batch")
    val matrixModules = "matrix.module · api · web · batch"

    def perModule(prefix: String): List[Job] =
      modules.map(m => Job(s"$prefix-$m", s"$prefix $m", "1 module", "ok"))

    def matrix(prefix: String, status: String, detail: String): List[Job] =
      List(Job(prefix, prefix, detail, status))

    val testCol = Column("Verify", List(Job("test", "test", "Aggregate", "ok")))

    val imageCol =
      mode match
        case "Off" => Column("Publish · image", perModule("image"))
        case _     => Column("Publish · image", matrix("image", "matrix", matrixModules))

    val (dockerCol, banner) =
      mode match
        case "Off" =>
          val jobs =
            if batchNeedsApi then
              List(
                Job("docker-api", "docker api", "needs: (none)", "ok"),
                Job("docker-web", "docker web", "needs: (none)", "ok"),
                Job("docker-batch", "docker batch", "needs: docker api", "ok"),
              )
            else perModule("docker")
          (Column("Publish · docker", jobs), None)
        case "Strict" if batchNeedsApi =>
          (
            Column(
              "Publish · docker",
              List(
                Job(
                  "docker",
                  "docker",
                  "Strict refuses docker→docker needs between modules",
                  "gate",
                )
              ),
            ),
            Some(
              "Generate fails: MatrixCollapse.Strict cannot collapse DependencyOrdered docker while batch needs api."
            ),
          )
        case "Strict" =>
          (
            Column("Publish · docker", matrix("docker", "matrix", matrixModules)),
            None,
          )
        case "Coarse" if batchNeedsApi =>
          (
            Column(
              "Publish · docker",
              matrix("docker", "warn", "matrix.module · docker→docker needs dropped"),
            ),
            Some(
              "Coarse warning: docker legs start together; GitHub cannot express per-matrix-leg needs."
            ),
          )
        case _ =>
          (
            Column("Publish · docker", matrix("docker", "matrix", matrixModules)),
            None,
          )

    val deployCol =
      mode match
        case "Off"                     => Column("Deploy", perModule("deploy"))
        case "Strict" if batchNeedsApi =>
          Column("Deploy", List(Job("deploy", "deploy", "blocked until docker generates", "gate")))
        case _ =>
          Column("Deploy", matrix("deploy", "matrix", matrixModules))

    GraphState(mode, batchNeedsApi, banner, List(testCol, imageCol, dockerCol, deployCol))
  end buildGraph

  private object Styles:
    val ink     = Color.hex("#1f2328")
    val muted   = Color.hex("#656d76")
    val line    = Color.hex("#d0d7de")
    val panel   = Color.hex("#f6f8fa")
    val surface = Color.hex("#ffffff")
    val ok      = Color.hex("#1a7f37")
    val warn    = Color.hex("#9a6700")
    val gate    = Color.hex("#cf222e")
    val accent  = Color.hex("#0969da")

    object Lab
        extends CssClass(
          S.display.grid,
          S.gap(1.rem),
          S.padding(1.rem),
          S.background(panel),
          S.border(Border.solid(1.px, line)),
          S.borderRadius.px(8),
          S.color(ink),
          Declaration("font-family", "'IBM Plex Sans', 'Segoe UI', sans-serif"),
          S.fontSize.px(14),
        )

    object Row
        extends CssClass(
          S.display.flex,
          S.flexWrap.wrap,
          S.alignItems.center,
          S.gap(0.5.rem),
        )

    object ModeBtn
        extends CssClass(
          S.padding(0.35.rem, 0.75.rem),
          S.border(Border.solid(1.px, line)),
          S.borderRadius.px(6),
          S.background(surface),
          S.color(ink),
          S.cursor.pointer,
          S.fontWeight(500),
        )

    object ModeBtnOn
        extends CssClass(
          S.padding(0.35.rem, 0.75.rem),
          S.border(Border.solid(1.px, accent)),
          S.borderRadius.px(6),
          S.background(accent),
          S.color(Color.hex("#ffffff")),
          S.cursor.pointer,
          S.fontWeight(600),
        )

    object Graph
        extends CssClass(
          S.display.flex,
          S.flexWrap.wrap,
          S.alignItems.flexStart,
          S.gap(0.75.rem),
          S.padding(0.75.rem),
          S.background(surface),
          S.border(Border.solid(1.px, line)),
          S.borderRadius.px(8),
          S.overflowX.auto,
        )

    object Col
        extends CssClass(
          S.display.flex,
          S.flexDirection.column,
          S.gap(0.5.rem),
          S.minWidth.px(148),
        )

    object ColTitle
        extends CssClass(
          S.fontSize.px(11),
          Declaration("letter-spacing", "0.04em"),
          S.textTransform.uppercase,
          S.color(muted),
          S.fontWeight(600),
        )

    object JobCard
        extends CssClass(
          S.display.grid,
          Declaration("grid-template-columns", "12px 1fr"),
          S.gap(0.45.rem),
          S.alignItems.start,
          S.padding(0.55.rem, 0.65.rem),
          S.background(surface),
          S.border(Border.solid(1.px, line)),
          S.borderRadius.px(6),
          S.boxShadow(Shadow(0.px, 1.px, 0.px, Color.rgba(31, 35, 40, 0.04))),
        )

    object JobName
        extends CssClass(
          S.fontWeight(600),
          S.fontSize.px(13),
          S.lineHeight(1.25),
        )

    object JobDetail
        extends CssClass(
          S.color(muted),
          S.fontSize.px(11),
          S.lineHeight(1.35),
        )

    object Arrow
        extends CssClass(
          S.alignSelf.center,
          S.color(muted),
          S.fontSize.px(18),
          S.paddingTop(1.4.rem),
          S.userSelect.none,
        )

    object BannerOk
        extends CssClass(
          S.padding(0.55.rem, 0.75.rem),
          S.borderRadius.px(6),
          S.background(Color.hex("#dafbe1")),
          S.color(ok),
          S.fontSize.px(13),
        )

    object BannerWarn
        extends CssClass(
          S.padding(0.55.rem, 0.75.rem),
          S.borderRadius.px(6),
          S.background(Color.hex("#fff8c5")),
          S.color(warn),
          S.fontSize.px(13),
        )

    object BannerGate
        extends CssClass(
          S.padding(0.55.rem, 0.75.rem),
          S.borderRadius.px(6),
          S.background(Color.hex("#ffebe9")),
          S.color(gate),
          S.fontSize.px(13),
        )

    object Mono
        extends CssClass(
          Declaration("font-family", "'IBM Plex Mono', ui-monospace, monospace"),
          S.fontSize.px(12),
        )

    object DotOk
        extends CssClass(
          S.width.px(10),
          S.height.px(10),
          S.marginTop.px(3),
          S.borderRadius.px(999),
          S.background(ok),
        )

    object DotMatrix
        extends CssClass(
          S.width.px(10),
          S.height.px(10),
          S.marginTop.px(3),
          S.borderRadius.px(999),
          S.background(accent),
        )

    object DotWarn
        extends CssClass(
          S.width.px(10),
          S.height.px(10),
          S.marginTop.px(3),
          S.borderRadius.px(999),
          S.background(warn),
        )

    object DotGate
        extends CssClass(
          S.width.px(10),
          S.height.px(10),
          S.marginTop.px(3),
          S.borderRadius.px(999),
          S.background(gate),
        )
  end Styles

  private def statusDot(status: String): UI[Any] =
    status match
      case "matrix" => E.span(Styles.DotMatrix)
      case "warn"   => E.span(Styles.DotWarn)
      case "gate"   => E.span(Styles.DotGate)
      case _        => E.span(Styles.DotOk)

  private def jobCard(job: Job): UI[Any] =
    E.div(
      Styles.JobCard,
      statusDot(job.status),
      E.div(
        E.div(Styles.JobName, job.name),
        E.div(Styles.JobDetail, job.detail),
      ),
    )

  private def columnUi(col: Column): UI[Any] =
    val kids: Seq[Arg[Any]] =
      Arg.ChildArg(E.div(Styles.ColTitle, col.title)) +: col.jobs.map(j => Arg.ChildArg(jobCard(j)))
    E.div(Styles.Col, kids)

  private def bannerUi(state: GraphState): UI[Any] =
    state.banner match
      case Some(msg) if state.mode == "Strict" => E.div(Styles.BannerGate, msg)
      case Some(msg)                           => E.div(Styles.BannerWarn, msg)
      case None if state.mode == "Off"         =>
        E.div(Styles.BannerOk, "Off: one Actions job per Graph module (busy DAG, familiar check names).")
      case None =>
        E.div(
          Styles.BannerOk,
          "Collapsed: sibling fan-out becomes one expandable matrix job in the Actions UI.",
        )

  private def modeButton(mode: Source[String], label: String): UI[Any] =
    E.button(
      mode.map(m => if m == label then Set[CssClass](Styles.ModeBtnOn) else Set[CssClass](Styles.ModeBtn)),
      Events.onClick(_ => mode.set(label)),
      label,
    )

  private def actionsGraphLab: UIO[UI[Any]] =
    for
      mode  <- sq("Off")
      chain <- sq(true)
    yield
      val state = Squawk.zipWith(mode, chain)(buildGraph)

      E.div(
        Styles.Lab,
        E.div(
          Styles.Mono,
          "toy monorepo · docker modules ",
          E.code("api"),
          " · ",
          E.code("web"),
          " · ",
          E.code("batch"),
          " (Graph image + docker + deploy after Aggregate test)",
        ),
        E.div(
          Styles.Row,
          E.strong("MatrixCollapse: "),
          modeButton(mode, "Off"),
          modeButton(mode, "Strict"),
          modeButton(mode, "Coarse"),
        ),
        E.div(
          Styles.Row,
          E.button(
            Styles.ModeBtn,
            Events.onClick(_ => chain.update(!_)),
            chain.map(on =>
              if on then "Module edge on: batch needs api (docker job → docker job)"
              else "Module edge off: docker siblings are independent"
            ),
          ),
        ),
        state.map(bannerUi),
        state.map { g =>
          val pieces: Seq[Arg[Any]] =
            g.columns.zipWithIndex.flatMap { (col, i) =>
              val arrow: List[Arg[Any]] =
                if i > 0 then List(Arg.ChildArg(E.div(Styles.Arrow, "→"))) else Nil
              arrow :+ Arg.ChildArg[Any](columnUi(col))
            }
          E.div(Styles.Graph, pieces)
        },
        E.div(
          Styles.JobDetail,
          "Blue = matrix job · amber = Coarse dropped needs · red = Strict generate gate. ",
          "This mirrors the Actions workflow graph: fewer nodes when collapse is allowed.",
        ),
      )
  end actionsGraphLab

  def doc = page("Matrix collapse")(
    md"""
**Matrix collapse** folds Graph module siblings (or Aggregate/Layer target siblings) into one GitHub Actions job with
`strategy.matrix`, so the Actions UI shows one expandable node instead of a busy DAG.

Default remains **Off**. Modes:

| Mode | Behavior |
|---|---|
| **Off** | Today's emission |
| **Strict** | Collapse only when legs are independent and isomorphic; else generate fails |
| **Coarse** | May drop Graph `needs` between modules of the **same capability** (GHA cannot do per-leg needs); still errors if templates diverge |

Cascade: `Capability.withMatrixCollapse` wins over `zipxMatrixCollapse` plan map, else Off.
""",
    section("Actions graph")(
      md"""
A small monorepo: Aggregate `test`, then Graph `image` / `docker` / `deploy` for `api`, `web`, and `batch`. Toggle
**Strict** with the api→batch edge on to see the generate gate; switch to **Coarse** to collapse anyway (needs dropped).
""",
      exampleIO {
        actionsGraphLab
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("API")(
      md"""
```scala
zipxMatrixCollapse := Map(
  Capability.DockerName -> MatrixCollapse.Strict,
)

zipxCapabilities += Capability.custom(...).withMatrixCollapse(MatrixCollapse.Strict)
zipxCapabilities += Capability.dockerGraph.withMatrixCollapse(MatrixCollapse.Off) // veto
```

Adopting collapse **renames** required checks: `image api` becomes `image (api)`. Use capability `Off`
during cutover; dual emission is not supported.
"""
    ),
  )
end MatrixCollapsePage
