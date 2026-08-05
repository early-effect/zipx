package zipx.core

import zio.test.*
import zipx.shell.*
import zipx.workflow.{Expr, Step}

import scala.collection.immutable.ListMap

/** Expected values are the `run:` bodies `early-effect/chekhov`'s `build.sbt` emits today. */
object ConsumerStepsSpec extends ZIOSpecDefault:

  private val aptMirrorPath: Word =
    Word.dquote(Word.vBraced("HOME"), Word.lit("/.cache/chekhov-apt-archives"))

  private val aptMirror: Word = Word.dquote(Word.vBraced("apt_mirror"))

  private val mirrorDebs: Word = Word.cat(aptMirror, Word.lit("/*.deb"))

  private val cacheDebs: Word = Word.lit("/var/cache/apt/archives/*.deb")

  private def debCount(glob: Word): Word.Subst =
    Word.subst(
      Exec("ls", Word.lit("-1"), glob) | Exec("wc", Word.lit("-l")) | Exec("tr", Word.lit("-d"), Word.squote(" "))
    )

  private def anyFilesMatch(glob: Word): ShTest = ShTest.succeeds(Exec("ls", glob).silenced)

  private val installBrowsers: Script =
    Script
      .strict(
        Assign("apt_mirror", aptMirrorPath),
        Exec("mkdir", Word.lit("-p"), aptMirror),
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
          List(
            Word.lit("chromium"),
            Word.lit("chromium-headless-shell"),
            Word.lit("firefox"),
            Word.lit("webkit"),
          ),
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
              aptMirror,
            ),
            Exec("echo", Word.dquote(Word.lit("Apt mirror now has "), debCount(mirrorDebs), Word.lit(" debs"))),
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

  private def cacheStep(pin: String) =
    Step.usesMake(pin).fold(error => throw AssertionError(s"bad action pin: $error"), identity)

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

  private val pins = ActionPins.Defaults
  private val ctx  = StepContext(ModuleNode("core"), None, matrixed = false, pins)

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
      val steps = browserSetup(pins)(ctx)
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
      assertTrue(browserSetup(pins)(ctx).forall(Step.validate(_).isRight))
    },
    test("nothing in the bundle used an escape hatch, so generate emits no warning") {
      val capability = Capability.test.copy(extraSteps = browserSetup(pins))
      val config     = PlanConfig(cacheRehydrateExtraSteps = browserSetup(pins), actions = pins)
      assertTrue(Steps.rawWarnings(List(capability), config).isEmpty)
    },
    test("one bundle serves Verify and rehydrate, which is what the build needed two settings for") {
      val bundle    = browserSetup(pins)
      val verify    = Capability.test.copy(extraSteps = bundle)
      val rehydrate = PlanConfig(cacheRehydrateExtraSteps = bundle, actions = pins)
      assertTrue(verify.extraSteps(ctx) == rehydrate.cacheRehydrateExtraSteps(ctx))
    },
  )
end ConsumerStepsSpec
