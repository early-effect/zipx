package zipx.core

import zio.test.*
import zipx.shell.*
import zipx.workflow.{Expr, Step}

import scala.collection.immutable.ListMap

/** The consumer proof: a real build's hand-written steps, rebuilt in the typed DSL.
  *
  * Every other spec tests the DSL against zipx's *own* scripts, which is a weak test of a DSL meant for other people:
  * zipx's scripts were written by the same person who chose the AST cases, so of course they fit. The steps below are
  * lifted from `early-effect/chekhov`'s `build.sbt`, the build that motivated issue #46 in the first place, and they
  * were written long before this module existed. They use the awkward shapes a real build reaches for: an `if` whose
  * condition is a *glob test on a command's exit status*, `$(…)` substitutions nested inside double quotes, `sudo`
  * fronting another program, a `${HOME}`-relative path built up from parts, and an `actions/cache` key mixing
  * `runner.os` with `hashFiles`.
  *
  * The expected strings are chekhov's current `run:` bodies verbatim. So this is two assertions at once: that the DSL
  * can express a consumer's real steps at all, and that it produces the same bytes, meaning adoption is a refactor
  * rather than a workflow diff to re-review. If a change to the shell AST moves a character here, a downstream
  * `zipxWorkflowCheck` would have failed in chekhov's repo; this fails first, here, with a diff.
  *
  * Writing it turned up exactly one rough edge, recorded here rather than smoothed over: `ActionPins` fields are
  * `String`, so `ctx.actions.cache` cannot reach `Step.uses`'s compile-time check and a build must use `usesMake` and
  * handle an `Either`. Typing those fields as `ActionRef` would remove the `Either` from every consumer that references
  * a pin, and is worth doing when the pin file's own decode path is next touched.
  *
  * @see
  *   [[ScriptRenderSpec]] for the same proof over zipx's own migrated scripts.
  */
