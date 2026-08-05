package zipx.central

import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.shell.{Exec, Script, Word}
import zipx.workflow.{Expr, Step}
import scala.collection.immutable.ListMap

/** Early-effect / Maven Central paved path for zipx.
  *
  * Prefer [[release]] (Aggregate: one job, `publishSigned; sonaRelease`) for typical library builds. Use
  * [[publishSigned]] + [[releaseOnce]] when you need Graph fan-out with staging artifacts across jobs.
  *
  * Org secrets are referenced **by name only**; values come from the `early-effect` GitHub org.
  *
  * {{{
  * // Aggregate (default / dogfood)
  * zipxCapabilities += ZipxCentral.release
  *
  * // Graph escape hatch
  * zipxCapabilities ++= Seq(ZipxCentral.publishSigned, ZipxCentral.releaseOnce)
  * }}}
  */
object ZipxCentral:

  /** The five early-effect org secrets used for CI-only Central publishing. */
  val OrgSecretNames: List[String] =
    List("PGP_KEY_HEX", "PGP_SECRET", "PGP_PASSPHRASE", "SONATYPE_USERNAME", "SONATYPE_PASSWORD")

  /** Job-level env for signed publish / `sonaRelease` (excludes `PGP_SECRET`, which is step-scoped on the import). */
  val signingEnv: Map[String, EnvValue] = Map(
    "PGP_KEY_HEX"       -> secret"PGP_KEY_HEX",
    "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
    "SONATYPE_USERNAME" -> secret"SONATYPE_USERNAME",
    "SONATYPE_PASSWORD" -> secret"SONATYPE_PASSWORD",
  )

  /** Local staging directory used by sbt `localStaging` / `sonaRelease`. */
  val StagingDir: String = "target/sona-staging"

  /** Artifact name prefix; each Graph publish job uploads `sona-staging-publish-<module>`. */
  val StagingArtifactPrefix: String = "sona-staging-"

  def stagingArtifactName(moduleId: String): String =
    s"${StagingArtifactPrefix}publish-$moduleId"

  private val gnupgHome: Word = Word.lit("~/.gnupg")

  /** Import the CI signing key from the base64-encoded `PGP_SECRET` org secret (same recipe as peer release.yml).
    *
    * This used to carry a warning comment where a type should be: the body was a plain string, so a doubled `$$`
    * survived into the YAML and bash expanded it to the PID, poisoning `base64 --decode`. `Word.vq("PGP_SECRET")` is
    * that expansion, and there is no second way to spell it.
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

  /** After Graph `publishSigned`, upload this job's `target/sona-staging` for the release job to merge. */
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

  /** Before Graph `sonaRelease`, download every publish job's staging tree into [[StagingDir]]. */
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

  /** Aggregate Central release: one job with GPG import + `publishSigned; sonaRelease`. Replaces the built-in `publish`
    * capability (same name). Prefer this over [[publishSigned]] + [[releaseOnce]] unless you need Graph fan-out.
    *
    * Uses [[zipx.core.CapabilityScope.Once]] (not Aggregate join) so the root command runs once rather than being
    * repeated per publishing module.
    */
  val release: Capability =
    Capability.once(
      name = "publish",
      command = "publishSigned; sonaRelease",
      phase = Phase.Publish,
      gate = Gate.OnReleaseTag,
      env = signingEnv,
      extraSteps = gpgImportSteps,
    )

  /** Graph escape hatch: replaces [[zipx.core.Capability.publishGraph]] with dependency-ordered `publishSigned`,
    * staging artifact upload, and org signing env. Pair with [[releaseOnce]].
    */
  val publishSigned: Capability =
    Capability.publishGraph.copy(
      command = n =>
        val task = "publishSigned"
        if n.crossScalaVersions.sizeIs > 1 then s"+${n.id}/$task" else s"${n.id}/$task"
      ,
      env = signingEnv,
      extraSteps = gpgImportSteps,
      postSteps = uploadStagingSteps,
    )

  /** After the Graph publish wave: merge staging artifacts and run `sonaRelease`. */
  val releaseOnce: Capability =
    Capability.once(
      name = "central-release",
      command = "sonaRelease",
      phase = Phase.Publish,
      gate = Gate.OnReleaseTag,
      needsCapabilities = List("publish"),
      env = signingEnv,
      // Composition, not a hand-threaded lambda: this is what `Steps.++` exists for.
      extraSteps = downloadStagingSteps ++ gpgImportSteps,
    )

end ZipxCentral
