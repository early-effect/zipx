package zipx.core

import zipx.shell.*
import zipx.workflow.*

import scala.collection.immutable.ListMap

/** Opt-in GitHub App installation token so companion PRs are not authored by `github-actions[bot]`.
  *
  * Unset [[AppId]] / [[AppKey]]: checkout and `gh` keep `GITHUB_TOKEN` (GitHub holds `pull_request` CI for approval).
  * Both set: mint before checkout; the PR author is the App, a write collaborator. Exactly one set: the detect step
  * fails with a `zipx:` error rather than falling through.
  *
  * Not a local `zipx-*` composite. Mint must run before checkout, and GitHub resolves `./.github/actions/…` from the
  * workspace. The action is a major tag, like companion checkout, because the bot cannot push repo-root workflow SHA
  * edits. `secrets.*` cannot be used in `if:`; detect copies the secrets into step env and writes a non-secret output.
  *
  * Job-level `env.GITHUB_TOKEN` is evaluated before steps, so it cannot see the mint output. Export writes
  * `GITHUB_TOKEN` / `GH_TOKEN` to `GITHUB_ENV` for `gh pr create`. `EnvName` rejects the `GITHUB_` prefix, so that
  * write is a `run:` script, not `Step.withEnv`.
  */
object CompanionAuth:

  /** Org secret plus the step-env copy of the same name. [[AppSecret.named]] inlines the literal into each newtype. */
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
