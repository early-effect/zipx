package zipx.core

import zipx.workflow.*
import scala.collection.immutable.ListMap

/** Opt-in Dependabot companion workflow: on Dependabot PRs, pull bumped `uses:` SHAs into the action-pins file and
  * regenerate so `zipxWorkflowCheck` converges.
  */
object ActionPinsSyncWorkflow:

  val DefaultPath: String = ".github/workflows/zipx-action-pins-sync.yml"

  def plan(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      actionsPath: String = ActionPinFile.DefaultPath,
      workflowPath: String = ".github/workflows/ci.yml",
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
            Step(
              name = Some("Commit pin file and workflows"),
              run = Some(
                s"""|if [ -z "$$(git status --porcelain '$actionsPath' '$workflowPath' '${DefaultPath}')" ]; then
                    |  echo "No pin/workflow changes to commit."
                    |  exit 0
                    |fi
                    |git config user.name "github-actions[bot]"
                    |git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
                    |git add '$actionsPath' '$workflowPath' '${DefaultPath}'
                    |git commit -m "ci: sync zipx action pins from Dependabot"
                    |git push
                    |""".stripMargin
              ),
            ),
          ),
        )
      ),
    )
  end plan

  def render(
      pins: ActionPins,
      javaVersion: String,
      runnerOs: String,
      actionsPath: String = ActionPinFile.DefaultPath,
      workflowPath: String = ".github/workflows/ci.yml",
  ): String =
    ActionPinFile.annotateUses(
      Render.render(plan(pins, javaVersion, runnerOs, actionsPath, workflowPath)),
      pins,
    )

end ActionPinsSyncWorkflow
