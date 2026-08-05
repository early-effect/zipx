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
    // A name written as a literal is checked while the build compiles, so these assert on a compile error rather than
    // on a caught exception. The runtime half of each rule is `secretMake` / `envMake`, below.
    test("a malformed secret name written as a literal does not compile") {
      for
        empty  <- typeCheck("""zipx.core.EnvValue.secret("")""")
        space  <- typeCheck("""zipx.core.EnvValue.secret("PGP PASSPHRASE")""")
        expr   <- typeCheck("""zipx.core.EnvValue.secret("${{ secrets.X }}")""")
        dotted <- typeCheck("""zipx.core.EnvValue.secret("secrets.X")""")
        digit  <- typeCheck("""zipx.core.EnvValue.secret("1PASSWORD")""")
        // Keep the alphabet tight: hyphens in secret *names* are uncommon and confuse YAML/shell; force underscore.
        hyphen <- typeCheck("""zipx.core.EnvValue.secret("PGP-PASSPHRASE")""")
      yield assertTrue(empty.isLeft, space.isLeft, expr.isLeft, dotted.isLeft, digit.isLeft, hyphen.isLeft)
    },
    test("a malformed name read at runtime comes back as a Left") {
      assertTrue(
        EnvValue.secretMake("").isLeft,
        EnvValue.secretMake("PGP PASSPHRASE").isLeft,
        EnvValue.secretMake("${{ secrets.X }}").isLeft,
        EnvValue.secretMake("secrets.X").isLeft,
        EnvValue.secretMake("1PASSWORD").isLeft,
        EnvValue.secretMake("PGP-PASSPHRASE").isLeft,
        EnvValue.secretMake("PGP_PASSPHRASE").map(_.render).contains("${{ secrets.PGP_PASSPHRASE }}"),
      )
    },
    test("env names follow the same validation") {
      for
        empty  <- typeCheck("""zipx.core.EnvValue.env("")""")
        hyphen <- typeCheck("""zipx.core.EnvValue.env("bad-name")""")
      yield assertTrue(
        empty.isLeft,
        hyphen.isLeft,
        EnvValue.envMake("").isLeft,
        EnvValue.envMake("bad-name").isLeft,
        EnvValue.env("_PRIVATE").render == "${{ env._PRIVATE }}",
      )
    },
    test("underscored uppercase names (the early-effect shape) are accepted") {
      val names = List("PGP_KEY_HEX", "PGP_SECRET", "PGP_PASSPHRASE", "SONATYPE_USERNAME", "SONATYPE_PASSWORD")
      assertTrue(names.forall(n => EnvValue.secretMake(n).isRight))
    },
    test("the secret interpolator carries the validation, so a runtime name does not compile") {
      // `secret"…"` is `inline` all the way down: an interpolation of compile-time-known parts is folded and checked,
      // and one splicing runtime data is a compile error naming the input instead of a silent runtime check.
      for
        runtime <- typeCheck("""val bad = "has space"; zipx.core.EnvValue.secret(StringContext("").s(bad))""")
        literal <- typeCheck("""zipx.core.EnvValue.secret("has space")""")
      yield assertTrue(
        runtime.isLeft,
        literal.isLeft,
        // The supported route for a name assembled at runtime.
        EnvValue.secretMake("has space").isLeft,
      )
    },
  )
end EnvValueSpec