object ConsumerStepsSpec extends ZIOSpecDefault:

  /** `"${HOME}/.cache/chekhov-apt-archives"`, the apt mirror path, as one quoted word.
    *
    * Braced because a name character follows the expansion: `"$HOME/.cache"` happens to work, but chekhov wrote
    * `${HOME}` and the two are only accidentally equivalent, so [[Word.vBraced]] keeps it deliberate.
    */
  private val aptMirrorPath: Word =
    Word.dquote(Word.vBraced("HOME"), Word.lit("/.cache/chekhov-apt-archives"))

  /** The apt mirror's `.deb` glob: the quoted variable carries the path, and the glob is *outside* the quotes so the
    * shell expands it. Getting that boundary wrong is the classic silent bug, since a glob inside double quotes matches
    * only a file whose name is literally those characters, and it is the reason [[Word.cat]] exists.
    */
  private val mirrorDebs: Word =
    Word.cat(Word.dquote(Word.vBraced("apt_mirror")), Word.lit("/*.deb"))

  private val cacheDebs: Word = Word.lit("/var/cache/apt/archives/*.deb")

  /** `$(ls -1 <glob> | wc -l | tr -d ' ')`: count the .debs, for a log line. A pipeline of three, so the `|` jointing
    * on [[InlineCommand]] does the work and the substitution just wraps the result.
    */
  private def debCount(glob: Word): Word.Subst =
    Word.subst(
      Exec("ls", Word.lit("-1"), glob) | Exec("wc", Word.lit("-l")) | Exec("tr", Word.lit("-d"), Word.squote(" "))
    )

  /** `ls` on a glob, silenced: "are there any?", tested by exit status rather than by parsing output. `silenced` is the
    * modelled form of the `>/dev/null 2>&1` suffix, so the redirection cannot be typo'd.
    */
  private def anyFilesMatch(glob: Word): ShTest =
    ShTest.succeeds(Exec("ls", glob).silenced)

  /** chekhov's Playwright browser install, the longest hand-written script in the build. */
  private val installBrowsers: Script =
    Script
      .strict(
        Assign("apt_mirror", aptMirrorPath),
        Exec("mkdir", Word.lit("-p"), Word.dquote(Word.vBraced("apt_mirror"))),
        Exec("sudo", Word.lit("mkdir"), Word.lit("-p"), Word.lit("/var/cache/apt/archives/partial")),
        If(
          anyFilesMatch(mirrorDebs),
          Block(
            Exec(
              "echo",
              Word.dquote(
                Word.lit("Seeding apt archives from "),
                Word.vBraced("apt_mirror"),
                Word.lit(" ("),
                debCount(mirrorDebs),
                Word.lit(" debs)"),
              ),
            ),
            Exec("sudo", Word.lit("cp"), Word.lit("-n"), mirrorDebs, Word.lit("/var/cache/apt/archives/"))
              || Exec("true"),
          ),
        ),
        Exec("mkdir", Word.lit("-p"), Word.dquote(Word.vBraced("PLAYWRIGHT_BROWSERS_PATH"))),
        Exec("chmod", Word.lit("+x"), Word.lit("./scripts/install-browsers.sh")),
        Exec.of(
          "./scripts/install-browsers.sh",
          List("chromium", "chromium-headless-shell", "firefox", "webkit").map(Word.litMake(_).toOption.get),
        ),
        If(
          anyFilesMatch(cacheDebs),
          Block(
            Exec(
              "sudo",
              Word.lit("cp"),
              Word.lit("-n"),
              cacheDebs,
              Word.dquote(Word.vBraced("apt_mirror"), Word.lit("/")),
            )
              || Exec("true"),
            Exec(
              "sudo",
              Word.lit("chown"),
              Word.lit("-R"),
              Word
                .dquote(Word.subst(Exec("id", Word.lit("-u"))), Word.lit(":"), Word.subst(Exec("id", Word.lit("-g")))),
              Word.dquote(Word.vBraced("apt_mirror")),
            ),
            Exec(
              "echo",
              Word.dquote(Word.lit("Apt mirror now has "), debCount(mirrorDebs), Word.lit(" debs")),
            ),
          ),
        ),
        Exec(
          "echo",
          Word.dquote(
            Word.lit("Playwright "),
            Word.subst(
              Exec("node", Word.lit("-p"), Word.dquote(Word.lit("require('playwright/package.json').version")))
            ),
            Word.lit(" browsers ready"),
          ),
        ),
      )
      .withTrailingNewline(true)

  /** The `actions/cache` step, which is the one place a consumer meets the `inline` / `make` split.
    *
    * `ctx.actions.cache` is a `String` read from the pin file at build time, so it cannot reach `Step.uses`'s
    * compile-time check and `usesMake` is the honest signature. A build has to decide what a malformed pin means, which
    * is why this returns `Either` rather than throwing: here that is a test failure, and in a `build.sbt` it would be
    * `orFail`. See the note on this spec about typing `ActionPins`.
    */
  private def cacheStep(pin: String) =
    Step.usesMake(pin).fold(error => throw AssertionError(s"bad action pin: $error"), identity)

  /** The whole bundle, as a build would now write it. */
  private def browserSetup(pins: ActionPins): Steps =
    Steps.built("browsers")(
      cacheStep(pins.cache)
        .named("Cache Playwright apt packages")
        .withInputs(
          ListMap(
            "path" -> "~/.cache/chekhov-apt-archives",
            "key"  -> Expr
              .concat(
                Expr.runner("os"),
                Expr.lit("-chekhov-apt-"),
                Expr.call("hashFiles", Expr.quoted("package-lock.json")),
              )
              .render,
            "restore-keys" -> Expr.concat(Expr.runner("os"), Expr.lit("-chekhov-apt-")).render,
          )
        ),
      Step
        .uses("actions/setup-node@48b55a011bda9f5d6aeb4c2d9c7362e8dae4041e")
        .named("Set up Node")
        .withInputs(ListMap("node-version" -> "24", "cache" -> "npm")),
      Step.run(Script(Exec("npm", Word.lit("ci")))).named("npm ci"),
      Step
        .run(Script(Exec("npm", Word.lit("ci"), Word.lit("--prefix"), Word.lit("examples/vite-fixture"))))
        .named("npm ci (vite fixture)"),
      Step
        .run(Script(Exec("npm", Word.lit("ci"), Word.lit("--prefix"), Word.lit("examples/ascent-fixture"))))
        .named("npm ci (ascent fixture)"),
      Step.run(installBrowsers).named("Install Playwright browsers"),
    )

  private def stepNamed(steps: List[Step], name: String): Step =
    steps.find(_.name.contains(name)).getOrElse(throw AssertionError(s"step '$name' missing"))

  def spec = suite("a consumer's hand-written steps, rebuilt typed")(
    test("the Playwright browser install renders byte-identically") {
      assertTrue(
        installBrowsers.render ==
          """|set -euo pipefail
             |apt_mirror="${HOME}/.cache/chekhov-apt-archives"
             |mkdir -p "${apt_mirror}"
             |sudo mkdir -p /var/cache/apt/archives/partial
             |if ls "${apt_mirror}"/*.deb >/dev/null 2>&1; then
             |  echo "Seeding apt archives from ${apt_mirror} ($(ls -1 "${apt_mirror}"/*.deb | wc -l | tr -d ' ') debs)"
             |  sudo cp -n "${apt_mirror}"/*.deb /var/cache/apt/archives/ || true
             |fi
             |mkdir -p "${PLAYWRIGHT_BROWSERS_PATH}"
             |chmod +x ./scripts/install-browsers.sh
             |./scripts/install-browsers.sh chromium chromium-headless-shell firefox webkit
             |if ls /var/cache/apt/archives/*.deb >/dev/null 2>&1; then
             |  sudo cp -n /var/cache/apt/archives/*.deb "${apt_mirror}/" || true
             |  sudo chown -R "$(id -u):$(id -g)" "${apt_mirror}"
             |  echo "Apt mirror now has $(ls -1 "${apt_mirror}"/*.deb | wc -l | tr -d ' ') debs"
             |fi
             |echo "Playwright $(node -p "require('playwright/package.json').version") browsers ready"
             |""".stripMargin
      )
    },
    test("the whole bundle matches the hand-written steps, field for field") {
      val pins  = ActionPins.Defaults
      val steps = browserSetup(pins)(StepContext(ModuleNode("core"), None, matrixed = false, pins))
      val cache = stepNamed(steps, "Cache Playwright apt packages")
      val node  = stepNamed(steps, "Set up Node")
      assertTrue(
        steps.map(_.name.getOrElse("")) == List(
          "Cache Playwright apt packages",
          "Set up Node",
          "npm ci",
          "npm ci (vite fixture)",
          "npm ci (ascent fixture)",
          "Install Playwright browsers",
        ),
        cache.uses.contains(pins.cache),
        cache.`with` == ListMap(
          "path"         -> "~/.cache/chekhov-apt-archives",
          "key"          -> "${{ runner.os }}-chekhov-apt-${{ hashFiles('package-lock.json') }}",
          "restore-keys" -> "${{ runner.os }}-chekhov-apt-",
        ),
        node.uses.contains("actions/setup-node@48b55a011bda9f5d6aeb4c2d9c7362e8dae4041e"),
        node.`with` == ListMap("node-version" -> "24", "cache" -> "npm"),
        stepNamed(steps, "npm ci").run.contains("npm ci"),
        stepNamed(steps, "npm ci (vite fixture)").run.contains("npm ci --prefix examples/vite-fixture"),
        stepNamed(steps, "npm ci (ascent fixture)").run.contains("npm ci --prefix examples/ascent-fixture"),
      )
    },
    test("every step in the bundle is valid, which the hand-written version could not promise") {
      val pins  = ActionPins.Defaults
      val steps = browserSetup(pins)(StepContext(ModuleNode("core"), None, matrixed = false, pins))
      assertTrue(steps.forall(Step.validate(_).isRight))
    },
    test("nothing in the bundle used an escape hatch, so generate emits no warning") {
      val pins = ActionPins.Defaults
      val test = Capability.test.copy(extraSteps = browserSetup(pins))
      val cfg  = PlanConfig(cacheRehydrateExtraSteps = browserSetup(pins), actions = pins)
      assertTrue(Steps.rawWarnings(List(test), cfg).isEmpty)
    },
    test("the same bundle serves Verify and rehydrate, which is what the build needed two settings for") {
      val pins   = ActionPins.Defaults
      val bundle = browserSetup(pins)
      val ctx    = StepContext(ModuleNode("core"), None, matrixed = false, pins)
      // The motivating requirement from issue #46: one definition, assigned to `extraSteps` and to
      // `zipxCacheRehydrateExtraSteps`, with the two staying in step because they are the same value.
      val verify    = Capability.test.copy(extraSteps = bundle)
      val rehydrate = PlanConfig(cacheRehydrateExtraSteps = bundle, actions = pins)
      assertTrue(verify.extraSteps(ctx) == rehydrate.cacheRehydrateExtraSteps(ctx))
    },
  )
end ConsumerStepsSpec
