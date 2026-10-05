package zipx

import zipx.core.*
import zipx.shell.{Assign, Exec, Script, Word}
import zipx.workflow.Step

/** Post-steps on Aggregate `test`: publish the in-dev plugin locally, then prove `examples/monorepo` still generates
  * the YAML committed beside it. Dogfood wiring for this repo, not published API.
  *
  * [[companionSteps]] regenerates the example onto the catalog PR. Its nested `.github/workflows/` is not repo-root, so
  * `GITHUB_TOKEN` can commit it; root `ci.yml` still needs a human `zipxWorkflowGenerate`.
  */
object ExampleCheck:

  /** Relative to the repo root; `build.sbt`'s `zipxWriteVersion` writes it. The scripts spell the path as a literal
    * because neotype needs a compile-time `String` and a `val` does not fold through `Word.quoted`. Drift is not
    * silent: the step's leading `test -f` fails naming the path.
    */
  val VersionFile: String = "target/zipx-version.txt"

  val ExampleDir: String = "examples/monorepo"

  private val publishLocal =
    Step
      .run(
        Script.strict(
          SbtCommand.session(SbtCommand.unsafeTask("publishLocal"), SbtCommand.unsafeTask("zipxWriteVersion")).render
        )
      )
      .named("Publish zipx locally")

  private def exampleRun(echo: Word.Lit, task: Word.Squote) =
    Step
      .run(
        Script.strict(
          // The step runs in the example, two levels below the repo root where the version file is.
          Exec("test", Word.lit("-f"), Word.quoted("../../target/zipx-version.txt")),
          Assign("ZIPX_VERSION", Word.subst(Exec("cat", Word.quoted("../../target/zipx-version.txt")))),
          // `dquote` of a literal plus a var ref, not `quoted("… $ZIPX_VERSION")`: a literal escapes its `$`, which
          // would echo the variable's name instead of its value.
          Exec("echo", Word.dquote(echo, Word.v("ZIPX_VERSION"))),
          Exec("sbt", Word.dquote(Word.lit("-Dzipx.version="), Word.v("ZIPX_VERSION")), task),
        )
      )
      .in(ExampleDir)

  private val checkExample =
    exampleRun(Word.lit("Checking examples/monorepo against zipx "), Word.squote("zipxWorkflowCheck"))
      .named("Check example workflow")

  private val generateExample =
    exampleRun(Word.lit("Generating examples/monorepo against zipx "), Word.squote("zipxWorkflowGenerate"))
      .named("Generate example workflow")

  /** Lets the version-updates companion regenerate the example with the in-dev plugin, with no human `publishLocal`. */
  val companionSteps: Seq[Step] = Seq(publishLocal.build, generateExample.build)

  /** Publishes the whole in-dev graph, not just `cli`, so `cs launch` resolves zipx-cli and its zipx deps at one
    * version. The version goes to `GITHUB_ENV`, not the committed `zipx-ci.env` that `zipxWorkflowCheck` reads.
    */
  val companionPreSteps: Seq[Step] = Seq(
    Step
      .run(
        Script.strict(
          SbtCommand
            .session(SbtCommand.unsafeTask("publishLocal"), SbtCommand.unsafeTask("zipxWriteVersion"))
            .render,
          Exec("test", Word.lit("-f"), Word.quoted("target/zipx-version.txt")),
          Assign("ZIPX_CLI_VERSION", Word.subst(Exec("cat", Word.quoted("target/zipx-version.txt")))),
          Exec("echo", Word.dquote(Word.lit("ZIPX_CLI_VERSION="), Word.v("ZIPX_CLI_VERSION")))
            .appendTo(Word.vq("GITHUB_ENV")),
        )
      )
      .named("Publish zipx-cli locally")
      .build
  )

  val steps: Steps =
    Steps.built("publish-local")(publishLocal) ++ Steps.built("example-check")(checkExample)

end ExampleCheck
