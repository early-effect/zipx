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

  /** Import the CI signing key from the base64-encoded `PGP_SECRET` org secret (same recipe as peer release.yml). */
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
            |echo "$$PGP_SECRET" | base64 --decode | gpg --batch --import""".stripMargin,
        ),
      ),
    )

  /** Replaces [[Capability.publish]]: dependency-ordered `publishSigned`, release-gated, with org signing env + GPG import. */
  val publishSigned: Capability =
    Capability.publish.copy(
      command = n =>
        val task = "publishSigned"
        if n.crossScalaVersions.sizeIs > 1 then s"+${n.id}/$task" else s"${n.id}/$task",
      env = signingEnv,
      extraSteps = gpgImportSteps,
    )

  /** After the full publish wave: a single `sonaRelease` job that uploads the Central Portal bundle. */
  val releaseOnce: Capability =
    Capability.once(
      name = "central-release",
      command = "sonaRelease",
      phase = Phase.Publish,
      gate = Gate.OnReleaseTag,
      needsCapabilities = List("publish"),
      env = signingEnv,
      extraSteps = gpgImportSteps, // sonaRelease may need the key still present; cheap to re-import
    )

end ZipxCentral
