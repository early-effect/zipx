package zipx.core

import neotype.unwrap

/** How a [[ReleaseWorkflow]] authenticates; each secret is exported as an env var of the same name. `zipxRelease`
  * refuses before any upload when the host has neither sbt credentials nor these vars.
  */
enum RegistryCredentials:
  case Anonymous
  case UserPassword(username: EnvValue, password: EnvValue)
  case Bearer(token: EnvValue)

  /** Literals (a GitHub Packages owner used as the username) are not exported. */
  def env: Map[String, EnvValue] =
    parts.flatMap(RegistryCredentials.binding).toMap

  /** Secret and env names the missing-credentials error quotes, in declaration order. */
  def described: List[String] =
    parts.flatMap(RegistryCredentials.envName)

  def supplied(env: Map[String, String]): Boolean = this match
    case Anonymous => false
    case _         => parts.forall(part => RegistryCredentials.read(part, env).isDefined)

  private def parts: List[EnvValue] = this match
    case Anonymous                        => Nil
    case UserPassword(username, password) => List(username, password)
    case Bearer(token)                    => List(token)
end RegistryCredentials

object RegistryCredentials:

  /** `${{ github.token }}` is not a secret name; the runner exports it as this var. Without it the metadata GET is
    * anonymous and GitHub Packages answers 401.
    */
  val GithubTokenEnv: String = "GITHUB_TOKEN"

  def read(value: EnvValue, env: Map[String, String]): Option[String] = value match
    case EnvValue.Plain(text) => Option.when(text.nonEmpty)(text)
    case other                => envName(other).flatMap(env.get).filter(_.nonEmpty)

  def envName(value: EnvValue): Option[String] = value match
    case EnvValue.FromSecret(name)              => Some(name.unwrap)
    case EnvValue.FromEnv(name)                 => Some(name.unwrap)
    case other if other == EnvValue.githubToken => Some(GithubTokenEnv)
    case _                                      => None

  private def binding(value: EnvValue): Option[(String, EnvValue)] =
    envName(value).map(_ -> value)
end RegistryCredentials
