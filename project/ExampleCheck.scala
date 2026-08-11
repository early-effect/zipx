package zipx

import zipx.core.*
import zipx.shell.{Assign, Exec, Script, Word}
import zipx.workflow.Step

/** Post-steps on Aggregate `test`: publish the in-dev plugin locally, then prove `examples/monorepo` still generates
  * the YAML committed beside it.
  *
  * Runs after the job command (`test; plugin/scripted`), so unit/IT and scripted fail first. A named
  * [[zipx.core.Steps]] bundle rather than a bare lambda, so escape-hatch use reaches `zipxWorkflowGenerate`'s warning
  * (see `Steps.rawWarnings`).
  *
  * Lives in the meta-build because it is dogfood wiring for *this* repo, not published API. A consumer wanting the same
  * shape writes the same thing in its own `project/`.
  */
object ExampleCheck:

  /** Where the root build writes the version for the example to consume, relative to the repo root (which is every
    * step's working directory unless it says otherwise).
    *
    * Read by `build.sbt`'s `zipxWriteVersion`, which writes the file. [[checkExampleSteps]] reads it back as
    * `../../target/zipx-version.txt`, since that step runs in [[ExampleDir]], two levels down.
    *
    * The script spells its path as a literal rather than deriving it from this `val`: neotype's validation needs a
    * compile-time known `String`, and a reference to a `val` (or even an `inline val`) does not survive folding through
    * `Word.quoted`. Drift between the two is not silent: the step's leading `test -f` fails the job naming the path it
    * could not find.
    */
  val VersionFile: String = "target/zipx-version.txt"

  val ExampleDir: String = "examples/monorepo"

  /** Publishes every zipx artifact to the local Ivy cache and records the version.
    *
    * Two commands in one sbt session rather than two steps: `zipxWriteVersion` is only meaningful for the artifacts
    * `publishLocal` just wrote, and one session is one JVM start instead of two.
    */
  private val publishLocalSteps: Steps = Steps.built("publish-local")(
    Step
      .run(
        Script.strict(
          SbtCommand.session(SbtCommand.unsafeTask("publishLocal"), SbtCommand.unsafeTask("zipxWriteVersion")).render
        )
      )
      .named("Publish zipx locally")
  )

  /** Generates the example's workflow with the in-dev plugin and fails on any drift.
    *
    * `zipxWorkflowCheck` rather than `zipxWorkflowGenerate` plus a `git diff`: the task exists to report drift with a
    * message naming the file, and it is what a consumer runs.
    *
    * The version reaches the example's `project/plugins.sbt` as a system property, read there via
    * `sys.props.getOrElse("zipx.version", …)`. Through a file rather than captured sbt stdout, which carries log lines;
    * the plugin's own `zipxAffectedModules` hands off through `target/zipx-affected.json` for the same reason.
    */
  private val checkExampleSteps: Steps = Steps.built("example-check")(
    Step
      .run(
        Script.strict(
          // `../../`, because this step's working-directory is the example: the publish step wrote the version file at
          // the repo root, two levels up.
          Exec("test", Word.lit("-f"), Word.quoted("../../target/zipx-version.txt")),
          Assign("ZIPX_VERSION", Word.subst(Exec("cat", Word.quoted("../../target/zipx-version.txt")))),
          // `dquote` of a literal plus a var ref, not `quoted("… $ZIPX_VERSION")`: a literal escapes its `$`, which
          // would echo the variable's name instead of its value.
          Exec("echo", Word.dquote(Word.lit("Checking examples/monorepo against zipx "), Word.v("ZIPX_VERSION"))),
          Exec(
            "sbt",
            Word.dquote(Word.lit("-Dzipx.version="), Word.v("ZIPX_VERSION")),
            Word.squote("zipxWorkflowCheck"),
          ),
        )
      )
      .named("Check example workflow")
      .in("examples/monorepo")
  )

  /** Runs after `test; plugin/scripted` on the Aggregate test job. */
  val steps: Steps = publishLocalSteps ++ checkExampleSteps

end ExampleCheck
