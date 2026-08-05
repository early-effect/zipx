// Build-helper types for the example's deploy capability. Living in project/ (the meta-build) keeps
// build.sbt clean and avoids the top-level-class-in-build.sbt scoping quirks.
//
// This is the typed replacement for an external YAML config + resolver script: deploy targets are a
// plain Scala list, validated by the compiler.
//
// Note `roleSecret: EnvValue` rather than `String`. The `secret"…"` interpolator is `inline`, so it
// checks the name while this file compiles. Holding the validated `EnvValue` here (instead of a bare
// name that build.sbt wraps later) is what keeps that check available: a name assembled from a
// runtime `String` cannot be validated at compile time, and `EnvValue.secretMake` would be the
// honest signature for one.

import zipx.core.EnvValue
import zipx.core.EnvValue.secret

/** One deploy destination. zipx knows nothing about clouds/tiers; this is entirely user-defined. */
final case class DeployEnv(
    name: String,
    ghEnvironment: Option[String],
    region: String,
    roleSecret: EnvValue,
    tier: String,
)

object DeployEnv:
  /** The environments this repo deploys to. Production carries a GitHub Environment for approval. */
  val all: List[DeployEnv] = List(
    DeployEnv("staging", None, "us-west-2", secret"STAGING_DEPLOY_ROLE", "staging"),
    DeployEnv("prod", Some("production"), "us-east-1", secret"PROD_DEPLOY_ROLE", "prod"),
  )

/** One image registry to publish to. Multi-account image push is just a typed list, no external config. */
final case class Registry(name: String, host: String, roleSecret: EnvValue)

object Registry:
  val all: List[Registry] = List(
    Registry("us", "111.dkr.ecr.us-east-1.amazonaws.com", secret"US_REGISTRY_ROLE"),
    Registry("eu", "222.dkr.ecr.eu-west-1.amazonaws.com", secret"EU_REGISTRY_ROLE"),
  )
