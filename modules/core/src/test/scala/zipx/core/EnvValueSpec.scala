package zipx.core

import zio.test.*
import scala.collection.immutable.ListMap

object EnvValueSpec extends ZIOSpecDefault:
  import EnvValue.secret

  def spec = suite("EnvValue")(
    test("secret / FromSecret renders the GitHub Actions secrets expression") {
      assertTrue(
        EnvValue.secret("PGP_PASSPHRASE").render == "${{ secrets.PGP_PASSPHRASE }}",
        secret"SONATYPE_USERNAME".render == "${{ secrets.SONATYPE_USERNAME }}",
        Secret("PGP_KEY_HEX").render == "${{ secrets.PGP_KEY_HEX }}",
        Secret.ref("SONATYPE_PASSWORD").render == "${{ secrets.SONATYPE_PASSWORD }}",
      )
    },
    test("FromEnv renders the env expression") {
      assertTrue(EnvValue.env("DEPLOY_ROLE").render == "${{ env.DEPLOY_ROLE }}")
    },
    test("Plain and Expr are verbatim") {
      assertTrue(
        EnvValue.plain("us-west-2").render == "us-west-2",
        EnvValue
          .plain("${{ secrets.LOOKS_LIKE_ONE }}")
          .render == "${{ secrets.LOOKS_LIKE_ONE }}", // Plain does not rewrite
        EnvValue.expr("${{ github.sha }}").render == "${{ github.sha }}",
      )
    },
    test("renderAll sorts keys deterministically") {
      val rendered = EnvValue.renderAll(
        Map(
          "Z_LAST"  -> EnvValue.plain("z"),
          "A_FIRST" -> secret"A_SECRET",
          "M_MID"   -> EnvValue.env("M"),
        )
      )
      assertTrue(
        rendered == ListMap(
          "A_FIRST" -> "${{ secrets.A_SECRET }}",
          "M_MID"   -> "${{ env.M }}",
          "Z_LAST"  -> "z",
        ),
        EnvValue.renderAll(Map.empty).isEmpty,
      )
    },
    // ---- Pathological / adversarial name validation ----
    test("empty secret name is rejected") {
      assertTrue(scala.util.Try(EnvValue.secret("")).isFailure)
    },
    test("secret names with spaces are rejected") {
      assertTrue(scala.util.Try(EnvValue.secret("PGP PASSPHRASE")).isFailure)
    },
    test("secret names that look like expressions are rejected") {
      assertTrue(
        scala.util.Try(EnvValue.secret("${{ secrets.X }}")).isFailure,
        scala.util.Try(EnvValue.secret("secrets.X")).isFailure, // dot
      )
    },
    test("secret names starting with a digit are rejected") {
      assertTrue(scala.util.Try(EnvValue.secret("1PASSWORD")).isFailure)
    },
    test("secret names with hyphens are rejected (GHA allows underscore, not hyphen in our validator)") {
      // Keep the alphabet tight: hyphens in secret *names* are uncommon and confuse YAML/shell; force underscore.
      assertTrue(scala.util.Try(EnvValue.secret("PGP-PASSPHRASE")).isFailure)
    },
    test("env names follow the same validation") {
      assertTrue(
        scala.util.Try(EnvValue.env("")).isFailure,
        scala.util.Try(EnvValue.env("bad-name")).isFailure,
        EnvValue.env("_PRIVATE").render == "${{ env._PRIVATE }}",
      )
    },
    test("underscored uppercase names (the early-effect shape) are accepted") {
      val names = List("PGP_KEY_HEX", "PGP_SECRET", "PGP_PASSPHRASE", "SONATYPE_USERNAME", "SONATYPE_PASSWORD")
      assertTrue(names.forall(n => scala.util.Try(EnvValue.secret(n)).isSuccess))
    },
    test("secret interpolator rejects a bad interpolated name") {
      val bad = "has space"
      assertTrue(scala.util.Try(secret"$bad").isFailure)
    },
  )
end EnvValueSpec
