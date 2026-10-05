package zipx.central

import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{Expr, Step}

/** Secrets are referenced by name only; the values live in the `early-effect` GitHub org.
  *
  * {{{
  * zipxCapabilities += ZipxCentral.release
  * zipxReleaseWorkflow := Some(ZipxCentral.releases)
  * }}}
  */
object ZipxCentral:

  val OrgSecretNames: List[String] =
    List("PGP_KEY_HEX", "PGP_SECRET", "PGP_PASSPHRASE", "SONATYPE_USERNAME", "SONATYPE_PASSWORD")

  /** No `PGP_SECRET`: that one is step-scoped on the key import, so the decoded key is not in every job's environment.
    */
  val signingEnv: Map[String, EnvValue] = Map(
    "PGP_KEY_HEX"       -> secret"PGP_KEY_HEX",
    "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
    "SONATYPE_USERNAME" -> secret"SONATYPE_USERNAME",
    "SONATYPE_PASSWORD" -> secret"SONATYPE_PASSWORD",
  )

  private val gnupgHome: Word = Word.lit("~/.gnupg")

  val gpgImportSteps: Steps = Steps.built("gpg-import")(
    Step
      .run(
        Script(
          Exec("mkdir", Word.lit("-p"), gnupgHome) && Exec("chmod", Word.lit("700"), gnupgHome),
          Exec("echo", Word.quoted("allow-loopback-pinentry")).appendTo(Word.lit("~/.gnupg/gpg-agent.conf")),
          // The trailing pad keeps this redirect aligned with the one above it, as the peer release.yml has it.
          Exec("echo", Word.cat(Word.quoted("pinentry-mode loopback"), Word.lit("  ")))
            .appendTo(Word.lit("~/.gnupg/gpg.conf")),
          Exec("gpgconf", Word.lit("--kill"), Word.lit("gpg-agent")) || Exec("true"),
          Exec("echo", Word.vq("PGP_SECRET")) |
            Exec("base64", Word.lit("--decode")) |
            Exec("gpg", Word.lit("--batch"), Word.lit("--import")),
        )
      )
      .named("Import signing key")
      .withEnv("PGP_SECRET", Expr.secret("PGP_SECRET"))
  )

  /** Named `publish`, so it replaces the built-in capability rather than adding a second one. Wire-form for tests and
    * docs; the sbt plugin's `ZipxCentral.release` rebuilds the same shape from real keys.
    */
  val release: Capability =
    Capability.publish
      .runningEachCross(SbtCommand.unsafeTask("publishSigned"))
      .thenOnce(SbtCommand.unsafeCommand("sonaRelease"))
      .withEnv(signingEnv)
      .withExtraSteps(gpgImportSteps)

  /** Use when the root `.aggregate` is exactly the publish set; prefer [[release]] for projectMatrix or skipped rows.
    * The sbt plugin rebuilds this from real keys.
    */
  val releaseRoot: Capability =
    Capability.once(
      name = Capability.PublishName,
      command = SbtCommand.session(SbtCommand.unsafeTask("publishSigned"), SbtCommand.unsafeCommand("sonaRelease")),
      phase = Phase.Publish,
      gate = Gate.OnReleaseTag,
      env = signingEnv,
      extraSteps = gpgImportSteps,
    )

  val releases: ReleaseWorkflow = ReleaseWorkflow(
    registry = ArtifactRegistry.MavenCentral,
    env = signingEnv,
    steps = gpgImportSteps,
    credentials = RegistryCredentials.UserPassword(secret"SONATYPE_USERNAME", secret"SONATYPE_PASSWORD"),
  )

  /** Central snapshots take no signature. */
  val snapshotEnv: Map[String, EnvValue] = Map(
    "SONATYPE_USERNAME" -> secret"SONATYPE_USERNAME",
    "SONATYPE_PASSWORD" -> secret"SONATYPE_PASSWORD",
  )

  val snapshots: Capability = Capability.snapshots().withEnv(snapshotEnv)

  inline def pullRequestSnapshots(inline label: String): Capability =
    Capability.pullRequestSnapshots(zipx.workflow.ExprLiteral(label.trim)).withEnv(snapshotEnv)

end ZipxCentral
