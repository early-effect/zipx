// Lives in project/ (the meta-build) rather than build.sbt, which cannot hold top-level classes cleanly.
//
// `roleSecret` is an `EnvValue`, not a `String`, so the `secret"…"` interpolator checks each name while this file
// compiles. A name assembled at runtime cannot be, and would have to go through `EnvValue.secretMake`.

import zipx.core.EnvValue
import zipx.core.EnvValue.secret

/** One deploy destination. zipx knows nothing about clouds or tiers; this shape is entirely user-defined. */
final case class DeployEnv(
    name: String,
    ghEnvironment: Option[String],
    region: String,
    roleSecret: EnvValue,
    tier: String,
)

object DeployEnv:
  /** `prod` carries a GitHub Environment, which is what gates it behind an approval. */
  val all: List[DeployEnv] = List(
    DeployEnv("staging", None, "us-west-2", secret"STAGING_DEPLOY_ROLE", "staging"),
    DeployEnv("prod", Some("production"), "us-east-1", secret"PROD_DEPLOY_ROLE", "prod"),
  )

final case class Registry(name: String, host: String, roleSecret: EnvValue)

object Registry:
  val all: List[Registry] = List(
    Registry("us", "111.dkr.ecr.us-east-1.amazonaws.com", secret"US_REGISTRY_ROLE"),
    Registry("eu", "222.dkr.ecr.eu-west-1.amazonaws.com", secret"EU_REGISTRY_ROLE"),
  )
