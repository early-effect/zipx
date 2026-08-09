// Lives in project/ (the meta-build) rather than build.sbt, which cannot hold top-level classes cleanly.
//
// `roleSecret` is an `EnvValue`, not a `String`, so the `secret"…"` interpolator checks each name while this file
// compiles. A name assembled at runtime cannot be, and would have to go through `EnvValue.secretMake`.
//
// `name` is a `TargetName` for the same reason: it becomes part of the generated `jobs.<job_id>` key, so it is checked
// here, at the literal, rather than when the workflow is pushed.
//
// Same for the AWS values: an account id is 12 digits and a region has a shape, both checked at the literal by
// `zipx-aws`.

import zipx.aws.{AwsAccountId, AwsRegion, EcrRegistry}
import zipx.core.EnvValue
import zipx.core.EnvValue.secret
import zipx.core.TargetName

/** One deploy destination. zipx knows nothing about clouds or tiers; this shape is entirely user-defined. */
final case class DeployEnv(
    name: TargetName,
    ghEnvironment: Option[String],
    region: AwsRegion,
    roleSecret: EnvValue,
    tier: String,
)

object DeployEnv:
  /** `prod` carries a GitHub Environment, which is what gates it behind an approval. That approval *is* the gate, so
    * there is no extra `condition` here: ANDing `refs/heads/main` onto a release-tag gate is never true, and zipx now
    * refuses to generate it (see the Job conditions docs page).
    */
  val all: List[DeployEnv] = List(
    DeployEnv(TargetName("staging"), None, AwsRegion("us-west-2"), secret"STAGING_DEPLOY_ROLE", "staging"),
    DeployEnv(TargetName("prod"), Some("production"), AwsRegion("us-east-1"), secret"PROD_DEPLOY_ROLE", "prod"),
  )

/** One image destination: an ECR registry, which is one AWS account in one region, plus the role to assume for it.
  *
  * `EcrRegistry` has no constructor that omits the region and derives its own host, which is why the generated login
  * step cannot be missing `aws-region` the way this example's hand-written one was.
  */
final case class Registry(name: TargetName, registry: EcrRegistry, roleSecret: EnvValue)

object Registry:
  val all: List[Registry] = List(
    Registry(
      TargetName("us"),
      EcrRegistry(AwsAccountId("111122223333"), AwsRegion("us-east-1")),
      secret"US_REGISTRY_ROLE",
    ),
    Registry(
      TargetName("eu"),
      EcrRegistry(AwsAccountId("444455556666"), AwsRegion("eu-west-1")),
      secret"EU_REGISTRY_ROLE",
    ),
  )

  /** The shape `ZipxAws.dockerPublishAll` takes: one destination per registry, sharing a single publish job. */
  val destinations: List[(TargetName, EcrRegistry, EnvValue)] =
    all.map(r => (r.name, r.registry, r.roleSecret))
