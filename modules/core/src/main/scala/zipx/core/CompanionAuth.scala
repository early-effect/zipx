package zipx.core

import zipx.shell.*
import zipx.workflow.*

import scala.collection.immutable.ListMap

/** Opt-in GitHub App installation token so companion PRs are not authored by `github-actions[bot]`.
  *
  * Neither [[AppId]] nor [[AppKey]] set keeps `GITHUB_TOKEN` (GitHub then holds `pull_request` CI for approval). Both
  * set mints before checkout and the App authors the PR. Exactly one set fails the detect step.
  *
  * Not a local `zipx-*` composite: mint runs before checkout, and `./.github/actions/…` resolves from the workspace.
  * The action is a major tag because the bot cannot push repo-root workflow SHA edits. `secrets.*` cannot appear in
  * `if:`, so detect copies the secrets into step env and writes a non-secret output.
  *
  * Job-level `env` is evaluated before steps and cannot see the mint output, so export writes `GITHUB_TOKEN` /
  * `GH_TOKEN` to `GITHUB_ENV` from a `run:` script (`EnvName` rejects the `GITHUB_` prefix).
  */
object CompanionAuth:

  /** Org secret plus the step-env copy of the same name. */
  final case class AppSecret(name: SecretName, env: EnvName, expr: Expr, quoted: Word.Dquote)

  object AppSecret:
    inline def named(inline name: String): AppSecret =
      AppSecret(SecretName(name), EnvName(name), Expr.secret(name), Word.vq(name))

  val AppId: AppSecret  = AppSecret.named("ZIPX_APP_ID")
  val AppKey: AppSecret = AppSecret.named("ZIPX_APP_PRIVATE_KEY")

  val DetectId: StepId          = StepId("zipx-app")
  val TokenStepId: StepId       = StepId("zipx-app-token")
  val PresentOutput: OutputName = OutputName("present")
  val TokenOutput: OutputName   = OutputName("token")
  val AppToken: EnvName         = EnvName("APP_TOKEN")

  val AppTokenRef: ActionRef = ActionRef("actions/create-github-app-token@v3")

  val present: Expr       = Expr.StepOutput(DetectId, PresentOutput) === Expr.quoted("true")
  val mintedToken: Expr   = Expr.StepOutput(TokenStepId, TokenOutput)
  val checkoutToken: Expr = mintedToken || Expr.secret("GITHUB_TOKEN")

  val checkoutWith: ListMap[String, String] = ListMap(
    "token"               -> checkoutToken.render,
    "persist-credentials" -> "true",
  )

  def steps: List[Step] = List(detect, mint, exportToken)

  private def detect: Step =
    Step
      .run(detectScript)
      .named("Detect GitHub App credentials")
      .withStepId(DetectId)
      .withEnvName(AppId.env, AppId.expr)
      .withEnvName(AppKey.env, AppKey.expr)
      .build

  private def mint: Step =
    Step
      .usesRef(AppTokenRef)
      .named("Mint GitHub App token")
      .withStepId(TokenStepId)
      .when(present)
      .withInput("app-id", AppId.expr)
      .withInput("private-key", AppKey.expr)
      .build

  private def exportToken: Step =
    Step
      .run(writeTokenScript)
      .named("Export GitHub App token")
      .when(present)
      .withEnvName(AppToken, mintedToken)
      .build

  private def detectScript: Script =
    Script.strict(
      If(
        ShTest.NonEmpty(AppId.quoted) && ShTest.NonEmpty(AppKey.quoted),
        Block(writePresentTrue),
        elifs = List(
          (ShTest.NonEmpty(AppId.quoted) || ShTest.NonEmpty(AppKey.quoted)) ->
            Block(
              Exec(
                "echo",
                Word.quoted("zipx: ZIPX_APP_ID and ZIPX_APP_PRIVATE_KEY must both be set, or neither."),
              ),
              Exit(ExitCode.Failure),
            )
        ),
        elseDo = Some(Block(writePresentFalse)),
      )
    )

  private def writeTokenScript: Script =
    Script.strict(
      Exec("echo", Word.dquote(Word.lit("GITHUB_TOKEN="), Word.v("APP_TOKEN")))
        .appendTo(Word.vq("GITHUB_ENV")),
      Exec("echo", Word.dquote(Word.lit("GH_TOKEN="), Word.v("APP_TOKEN"))).appendTo(Word.vq("GITHUB_ENV")),
    )

  private def writePresentTrue: Command =
    Exec("echo", Word.quoted("present=true")).appendTo(Word.vq("GITHUB_OUTPUT"))

  private def writePresentFalse: Command =
    Exec("echo", Word.quoted("present=false")).appendTo(Word.vq("GITHUB_OUTPUT"))

end CompanionAuth
