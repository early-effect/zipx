package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.central.ZipxCentral
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.docs.DocsRender.yaml
import zipx.shell.*
import zipx.workflow.{ActionRef, Expr, Step}
import zio.test.*

/** The typed DSL a build writes steps with: shell scripts, expressions, step builders, and step bundles. */
object ShellAndSteps extends DocSpecSuite:

  def doc = page("Shell and steps")(
    md"""
Everything zipx generates is ultimately YAML full of shell and `$${{ … }}` expressions. This page is about writing
those in Scala instead of in strings.

Four layers, each usable on its own:

```mermaid
flowchart TD
  Shell[zipx-shell · Script / Command / Word / ShTest] --> Steps2[Step.run]
  Expr2[Expr · GitHub Actions expressions] --> Steps2
  Steps2[StepBuilder · Step.run / Step.uses] --> Bundle[Steps bundle · named, composable]
  Bundle --> Fields([extraSteps · postSteps · zipxCacheRehydrateExtraSteps])
  class Fields warn
  class Shell,Expr2,Steps2,Bundle happy
```

`zipx-shell` has no zipx or GitHub concepts in it at all and is publishable on its own; `Expr` knows Actions but not
sbt. The jar you already depend on via `addSbtPlugin` carries both, and the plugin's `autoImport` re-exports them, so a
`build.sbt` needs no `import`.

**Nothing here throws.** A failure is removed in the strongest form available: unrepresentable where a type can say it
(a `Block` cannot be empty, a compound command cannot sit in a pipeline leg), otherwise checked at *compile* time for a
literal, otherwise returned as `Either[String, A]`. See **Validation** below.
""",
    section("A run: body is a Script, not a string")(
      md"""
`Step.run` takes a [[zipx.shell.Script]]. `Script.strict` prefixes `set -euo pipefail`, which is what every generated
script should start with:

```scala
Step
  .run(
    Script.strict(
      Exec("mkdir", Word.lit("-p"), Word.lit("~/.gnupg")),
      Exec("echo", Word.vq("PGP_SECRET")) | Exec("base64", Word.lit("--decode")) | Exec("gpg", Word.lit("--import")),
    )
  )
  .named("Import signing key")
  .withEnv("PGP_SECRET", Expr.secret("PGP_SECRET"))
  .build
```

`|`, `&&`, `||`, redirects and `2>&1` are operators on the AST, so a pipeline is a value rather than punctuation inside
a string. Quoting is explicit, because the two styles are not interchangeable: `Word.squote("v*")` suppresses globbing,
`Word.vq("NAME")` expands `"$$NAME"` in a form that survives word splitting, `Word.lit` is verbatim.

This is the whole reason the layer exists. The GPG import step above was previously an interpolated string carrying a
warning comment: a `$$` meant "escaped dollar" to Scala and "process id" to bash, so the value reaching
`base64 --decode` was a PID. `Word.vq("PGP_SECRET")` *is* that expansion and there is no second way to spell it.
""",
      exampleValue {
        val script = Script.strict(
          Exec("mkdir", Word.lit("-p"), Word.lit("~/.gnupg")),
          Exec("echo", Word.vq("PGP_SECRET")) |
            Exec("base64", Word.lit("--decode")) |
            Exec("gpg", Word.lit("--batch"), Word.lit("--import")),
        )
        script.render
      }.assert(sh =>
        assertTrue(
          sh.startsWith("set -euo pipefail\n"),
          sh.contains("""echo "$PGP_SECRET" | base64 --decode | gpg --batch --import"""),
          !sh.contains("$$"),
        )
      ),
    ),
    section("Conditionals, loops, and jointing")(
      md"""
`If` / `ForIn` / `While` take a [[zipx.shell.ShTest]] and a `Block`. Two things are types rather than checks:

- **A branch cannot be empty.** `Block` is a head plus a tail, so `if …; then fi` (a syntax error in every shell) has no
  value that spells it.
- **`[ … ]` versus `[[ … ]]` is decided by the variant.** `ShTest.StrEq` renders POSIX `[ left = right ]`;
  `ShTest.GlobMatch` renders `[[ word == pattern ]]` and takes a `GlobPattern` rather than a `Word`, because the
  right-hand side of a bash `==` must **not** be quoted. `[ "$$ref" = refs/tags/v* ]` compares against the literal
  string, which is the silent-jobs-never-fire kind of bug.

```scala
Script.strict(
  If(
    ShTest.varEquals("ZIPX_VERIFY_CLEAN_FULL", "true"),
    Block(Exec("sbt", Word.squote("cleanFull; test"))),
    elseDo = Some(Block(Exec("sbt", Word.squote("test")))),
  )
)
```

`ShTest.Cmd` tests by exit status (`if git describe …; then`), and takes an `InlineCommand`, so `if for x in …; done;
then` does not compile.
""",
      exampleValue {
        Script
          .strict(
            If(
              ShTest.GlobMatch(Word.vq("GITHUB_REF"), GlobPattern("refs/tags/v*")),
              Block(Assign("epoch", Word.subst(Exec("git", Word.lit("describe"), Word.lit("--tags"))))),
              elseDo = Some(Block(Assign("epoch", Word.quoted("0.0.0-ci")))),
            )
          )
          .render
      }.assert(sh =>
        assertTrue(
          sh.contains("""if [[ "$GITHUB_REF" == refs/tags/v* ]]; then"""),
          sh.contains("""epoch=$(git describe --tags)"""),
          sh.contains("else"),
          sh.contains("fi"),
          sh.contains("\n  epoch="),
        )
      ),
    ),
    section("The sh\"…\" interpolator, and why splices are Words")(
      md"""
For the one-liner case, `sh"…"` concatenates literal text with typed splices:

```scala
val dir = Word.vq("BROWSERS_DIR")
Step.run(Script(Exec("npm", sh"--prefix=$$dir", Word.lit("ci")))).named("Install browsers").build
```

The signature is `sh(args: Word*)`, so a bare `String` splice **does not compile**, and there is deliberately no
implicit `String => Word`: string interpolation is precisely the hole this layer closes, and an implicit conversion
would reopen it. Wrap explicitly with `Word.lit` (checked at compile time) or `Word.litMake` (`Either`, for runtime
input).

`sh` builds a *word*, not a command: it concatenates, it does not parse shell syntax. Keep `|`, `&&` and redirects on
the AST so they stay typed.
""",
      exampleValue {
        val dir = Word.vq("BROWSERS_DIR")
        Script(Exec("npm", sh"--prefix=$dir", Word.lit("ci"))).render
      }.assert(sh => assertTrue(sh == """npm --prefix="$BROWSERS_DIR" ci""")),
    ),
    section("Expressions: Expr instead of \"${{ … }}\"")(
      md"""
Every field that takes an expression takes an [[zipx.workflow.Expr]]. Each case holds a validated name, so a typo is a
build error at the construction site instead of an empty value on the runner:

```scala
Expr.env("DEPLOY_ROLE")            // $${{ env.DEPLOY_ROLE }}
Expr.secret("PGP_PASSPHRASE")      // $${{ secrets.PGP_PASSPHRASE }}
Expr.github("event.pull_request.base.sha")
Expr.jobOutput("affected", "modules")
Expr.matrix("scala")
```

Operators and calls are jointing on the AST, which is how zipx's own gates are built:

```scala
(!Expr.cancelled && Expr.jobResult("test") !== Expr.quoted("failure")).unwrapped
// !cancelled() && needs.test.result != 'failure'

Expr.contains(Expr.fromJson(Expr.jobOutput("affected", "modules")), Expr.quoted("api")).unwrapped
// contains(fromJson(needs.affected.outputs.modules), 'api')
```

`Expr.concat` (or `++`) assembles a cache key from parts rather than interpolating one:
`Expr.lit("sbt-") ++ Expr.runner("os")` renders `sbt-$${{ runner.os }}`.

Two details worth knowing. **`render` versus `unwrapped`:** `render` wraps in `$${{ … }}`, `unwrapped` does not. An
`if:` and every operand of an operator or call is *already* an expression context, where `$${{ a }} && $${{ b }}` is a
template string that evaluates to neither operand, so those positions use `unwrapped`. **Grouping is explicit:** no
operator adds parentheses of its own, `Expr.group` is the only thing that emits one. (`JobCondition`, on the **Job
conditions** page, is the opposite: it composes user-supplied conditions of unknown shape, so it parenthesizes every
clause defensively. Different callers, different default.)
""",
      exampleValue {
        List(
          Expr.env("DEPLOY_ROLE").render,
          (Expr.lit("sbt-") ++ Expr.runner("os") ++ Expr.lit("-") ++ Expr.matrix("scala")).render,
          (!Expr.cancelled && (Expr.jobResult("test") !== Expr.quoted("failure"))).unwrapped,
          Expr.contains(Expr.fromJson(Expr.jobOutput("affected", "modules")), Expr.quoted("api")).unwrapped,
        ).mkString("\n")
      }.assert(out =>
        assertTrue(
          out.contains("${{ env.DEPLOY_ROLE }}"),
          out.contains("sbt-${{ runner.os }}-${{ matrix.scala }}"),
          out.contains("!cancelled() && needs.test.result != 'failure'"),
          out.contains("contains(fromJson(needs.affected.outputs.modules), 'api')"),
        )
      ),
    ),
    section("Step builders: run: or uses:, never both")(
      md"""
`Step` itself is a flat all-optional case class, because that shape is fixed by its YAML codec. Validity is closed from
both ends instead.

The good end is the builder. `Step.run(script)` and `Step.uses(ref)` decide the mutually exclusive pair **before** any
other field is set, so a step with both keys or neither has no builder expression that produces it. `withInput` (the
`with:` block) exists only on the `uses:` builder, because GitHub silently ignores `with:` on a `run:` step, so writing
it there does not compile.

```scala
Step.uses("aws-actions/configure-aws-credentials@v6")   // @ref checked at compile time
  .named("Configure AWS credentials")
  .withInput("role-to-assume", Expr.env("DEPLOY_ROLE"))
  .withId("aws")
  .when(Expr.github("event_name") === Expr.quoted("push"))
  .build
```

`build` is total: it returns a `Step` and has nothing to report. The closing end is `Step.validate`, which runs during
rendering and catches a step that was hand-built or decoded around the builder, naming the offending step. Validation
lives there rather than in a constructor because a `Step` is also a *decode* target, and validating on construction
would reject a value a codec is still filling in.
""",
      exampleValue {
        val step = Step
          .uses("aws-actions/configure-aws-credentials@v6")
          .named("Configure AWS credentials")
          .withInput("role-to-assume", Expr.env("DEPLOY_ROLE"))
          .withId("aws")
          .build
        val handBuilt =
          Step(name = Some("Both keys"), run = Some("echo hi"), uses = Some(ActionRef("actions/checkout@v5")))
        List(
          zipx.workflow.Render.renderSteps(List(step)).yaml,
          s"hand-built step with both keys is rejected: ${Step.validate(handBuilt).isLeft}",
        ).mkString("\n")
      }.assert(out =>
        assertTrue(
          out.contains("uses: aws-actions/configure-aws-credentials@v6"),
          out.contains("role-to-assume: ${{ env.DEPLOY_ROLE }}"),
          out.contains("id: aws"),
          out.contains("hand-built step with both keys is rejected: true"),
        )
      ),
    ),
    section("Steps bundles: named, composable, publishable")(
      md"""
`extraSteps`, `postSteps` and `cacheRehydrateExtraSteps` all have type `StepContext => List[Step]`. A [[Steps]] bundle
**is** one, so it drops into every one of those fields with no signature change:

```scala
val playwright = Steps.built("playwright")(
  Step.run(Script(Exec("npx", Word.lit("playwright"), Word.lit("install")))).named("Install browsers")
)
```

What the type adds is everything a bare lambda cannot have:

| | |
|---|---|
| `++` | concatenate two bundles, keeping order |
| `when(cond)` | AND a [[JobCondition]] into every step's `if:`, in one place |
| `named` / `mapSteps` | rename for diagnostics; apply a cross-cutting tweak |
| a name | reaches the generate-time raw-escape-hatch warning and error messages |
| a stable identity | so it can be **published** |

Constructors: `Steps.built` (from builders, the form to prefer), `Steps.of` (context-independent steps), `Steps.one`
(one context-dependent step), `Steps.buildingWith` (context-dependent builders), `Steps.all`, `Steps.empty`.

`when` gates per step, because GitHub has no bundle-level `if:`; doing it here means you write the condition once. A
step that already has an `if:` keeps it, ANDed with yours.
""",
      exampleValue {
        val warm = Steps.built("warm-node")(
          Step.run(Script(Exec("npm", Word.lit("ci")))).named("Install deps")
        )
        val browsers = Steps.built("browsers")(
          Step.run(Script(Exec("npx", Word.lit("playwright"), Word.lit("install")))).named("Install browsers")
        )
        val bundle       = (warm ++ browsers).when(JobCondition.eventIs("pull_request"))
        given PlanConfig = config.copy(skipMergedPrPush = true, cacheRehydrateExtraSteps = bundle)
        s"bundle name: ${bundle.name}\n---\n${DocsRender.job("cache-rehydrate")(Capability.test)}"
      }.assert(yaml =>
        assertTrue(
          yaml.contains("bundle name: warm-node+browsers"),
          yaml.contains("Install deps"),
          yaml.contains("Install browsers"),
          yaml.contains("github.event_name == 'pull_request'"),
        )
      ),
    ),
    section("Publishing a bundle across repos")(
      md"""
This is the payoff, and the thing issue #46 was reaching for. `zipx-core` is on Maven Central, so a shared org bundle is
an ordinary published Scala value, versioned and resolved like any other dependency:

```scala
// in a pack the org publishes
object OrgSteps:
  val aptMirror: Steps = Steps.built("apt-mirror")(
    Step
      .run(Script.strict(Exec("sudo", Word.lit("sed"), Word.lit("-i"), Word.squote("s|archive.ubuntu.com|mirror.corp|g"),
        Word.lit("/etc/apt/sources.list"))))
      .named("Point apt at the internal mirror")
  )
  val playwright: Steps = Steps.built("playwright")(
    Step.run(Script(Exec("npx", Word.lit("playwright"), Word.lit("install")))).named("Install browsers")
  )

// in a consumer build.sbt
zipxCacheRehydrateExtraSteps := OrgSteps.aptMirror ++ OrgSteps.playwright
zipxCapabilities += Capability.test.copy(extraSteps = OrgSteps.aptMirror)
```

A YAML resource file would have *relocated* the string splicing rather than removed it, and the composition operators
above have no YAML equivalent. `zipx-central`'s own steps are the first consumers of exactly this: `ZipxCentral`'s GPG
import is a published `Steps`, and `releaseOnce` composes two bundles with `++`.
""",
      exampleValue {
        val composed = ZipxCentral.releaseOnce.extraSteps
        val name     = composed match
          case s: Steps => s.name
          case _        => "not a bundle"
        s"releaseOnce.extraSteps: $name\n---\n${DocsRender.job("central-release")(ZipxCentral.releaseOnce)}"
      }.assert(yaml =>
        assertTrue(
          yaml.contains("releaseOnce.extraSteps: download-staging+gpg-import"),
          yaml.contains("Download sona staging"),
          yaml.contains("Import signing key"),
        )
      ),
    ),
    section("Validation is structural, and checked while your build compiles")(
      md"""
Every important type here is a [neotype](https://github.com/kitlangton/neotype) newtype, so an invalid value is
unconstructible. Smart constructors come in pairs:

- `inline def foo(inline text: String)` forwards a **literal** into the check, so a bad one is a compile error carrying
  the validator's own message. This is the form a `build.sbt` writes.
- `def fooMake(text: String): Either[String, A]` is for genuinely runtime input (a setting, a value read from a
  file), and names what was wrong.

```scala
Word.lit("ok")                    // fine
Word.lit("two\nlines")            // compile error: a word cannot contain a newline
Word.litMake(userInput)           // Either, for a value that is not a literal
```

Passing a non-literal (a `val`, a `"a" * 30`, a lambda parameter) to an `inline` constructor is a **compile error**
telling you to use the `Make` sibling. That is the one surprise worth naming: it is neotype working, not a bug.

The rules cover what the shell and GitHub actually do, not just the convenient cases: no `'` inside `'…'` (it cannot be
escaped, so there is nothing to escape it *with*); no `}` inside `$${…}` (it closes the expansion early); no leading tab
on a script line (YAML block-scalar indentation must be spaces); an `ExitCode` is 0 to 255 (the shell truncates modulo
256); file descriptors are single digits; `uses:` refuses an unpinned `owner/repo`; a secret name may be `GITHUB_TOKEN`
but not otherwise `GITHUB_`-prefixed, checked case-insensitively because GitHub matches names that way.
""",
      exampleValue {
        List(
          s"squote rejects an embedded quote: ${Word.squoteMake("it's").isLeft}",
          s"a deliberate glob survives: ${Word.squoteMake("v*").map(_.render) == Right("'v*'")}",
          s"unpinned uses: rejected: ${Step.usesMake("actions/checkout").isLeft}",
          s"pinned uses: accepted: ${Step.usesMake("actions/checkout@v5").isRight}",
          s"even the raw hatch rejects a leading tab: ${Script.raw("\tindented").isLeft}",
        ).mkString("\n")
      }.assert(out =>
        assertTrue(
          out.contains("squote rejects an embedded quote: true"),
          out.contains("a deliberate glob survives: true"),
          out.contains("unpinned uses: rejected: true"),
          out.contains("pinned uses: accepted: true"),
          out.contains("even the raw hatch rejects a leading tab: true"),
        )
      ),
    ),
    section("Extending: Command is open on purpose")(
      md"""
`Command` is a `trait`, not an `enum`. If you need a construct zipx does not model (a `case` statement, a `select`, a
shell function definition), implement it in your own build or pack instead of waiting on a zipx release:

```scala
final case class WhileRead(name: VarName, body: Block) extends Command:
  def lines(ctx: Script.Ctx): List[ScriptLine] =
    ctx.emit(ShLines.of("while read -r ") ++ ShLines.varName(name) + "; do") :::
      body.lines(ctx.nested) ::: ctx.line("done")
```

The contract: emit lines through `ctx.emit` / `ctx.line` / `ctx.nested` and never by prepending spaces yourself (`Script`
owns depth, and `ScriptLine` is what guarantees the result is safe inside a YAML block scalar); return one entry per
physical line; override `rawFragments` if your command carries unvalidated text. Extend `InlineCommand` instead when your
construct is usable where the shell wants one command, and you get `lines` for free from a total `inlineLines`.

Note what you cannot write: `ctx.line(s"while read -r $${name.unwrap}; do")`. `ctx.line` takes a *literal*, validated
while your file compiles, so interpolating into it is a compile error rather than a runtime one. Structure is built by
composing `ShLines`, a non-empty sequence of already-validated lines; the typed values you hold (`ShLines.varName`,
`ShLines.text`, `ShLines.pattern`, a `Word`'s own `lines`) convert into it without a validity check, because their types
already carry the guarantee. `String` appears once, at `render`.

The knowing cost is that a match over `Command` cannot be exhaustive, which is why rendering is a method on the trait
rather than a match in `Script`. `Word`, `ShTest` and `Expr` stay **closed**: the shell's grammar and GitHub's context
list are fixed, so a new case there would be a new upstream feature, not a consumer's extension.
""",
      exampleValue {
        final case class WhileRead(name: VarName, body: Block) extends Command:
          def lines(ctx: Script.Ctx): List[ScriptLine] =
            ctx.emit(ShLines.of("while read -r ") ++ ShLines.varName(name) + "; do") :::
              body.lines(ctx.nested) ::: ctx.line("done")

        Script
          .strict(
            WhileRead(VarName("line"), Block(Exec("echo", Word.vq("line"))))
          )
          .render
      }.assert(sh =>
        assertTrue(
          sh.contains("while read -r line; do"),
          sh.contains("""  echo "$line""""),
          sh.contains("done"),
        )
      ),
    ),
    section("Escape hatches, and what they cost")(
      md"""
Each layer has one, because a build that cannot express something should not be blocked. They are typed, so they cannot
break the *YAML*, and they are **loud**.

| Hatch | Guarantees | Does not guarantee |
|---|---|---|
| `Script.raw(text)` / `Command.Raw` | validated lines: no `\r`, no control characters, no leading tab | that the text is valid *shell* |
| `RawLine` | the same, in a single-command position | the same |
| `Expr.raw(text)` / `Expr.Raw` | balanced `$${{ }}`, expression-shaped | that the expression references anything real |
| `JobCondition.raw(text)` | non-empty after trimming | that it evaluates to a boolean |
| `Step.runRaw(text)` | nothing beyond `Step`'s own validity | anything about the body |
| `SbtCommand.unchecked(text)` | one line, no control characters, so it cannot corrupt the `run:` scalar | that it is valid *sbt* syntax |

The guarantee is structural, not a lint pass: `Raw` holds `List[ScriptLine]`, so the *type* is what rules out YAML
GitHub fails to parse. There is no separate check to forget to run.

What raw can still produce is broken shell, so `Script.raw` returns `Either` (it is usually runtime input, and it names
the offending line), and every hatch's content is reported through `rawFragments`. `Steps.built` collects its builders'
fragments, `++` / `when` / `named` / `mapSteps` preserve them, and `zipxWorkflowGenerate` logs one line per fragment
naming the bundle:

```text
[warn] zipx: step bundle 'legacy-script' uses a raw escape hatch, which nothing validates: ./scripts/legacy.sh --ci
```

**A bare lambda reports nothing**, because it has nowhere to carry the information. That is the honest incentive to use
`Steps.built`: the warning is a feature of the type, not of the generator.

`SbtCommand.unchecked` is reported by the same pass, and for the same reason: a typo in `api/tets` is a failing CI job
rather than a compile error. Its provenance survives composition, so an unchecked command joined into a larger session
(`SbtCommand.join`) still warns. Prefer `zipxTasks`, `cmd"…"` or the combinators on `SbtCommand`, all of which take a
real key or already-validated pieces.

Prefer implementing `Command` over reaching for `Raw` more than once. An implementation is checked, composable and
reusable; `Raw` is none of the three.
""",
      exampleValue {
        val bundle = Steps
          .built("legacy-script")(
            Step.run(Script.raw("./scripts/legacy.sh --ci").toOption.get).named("Legacy script")
          )
        val warnings = Steps.rawWarnings(List(Capability.test.copy(extraSteps = bundle)), config)
        val lambda: StepContext => List[Step] =
          _ => List(Step.run(Script.raw("./scripts/legacy.sh --ci").toOption.get).build)
        val fromLambda = Steps.rawWarnings(List(Capability.test.copy(extraSteps = lambda)), config)
        s"${warnings.mkString("\n")}\nfrom a bare lambda: ${fromLambda.size} warnings"
      }.assert(out =>
        assertTrue(
          out.contains("step bundle 'legacy-script' uses a raw escape hatch"),
          out.contains("./scripts/legacy.sh --ci"),
          out.contains("from a bare lambda: 0 warnings"),
        )
      ),
      md"""
The same pass covers an unchecked sbt command, including one composed into a joined session:
""",
      exampleValue {
        val hand = SbtCommand.unchecked("promote --dry-run").toOption.get
        val cap  = Capability.custom(name = CapabilityName("promote"), command = n => SbtCommand.module(n, hand))
        Steps.rawWarnings(List(cap), config).mkString("\n")
      }.assert(out =>
        assertTrue(
          out.contains("capability 'promote' uses an unchecked sbt command"),
          out.contains("promote --dry-run"),
          out.contains("a typo is a failing job rather than a compile error"),
        )
      ),
    ),
  )
end ShellAndSteps
