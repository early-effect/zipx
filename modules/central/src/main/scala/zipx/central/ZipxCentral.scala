package zipx.central

import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{Expr, Step}
import scala.collection.immutable.ListMap

/** Early-effect / Maven Central paved path for zipx. Secrets are referenced by name only; the values live in the
  * `early-effect` GitHub org.
  *
  * {{{
  * // one job: publishSigned; sonaRelease
  * zipxCapabilities += ZipxCentral.release
  *
  * // Graph fan-out, staging artifacts merged across jobs
  * zipxCapabilities ++= Seq(ZipxCentral.publishSigned, ZipxCentral.releaseOnce)
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

  /** Where sbt's `localStaging` / `sonaRelease` expect the bundle. */
  val StagingDir: String = "target/sona-staging"

  val StagingArtifactPrefix: String = "sona-staging-"

  def stagingArtifactName(moduleId: String): String =
    s"${StagingArtifactPrefix}publish-$moduleId"

  private val gnupgHome: Word = Word.lit("~/.gnupg")

  /** Imports the CI signing key from the base64-encoded `PGP_SECRET` secret, the same recipe as the peer release.yml.
    */
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

  /** Uploads this job's staging tree for [[downloadStagingSteps]] to merge. */
  val uploadStagingSteps: Steps = Steps.one("upload-staging") { ctx =>
    Step(
      name = Some("Upload sona staging"),
      uses = Some(ctx.actions.uploadArtifact),
      `with` = ListMap(
        "name"              -> stagingArtifactName(ctx.node.id),
        "path"              -> StagingDir,
        "if-no-files-found" -> "error",
      ),
    )
  }

  /** Merges every publish job's staging tree into one [[StagingDir]], which is what `sonaRelease` uploads. */
  val downloadStagingSteps: Steps = Steps.one("download-staging") { ctx =>
    Step(
      name = Some("Download sona staging"),
      uses = Some(ctx.actions.downloadArtifact),
      `with` = ListMap(
        "pattern"        -> s"$StagingArtifactPrefix*",
        "path"           -> StagingDir,
        "merge-multiple" -> "true",
      ),
    )
  }

  /** Named `publish`, so it replaces the built-in capability rather than adding a second one.
    * [[zipx.core.CapabilityScope.Once]] rather than an Aggregate join, so the root command runs once instead of once
    * per publishing module.
    */
  val release: Capability =
    Capability.once(
      name = Capability.PublishName,
      command = SbtCommand("publishSigned; sonaRelease"),
      phase = Phase.Publish,
      gate = Gate.OnReleaseTag,
      env = signingEnv,
      extraSteps = gpgImportSteps,
    )

  /** Pair with [[releaseOnce]]: this publishes per module, that merges the staging trees and releases once. */
  val publishSigned: Capability =
    Capability.publishGraph.copy(
      command = n => Some(SbtCommand.crossModule(n, SbtCommand("publishSigned"))),
      env = signingEnv,
      extraSteps = gpgImportSteps,
      postSteps = uploadStagingSteps,
    )

  val releaseOnce: Capability =
    Capability.once(
      name = CapabilityName("central-release"),
      command = SbtCommand("sonaRelease"),
      phase = Phase.Publish,
      gate = Gate.OnReleaseTag,
      needsCapabilities = List(Capability.PublishName),
      env = signingEnv,
      extraSteps = downloadStagingSteps ++ gpgImportSteps,
    )

end ZipxCentral
