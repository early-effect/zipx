package zipx.central

import zipx.core.*
import zipx.core.EnvValue.secret
import zipx.workflow.Step
import scala.collection.immutable.ListMap

/** Early-effect / Maven Central paved path for zipx.
  *
  * Composes [[EnvValue]] secret refs, a GPG-import `extraSteps` block, a `publishSigned` capability (same-name replace
  * of the built-in publish), and a post-wave `sonaRelease` once-job. Org secrets are referenced **by name only** —
  * values come from the `early-effect` GitHub org (inherited by public repos).
  *
  * `publishSigned` writes to `target/sona-staging` on each job's runner; those trees are uploaded as artifacts and
  * merged on the `central-release` job before `sonaRelease` (peers run publish+release in one job; zipx fans out).
  *
  * {{{
  * // build.sbt — ZipxCentral is re-exported from zipx-sbt's autoImport
  * zipxCapabilities += ZipxCentral.publishSigned
  * zipxCapabilities += ZipxCentral.releaseOnce
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

  /** Artifact name prefix; each publish job uploads `sona-staging-publish-<module>`. */
  val StagingArtifactPrefix: String = "sona-staging-"

  def stagingArtifactName(moduleId: String): String =
    s"${StagingArtifactPrefix}publish-$moduleId"

  /** Import the CI signing key from the base64-encoded `PGP_SECRET` org secret (same recipe as peer release.yml).
    *
    * Use a plain `$PGP_SECRET` (not `$$`). This string is not Scala-interpolated; a doubled `$` survives into the
    * workflow YAML and bash expands `$$` to the PID, which poisons `base64 --decode`.
    */
  val gpgImportSteps: StepContext => List[Step] = _ =>
    List(
      Step(
        name = Some("Import signing key"),
        env = ListMap("PGP_SECRET" -> secret"PGP_SECRET".render),
        run = Some(
          """mkdir -p ~/.gnupg && chmod 700 ~/.gnupg
            |echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
            |echo "pinentry-mode loopback"   >> ~/.gnupg/gpg.conf
            |gpgconf --kill gpg-agent || true
            |echo "$PGP_SECRET" | base64 --decode | gpg --batch --import""".stripMargin
        ),
      )
    )

  /** After `publishSigned`, upload this job's `target/sona-staging` for the release job to merge. */
  val uploadStagingSteps: StepContext => List[Step] = ctx =>
    List(
      Step(
        name = Some("Upload sona staging"),
        uses = Some(ctx.actions.uploadArtifact),
        `with` = ListMap(
          "name"              -> stagingArtifactName(ctx.node.id),
          "path"              -> StagingDir,
          "if-no-files-found" -> "error",
        ),
      )
    )

  /** Before `sonaRelease`, download every publish job's staging tree into [[StagingDir]]. */
  val downloadStagingSteps: StepContext => List[Step] = ctx =>
    List(
      Step(
        name = Some("Download sona staging"),
        uses = Some(ctx.actions.downloadArtifact),
        `with` = ListMap(
          "pattern"        -> s"$StagingArtifactPrefix*",
          "path"           -> StagingDir,
          "merge-multiple" -> "true",
        ),
      )
    )

  /** Replaces [[Capability.publish]]: dependency-ordered `publishSigned`, release-gated, with org signing env + GPG
    * import + staging artifact upload.
    */
  val publishSigned: Capability =
    Capability.publish.copy(
      command = n =>
        val task = "publishSigned"
        if n.crossScalaVersions.sizeIs > 1 then s"+${n.id}/$task" else s"${n.id}/$task"
      ,
      env = signingEnv,
      extraSteps = gpgImportSteps,
      postSteps = uploadStagingSteps,
    )

  /** After the full publish wave: merge staging artifacts and run `sonaRelease`. */
  val releaseOnce: Capability =
    Capability.once(
      name = "central-release",
      command = "sonaRelease",
      phase = Phase.Publish,
      gate = Gate.OnReleaseTag,
      needsCapabilities = List("publish"),
      env = signingEnv,
      extraSteps = ctx => downloadStagingSteps(ctx) ++ gpgImportSteps(ctx),
    )

end ZipxCentral
