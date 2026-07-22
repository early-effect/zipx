// Build-helper types for the example's deploy capability. Living in project/ (the meta-build) keeps
// build.sbt clean and avoids the top-level-class-in-build.sbt scoping quirks.
//
// This is the typed replacement for an external YAML config + resolver script: deploy targets are a
// plain Scala list, validated by the compiler.

/** One deploy destination. zipx knows nothing about clouds/tiers — this is entirely user-defined. */
final case class DeployEnv(
    name: String,
    ghEnvironment: Option[String],
    region: String,
    roleSecret: String,
    tier: String,
)

object DeployEnv:
  /** The environments this repo deploys to. Production carries a GitHub Environment for approval. */
  val all: List[DeployEnv] = List(
    DeployEnv("staging", None, "us-west-2", "STAGING_DEPLOY_ROLE", "staging"),
    DeployEnv("prod", Some("production"), "us-east-1", "PROD_DEPLOY_ROLE", "prod"),
  )

/** One image registry to publish to. Multi-account image push is just a typed list — no external config. */
final case class Registry(name: String, host: String, roleSecret: String)

object Registry:
  val all: List[Registry] = List(
    Registry("us", "111.dkr.ecr.us-east-1.amazonaws.com", "US_REGISTRY_ROLE"),
    Registry("eu", "222.dkr.ecr.eu-west-1.amazonaws.com", "EU_REGISTRY_ROLE"),
  )
