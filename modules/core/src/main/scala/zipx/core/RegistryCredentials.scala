package zipx.core

import neotype.unwrap

/** How a [[ReleaseWorkflow]] authenticates. The workflow exports each secret as an env var of the same name.
  * `zipxRelease` refuses before any upload when the host has neither sbt credentials nor these vars. A `file:` registry
  * is [[RegistryCredentials.Anonymous]].
  */
enum RegistryCredentials:
  case Anonymous
  case UserPassword(username: EnvValue, password: EnvValue)
  case Bearer(token: EnvValue)

  /** Env bindings for the release job. Literals (a GitHub Packages owner used as the username) are not exported. */
  def env: Map[String, EnvValue] =
    parts.flatMap(RegistryCredentials.binding).toMap

  /** Secret and env names the missing-credentials error quotes, in declaration order. */
  def described: List[String] =
    parts.flatMap(RegistryCredentials.envName)

  /** Every declared part is available: a non-empty literal, or a non-empty env var. Anonymous is never supplied. */
  def supplied(env: Map[String, String]): Boolean = this match
    case Anonymous => false
    case _         => parts.forall(part => RegistryCredentials.read(part, env).isDefined)

  private def parts: List[EnvValue] = this match
    case Anonymous                        => Nil
    case UserPassword(username, password) => List(username, password)
    case Bearer(token)                    => List(token)
end RegistryCredentials

object RegistryCredentials:

  def read(value: EnvValue, env: Map[String, String]): Option[String] = value match
    case EnvValue.Plain(text)      => Option.when(text.nonEmpty)(text)
    case EnvValue.FromSecret(name) => env.get(name.unwrap).filter(_.nonEmpty)
    case EnvValue.FromEnv(name)    => env.get(name.unwrap).filter(_.nonEmpty)
    case _                         => None

  def envName(value: EnvValue): Option[String] = value match
    case EnvValue.FromSecret(name) => Some(name.unwrap)
    case EnvValue.FromEnv(name)    => Some(name.unwrap)
    case _                         => None

  private def binding(value: EnvValue): Option[(String, EnvValue)] =
    envName(value).map(_ -> value)
end RegistryCredentials
