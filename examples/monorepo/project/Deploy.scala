// Lives in project/ (the meta-build) rather than build.sbt, which cannot hold top-level classes cleanly.
//
// `roleSecret` is an `EnvValue`, not a `String`, so the `secret"…"` interpolator checks each name while this file
// compiles. A name assembled at runtime cannot be, and would have to go through `EnvValue.secretMake`.
//
// `name` is a `TargetName` for the same reason: it becomes part of the generated `jobs.<job_id>` key, so it is checked
// here, at the literal, rather than when the workflow is pushed.

import zipx.core.EnvValue
import zipx.core.EnvValue.secret
import zipx.core.TargetName

/** One deploy destination. zipx knows nothing about clouds or tiers; this shape is entirely user-defined. */
final case class DeployEnv(
    name: TargetName,
    ghEnvironment: Option[String],
    region: String,
    roleSecret: EnvValue,
    tier: String,
)

object DeployEnv:
  /** `prod` carries a GitHub Environment, which is what gates it behind an approval. */
  val all: List[DeployEnv] = List(
    DeployEnv(TargetName("staging"), None, "us-west-2", secret"STAGING_DEPLOY_ROLE", "staging"),
    DeployEnv(TargetName("prod"), Some("production"), "us-east-1", secret"PROD_DEPLOY_ROLE", "prod"),
  )

final case class Registry(name: TargetName, host: String, roleSecret: EnvValue)

object Registry:
  val all: List[Registry] = List(
    Registry(TargetName("us"), "111.dkr.ecr.us-east-1.amazonaws.com", secret"US_REGISTRY_ROLE"),
    Registry(TargetName("eu"), "222.dkr.ecr.eu-west-1.amazonaws.com", secret"EU_REGISTRY_ROLE"),
  )
