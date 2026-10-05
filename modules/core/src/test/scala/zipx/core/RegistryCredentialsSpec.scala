package zipx.core

import zio.test.*

object RegistryCredentialsSpec extends ZIOSpecDefault:

  /** `${{ github.token }}` reaches sbt as `GITHUB_TOKEN`, and the lookup must send it: Packages answers 401 to a
    * request that declares the token and then omits it.
    */
  private val packages =
    RegistryCredentials.UserPassword(EnvValue.plain("early-effect"), EnvValue.githubToken)

  def spec = suite("RegistryCredentials")(
    test("a github token credential is the GITHUB_TOKEN already in the job environment") {
      val env = Map("GITHUB_TOKEN" -> "ghs_abc")
      assertTrue(
        RegistryCredentials.read(EnvValue.githubToken, env).contains("ghs_abc"),
        packages.supplied(env),
        packages.env.get("GITHUB_TOKEN").contains(EnvValue.githubToken),
        packages.described.contains("GITHUB_TOKEN"),
        !packages.supplied(Map.empty),
      )
    },
    test("a named secret is that env var, and a literal username is not exported") {
      val creds = RegistryCredentials.UserPassword(EnvValue.plain("early-effect"), EnvValue.secret("GH_PACKAGES_TOKEN"))
      assertTrue(
        RegistryCredentials
          .read(EnvValue.secret("GH_PACKAGES_TOKEN"), Map("GH_PACKAGES_TOKEN" -> "ghp_x"))
          .contains(
            "ghp_x"
          ),
        creds.supplied(Map("GH_PACKAGES_TOKEN" -> "ghp_x")),
        creds.env.get("GH_PACKAGES_TOKEN").contains(EnvValue.secret("GH_PACKAGES_TOKEN")),
        !creds.env.contains("early-effect"),
        creds.described == List("GH_PACKAGES_TOKEN"),
      )
    },
  )
end RegistryCredentialsSpec
