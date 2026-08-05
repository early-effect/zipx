package zipx.core

import zipx.shell.*
import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Opt-in Dependabot companion workflow: on Dependabot PRs, pull bumped `uses:` SHAs into the action-pins file and
  * regenerate so `zipxWorkflowCheck` converges.
  */
object ActionPinsSyncWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-action-pins-sync.yml"

  /** `Left` when a path cannot be single-quoted into [[commitScript]]: inside `'…'` there is no escape for a single
    * quote, so such a path would hand the shell a different argument list than the caller wrote.
    */
  def plan(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      actionsPath: String = ActionPinFile.DefaultPath,
      workflowPath: String = ".github/workflows/ci.yml",
  ): Either[String, Workflow] =
    commitScript(actionsPath, workflowPath).map(planWith(pins, javaVersion, runnerOs, _))

  private def planWith(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      commit: Script,
  ): Workflow =
    val setupJava = Step(
      name = Some("Setup JDK"),
      uses = Some(pins.setupJava),
      `with` = ListMap("distribution" -> "temurin", "java-version" -> javaVersion),
    )
    val setupSbt = Step(uses = Some(pins.setupSbt))
    Workflow(
      name = "zipx action-pins sync",
      on = Triggers(pullRequest = Some(BranchFilter())),
      permissions = ListMap("contents" -> "write", "pull-requests" -> "write"),
      jobs = ListMap(
        "sync" -> Job(
          name = Some("Sync action pins from Dependabot"),
          runsOn = List(runnerOs),
          `if` = Some("github.actor == 'dependabot[bot]'"),
          steps = List(
            Step(
              uses = Some(pins.checkout),
              `with` = ListMap(
                "ref"                 -> "${{ github.head_ref }}",
                "token"               -> "${{ secrets.GITHUB_TOKEN }}",
                "persist-credentials" -> "true",
              ),
            ),
            setupJava,
            setupSbt,
            Step(
              name = Some("Pull pins and regenerate"),
              run = Some("sbt zipxActionsPull"),
            ),
            Step.run(commit).named("Commit pin file and workflows").build,
          ),
        )
      ),
    )
  end planWith

  private def commitScript(actionsPath: String, workflowPath: String): Either[String, Script] =
    quotedPaths(List(actionsPath, workflowPath, DefaultPath)).map(commitScriptWith)

  private def commitScriptWith(paths: List[Word]): Script =
    Script(
      List(
        If(
          ShTest.Empty(
            Word.dquote(Word.subst(Exec.of("git", Word.lit("status") :: Word.lit("--porcelain") :: paths)))
          ),
          Block(
            Exec("echo", Word.quoted("No pin/workflow changes to commit.")),
            Exit(),
          ),
        ),
        Exec("git", Word.lit("config"), Word.lit("user.name"), Word.quoted("github-actions[bot]")),
        Exec(
          "git",
          Word.lit("config"),
          Word.lit("user.email"),
          Word.quoted("41898282+github-actions[bot]@users.noreply.github.com"),
        ),
        Exec.of("git", Word.lit("add") :: paths),
        Exec("git", Word.lit("commit"), Word.lit("-m"), Word.quoted("ci: sync zipx action pins from Dependabot")),
        Exec("git", Word.lit("push")),
      ),
      // Emits a blank line after `git push`, as the pre-DSL string did. Kept for byte parity with the committed YAML.
      trailingNewline = true,
    )
  end commitScriptWith

  private def quotedPaths(paths: List[String]): Either[String, List[Word]] =
    paths.foldRight(Right(Nil): Either[String, List[Word]]) { (path, acc) =>
      for
        rest <- acc
        word <- Word.squoteMake(path).left.map(err => s"invalid path '$path': $err")
      yield word :: rest
    }

  /** As YAML, with `# vX.Y.Z` comments annotated onto its `uses:` lines. */
  def render(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      actionsPath: String = ActionPinFile.DefaultPath,
      workflowPath: String = ".github/workflows/ci.yml",
  ): Either[String, String] =
    for
      workflow <- plan(pins, javaVersion, runnerOs, actionsPath, workflowPath)
      yaml     <- Render.render(workflow)
    yield ActionPinFile.annotateUses(yaml, pins)

end ActionPinsSyncWorkflow
