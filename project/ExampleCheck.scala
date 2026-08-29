package zipx

import zipx.core.*
import zipx.shell.{Assign, Exec, Script, Word}
import zipx.workflow.Step

/** Post-steps on Aggregate `test`: publish the in-dev plugin locally, then prove `examples/monorepo` still generates
  * the YAML committed beside it.
  *
  * The version-updates companion uses [[companionSteps]] to regenerate that example (including
  * `examples/monorepo/.github/workflows/`) onto the catalog PR. Those files are not repo-root `.github/workflows/`, so
  * `GITHUB_TOKEN` can commit them. Root `ci.yml` still needs a human `zipxWorkflowGenerate`.
  *
  * Lives in the meta-build because it is dogfood wiring for *this* repo, not published API. A consumer wanting the same
  * shape writes the same thing in its own `project/`.
  */
object ExampleCheck:

  /** Where the root build writes the version for the example to consume, relative to the repo root (which is every
    * step's working directory unless it says otherwise).
    *
    * Read by `build.sbt`'s `zipxWriteVersion`, which writes the file. Example steps read it back as
    * `../../target/zipx-version.txt`, since they run in [[ExampleDir]], two levels down.
    *
    * The script spells its path as a literal rather than deriving it from this `val`: neotype's validation needs a
    * compile-time known `String`, and a reference to a `val` (or even an `inline val`) does not survive folding through
    * `Word.quoted`. Drift between the two is not silent: the step's leading `test -f` fails the job naming the path it
    * could not find.
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
          // `../../`, because this step's working-directory is the example: the publish step wrote the version file at
          // the repo root, two levels up.
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

  /** Regenerates the example with the in-dev plugin. Used by the version-updates companion so Action pin peels do not
    * require a human to `publishLocal` locally. Nested `.github/workflows/` is committed; repo-root workflows are not.
    */
  val companionSteps: Seq[Step] = Seq(publishLocal.build, generateExample.build)

  /** Publish the whole in-dev graph and export `ZIPX_CLI_VERSION` so Sunday `cs launch` can resolve zipx-cli plus
    * zipx-core / zipx-syntax at the same dynver. `cli/publishLocal` alone is not enough.
    *
    * Do not bake dynver into committed `zipx-ci.env`: that file is a `zipxWorkflowCheck` input, and the next SHA would
    * fail. `GITHUB_ENV` lasts for later steps in this job only.
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

  /** Runs after `test; plugin/scripted` on the Aggregate test job. */
  val steps: Steps =
    Steps.built("publish-local")(publishLocal) ++ Steps.built("example-check")(checkExample)

end ExampleCheck
