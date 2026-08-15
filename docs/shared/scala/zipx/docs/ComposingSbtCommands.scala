package zipx.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

/** Typed sbt commands: keys in the plugin, wire form in core. JS-safe (no zipx.core), like [[MatrixCollapsePage]]. */
object ComposingSbtCommands extends DocSpec:

  private enum ReleaseShape:
    case Aggregate, GraphStaging, RootOnce

  private object Styles:
    val ink     = Color.hex("#1f2328")
    val muted   = Color.hex("#656d76")
    val line    = Color.hex("#d0d7de")
    val panel   = Color.hex("#f6f8fa")
    val surface = Color.hex("#ffffff")
    val ok      = Color.hex("#1a7f37")

    object Lab
        extends CssClass(
          S.display.grid,
          S.gap(0.75.rem),
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
          S.border(Border.solid(1.px, ok)),
          S.borderRadius.px(6),
          S.background(Color.hex("#dafbe1")),
          S.color(ok),
          S.cursor.pointer,
          S.fontWeight(600),
        )

    object Detail
        extends CssClass(
          S.color(muted),
          S.lineHeight(1.45),
        )

    object Mono
        extends CssClass(
          Declaration("font-family", "'IBM Plex Mono', ui-monospace, monospace"),
          S.fontSize.px(12),
          S.color(ok),
        )
  end Styles

  private def shapeButton(shape: Source[ReleaseShape], label: String, value: ReleaseShape): UI[Any] =
    E.button(
      shape.map(s => if s == value then Set[CssClass](Styles.ModeBtnOn) else Set[CssClass](Styles.ModeBtn)),
      Events.onClick(_ => shape.set(value)),
      label,
    )

  private def releasePicker: UIO[UI[Any]] =
    for shape <- sq(ReleaseShape.Aggregate: ReleaseShape)
    yield E.div(
      Styles.Lab,
      E.div(Styles.Row, "Choose a Central release shape:"),
      E.div(
        Styles.Row,
        shapeButton(shape, "Aggregate · ZipxCentral.release", ReleaseShape.Aggregate),
        shapeButton(shape, "Graph · publishSigned + releaseOnce", ReleaseShape.GraphStaging),
        shapeButton(shape, "Root Once · releaseRoot", ReleaseShape.RootOnce),
      ),
      shape.map {
        case ReleaseShape.Aggregate =>
          E.div(
            Styles.Detail,
            "One job: every publishing module's ",
            E.span(Styles.Mono, "publishSigned"),
            " in dependency order, then ",
            E.span(Styles.Mono, "sonaRelease"),
            ". Prefer this when the publish set is not exactly the root ",
            E.span(Styles.Mono, ".aggregate"),
            " (projectMatrix rows, skipped docs).",
          )
        case ReleaseShape.GraphStaging =>
          E.div(
            Styles.Detail,
            "Per-module publish jobs upload ",
            E.span(Styles.Mono, "target/sona-staging"),
            "; ",
            E.span(Styles.Mono, "releaseOnce"),
            " merges them and runs ",
            E.span(Styles.Mono, "sonaRelease"),
            ". Use when you need Graph isolation or staging across jobs.",
          )
        case ReleaseShape.RootOnce =>
          E.div(
            Styles.Detail,
            E.span(Styles.Mono, "publishSigned; sonaRelease"),
            " as one fixed root command. Fine when root ",
            E.span(Styles.Mono, ".aggregate"),
            " is exactly the publish set; otherwise prefer Aggregate ",
            E.span(Styles.Mono, "release"),
            ".",
          )
      },
    )

  def doc = page("Composing sbt commands")(
    md"""
Skip until a job needs more than one sbt task, or you are wiring a pack. Most builds never write this.

A CI job step is one `sbt '<text>'`. zipx validates text that cannot corrupt the generated file; it does not parse sbt
syntax. Typing comes back through **composition and provenance**: real keys in the plugin, a step list on the wire.

```mermaid
flowchart LR
  Key[core publishSigned TaskKey] --> Task[SbtStep.Task]
  Rows[zipxTasks.rows] --> Task
  Name[sonaRelease Command] --> Named[SbtStep.Named]
  Text[SbtCommand.raw] --> Raw[SbtStep.Raw]
  Graph([ModuleGraph]) --> Join
  Task --> Join[session a then b then c]
  Named --> Join
  Raw --> Join
  Join --> Tail[thenOnce tail]
  Tail --> Run([sbt session])
  class Key,Rows,Name,Graph,Task,Named,Join,Tail,Run happy
  class Text,Raw sad
```

Green is the paved path. Red is the escape hatch: raw text is warned at generate time and never preferred.
""",
    section("Never a string where sbt has a value")(
      md"""
**If zipx can derive it from the graph, do not write it. If sbt has a value for it, pass the value.**

```scala
zipxCapabilities ++= Seq(
  zipxTasks.once(CapabilityName("fmt"), scalafmtCheckAll),
  Capability.testGraph,
  ZipxCentral.release.withCondition(upstream),
)

zipxTestTask := zipxTasks.of(testFull)   // plugin default; override per module if needed
zipxPublish := zipxOff                   // not Some(false)
```

`zipxTasks.of` / `session` / `rows` / `each` / `only` / `except` live in the plugin (sbt on the classpath). Scripted
suites prove the key path; core unit tests prove the wire form.
"""
    ),
    section("Chaining and thenOnce")(
      md"""
`SbtCommand.session` / `andThen` join steps. Capability setters: `running` / `runningEach` / `runningEachCross` /
`thenOnce` / `runningNothing`. A session tail is **Aggregate or Once only**: Layer and Graph would release a partial
bundle per wave (generate fails naming Aggregate / `releaseOnce`).

```mermaid
flowchart TD
  A1[core publishSigned] --> A2[coreJS publishSigned]
  A2 --> A3[sonaRelease]
  A3 --> A4([Aggregate whole bundle])
  L1[Layer wave thenOnce] --> L2([partial bundle refused])
  class A1,A2,A3,A4 happy
  class L1,L2 sad
```

Verify twin: `Capability.test.thenOnce(zipxTasks.of(docs / specularSite))` after the root test session.
"""
    ),
    section("Which Central release?")(
      md"""
Pick the shape that matches your aggregate vs publish set. Rendered YAML for each lives on **Packs**.
""",
      exampleIO {
        releasePicker
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("What is checked, and when")(
      md"""
```mermaid
flowchart LR
  Key[a TaskKey or Command] --> Compile([compile: key must exist])
  Named[SbtStep.Named] --> Gen([generate: name in definedCommands])
  Raw[SbtStep.Raw] --> Warn([generate: warn only])
  Run[the runner] --> Ci([CI: sbt executes the text])
  class Key,Named,Raw,Run warn
  class Compile,Gen,Warn,Ci happy
```

`zipxCheckCommandNames` (default true) fails generate when a declared command name is unknown. Escape with
`zipxCheckCommandNames := false` only for a false positive. See **Validation**.
"""
    ),
  )
end ComposingSbtCommands
