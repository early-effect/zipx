package zipx.core

import neotype.unwrap
import zio.test.*
import zipx.workflow.*

object PlannerSpec extends ZIOSpecDefault:
  import Fixtures.*
  import EnvValue.secret

  private val config = PlanConfig(
    workflowName = WorkflowName("CI"),
    cacheEpoch = CacheEpoch.Fixed("1.2.3-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private def deployCap(targets: List[Target]) = Capability(
    name = Capability.DeployName,
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.id == "serviceA",
    command = CommandSource.PerModule(n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy"))),
    matrixed = false,
    targets = _ => targets,
    scope = CapabilityScope.Graph,
  )

  /** The prod target's extra filter is a `vars` check rather than the `refIs("refs/heads/main")` this fixture used to
    * carry: these capabilities are gated `OnReleaseTag`, so a branch-ref requirement on top made the job's `if:` never
    * true, and the planner now refuses to generate that (#66). A `vars` check is the realistic shape anyway, and being
    * outside the decidable subset it still exercises the ANDing.
    */
  private val prodOnly: JobCondition = JobCondition.varNonEmpty("DEPLOY_PROD_ENABLED")

  private val stagingProd = List(
    Target(
      TargetName("staging"),
      env = Map("DEPLOY_ROLE" -> secret"STAGING_ROLE", "TIER" -> EnvValue.plain("staging")),
    ),
    Target(
      TargetName("prod"),
      environment = Some("production"),
      env = Map("DEPLOY_ROLE" -> secret"PROD_ROLE", "TIER" -> EnvValue.plain("prod")),
      condition = Some(prodOnly),
    ),
  )

  def spec = suite("Planner")(
    test("emits workflow-level concurrency so superseded PR pushes are cancelled") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      val c  = wf.concurrency
      assertTrue(
        c.isDefined,
        c.exists(_.group.contains("${{ github.ref }}")),
        c.exists(_.group.startsWith("CI-")),
      )
    },
    test("concurrency never cancels a release-tag run") {
      val c = Planner.plan(sampleGraph, List(Capability.testGraph), config).concurrency
      assertTrue(
        c.exists(_.cancelInProgress == "${{ !startsWith(github.ref, 'refs/tags/') }}"),
        c.exists(_.cancelInProgress != "true"),
      )
    },
    test("concurrency is omitted when cancelSupersededRuns is off") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(cancelSupersededRuns = false))
      assertTrue(wf.concurrency.isEmpty)
    },
    test("Gate.AffectedOnly is rejected, not silently treated as Always") {
      val cap = Capability.publish.copy(gate = Gate.AffectedOnly)
      val err = scala.util.Try(Planner.plan(sampleGraph, List(cap), config)).failed.get
      assertTrue(
        err.getMessage.contains("Gate.AffectedOnly is not implemented"),
        err.getMessage.contains("publish"),
        err.getMessage.contains("zipxAffectedOnPR"),
      )
    },
    suite("an if: that can never be true is rejected")(
      // #66: `examples/monorepo` shipped `deploy-prod` gated on a release tag *and* on `refs/heads/main`. Both halves
      // read as deliberate; only their conjunction is wrong, and it lived in two different files.
      test("a tag gate plus a branch-ref condition, naming the capability and both clauses") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.refIs("refs/heads/main")),
        )
        val err = scala.util.Try(Planner.plan(sampleGraph, List(cap), config)).failed.get.getMessage
        assertTrue(
          err.contains("can never run"),
          err.contains("publish"),
          err.contains("refs/tags/v"),
          err.contains("refs/heads/main"),
        )
      },
      test("a tag gate plus a branch-ref condition on a *target*, which is where the real one was") {
        val cap = deployCap(
          List(Target(TargetName("prod"), condition = Some(JobCondition.refIs("refs/heads/main"))))
        )
        val err = scala.util.Try(Planner.plan(sampleGraph, List(cap), config)).failed.get.getMessage
        assertTrue(err.contains("target 'prod'"), err.contains("refs/heads/main"))
      },
      test("a tag gate plus a non-comparable ref prefix") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.refStartsWith("refs/heads/")),
        )
        assertTrue(scala.util.Try(Planner.plan(sampleGraph, List(cap), config)).isFailure)
      },
      test("two different values of one single-valued context, however deeply nested in the conjunction") {
        val cap = Capability.publish.copy(condition =
          Some(
            JobCondition.and(
              JobCondition.eventIs("push"),
              JobCondition.and(JobCondition.repositoryIs("a/b"), JobCondition.eventIs("pull_request")),
            )
          )
        )
        val err = scala.util.Try(Planner.plan(sampleGraph, List(cap), config)).failed.get.getMessage
        assertTrue(err.contains("event_name"), err.contains("one value per run"))
      },
      test("a condition that both requires and negates the same claim") {
        val cap = Capability.publish.copy(condition =
          Some(JobCondition.and(JobCondition.eventIs("push"), JobCondition.not(JobCondition.eventIs("push"))))
        )
        assertTrue(scala.util.Try(Planner.plan(sampleGraph, List(cap), config)).isFailure)
      },
      test("a tag gate plus !refStartsWith of a shorter prefix, which excludes every ref the gate allows") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.not(JobCondition.refStartsWith("refs/tags/"))),
        )
        assertTrue(scala.util.Try(Planner.plan(sampleGraph, List(cap), config)).isFailure)
      },
    ),
    suite("but only the decidable subset is rejected")(
      // An unsound rejection is worse than a missed one: a missed contradiction is the status quo, a wrong rejection is
      // a build that cannot generate its CI and no way to argue. Each of these must keep planning.
      test("a disjunction, where one branch satisfies the gate") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.or(JobCondition.refIs("refs/heads/main"), JobCondition.onReleaseTag)),
        )
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("a Raw condition, whose meaning zipx does not know") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.raw("github.ref == 'refs/heads/main'")),
        )
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("compatible ref prefixes, where one extends the other") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.refStartsWith("refs/tags/")),
        )
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("a ref that does satisfy the tag gate") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.refIs("refs/tags/v1.2.3")),
        )
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("two exclusions, which always leave a third value") {
        val cap = Capability.publish.copy(condition =
          Some(
            JobCondition.and(
              JobCondition.not(JobCondition.eventIs("push")),
              JobCondition.not(JobCondition.eventIs("pull_request")),
            )
          )
        )
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("claims about different contexts, which are not comparable at all") {
        val cap = Capability.publish.copy(condition =
          Some(JobCondition.and(JobCondition.eventIs("push"), JobCondition.repositoryIs("early-effect/zipx")))
        )
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("an exclusion of one exact ref under a required prefix, which leaves every other ref") {
        val cap = Capability.publish.copy(
          gate = Gate.OnReleaseTag,
          condition = Some(JobCondition.not(JobCondition.refIs("refs/tags/v0.0.1"))),
        )
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("a target condition on a capability whose gate allows any ref") {
        val cap = deployCap(List(Target(TargetName("prod"), condition = Some(JobCondition.refIs("refs/heads/main")))))
          .copy(gate = Gate.Always)
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.nonEmpty)
      },
      test("a target whose module does not participate, so the job never exists") {
        val cap = deployCap(
          List(Target(TargetName("prod"), condition = Some(JobCondition.refIs("refs/heads/main"))))
        ).copy(participates = _ => false)
        assertTrue(Planner.plan(sampleGraph, List(cap), config).jobs.forall(!_._1.startsWith("deploy")))
      },
    ),
    test("the supported gates still plan cleanly") {
      assertTrue(
        Planner.plan(sampleGraph, List(Capability.publish.copy(gate = Gate.Always)), config).jobs.nonEmpty,
        Planner.plan(sampleGraph, List(Capability.publish.copy(gate = Gate.OnReleaseTag)), config).jobs.nonEmpty,
      )
    },
    test("emits one test job per CI-relevant module") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      assertTrue(
        wf.jobs.contains("test-schema"),
        wf.jobs.contains("test-clientA"),
        wf.jobs.contains("test-core"),
        wf.jobs.size == sampleGraph.ids.size,
      )
    },
    test("test-job `needs` are the direct upstream modules' test jobs") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      assertTrue(
        wf.jobs("test-schema").needs == Nil,
        wf.jobs("test-api").needs == List("test-schema"),
        wf.jobs("test-clientA").needs == List("test-api"),
        wf.jobs("test-serviceA").needs == List("test-api", "test-core"),
      )
    },
    test("per-module Scala matrix reflects each module's crossScalaVersions") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      assertTrue(
        wf.jobs("test-schema").strategy.exists(_.matrix("scala") == cross),
        wf.jobs("test-legacyClient").strategy.isEmpty,
        wf.jobs("test-core").strategy.isEmpty,
      )
    },
    test("publish jobs are dependency-ordered (the L0/L1/L2 headline case)") {
      val wf = Planner.plan(sampleGraph, List(Capability.publishGraph), config)
      assertTrue(
        wf.jobs("publish-schema").needs == Nil,
        wf.jobs("publish-api").needs == List("publish-schema"),
        wf.jobs("publish-legacyClient").needs == List("publish-schema"),
        wf.jobs("publish-clientA").needs == List("publish-api"),
        wf.jobs("publish-clientB").needs == List("publish-api"),
        !wf.jobs.contains("publish-core"),
        !wf.jobs.contains("publish-serviceA"),
      )
    },
    test("publish command crosses when the module is cross-built, single otherwise") {
      val wf    = Planner.plan(sampleGraph, List(Capability.publishGraph), config)
      val runOf = (id: String) => wf.jobs(id).steps.last.run.getOrElse("")
      assertTrue(
        runOf("publish-api").contains("+api/publish"),
        runOf("publish-legacyClient").contains("legacyClient/publish"),
        !runOf("publish-legacyClient").contains("+legacyClient"),
      )
    },
    test("publish jobs are never matrixed: the `+publish` leg crosses internally") {
      val wf = Planner.plan(sampleGraph, List(Capability.publishGraph), config)
      assertTrue(
        wf.jobs("publish-api").strategy.isEmpty,
        !wf.jobs("publish-api").steps.last.run.getOrElse("").contains("matrix.scala"),
      )
    },
    test("LocalDir cache primary key includes run_id + job id so same-run jobs accumulate and can save") {
      val wf       = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      val coreStep = wf.jobs("test-core").steps.find(_.uses.exists(_.unwrap.startsWith("actions/cache@")))
      val apiStep  = wf.jobs("test-api").steps.find(_.uses.exists(_.unwrap.startsWith("actions/cache@")))
      val coreKey  = coreStep.map(_.`with`("key")).getOrElse("")
      val apiKey   = apiStep.map(_.`with`("key")).getOrElse("")
      assertTrue(
        coreKey.contains("ubuntu-latest-jdk21-sbt-1.2.3-ci-${{ github.run_id }}-test-core"),
        apiKey.contains("ubuntu-latest-jdk21-sbt-1.2.3-ci-${{ github.run_id }}-test-api"),
        coreKey != apiKey,
      )
    },
    test("LocalDir cache paths cover sbt tooling and target directories") {
      val wf    = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      val step  = wf.jobs("test-core").steps.find(_.uses.exists(_.unwrap.startsWith("actions/cache@")))
      val paths = step.map(_.`with`("path")).getOrElse("")
      assertTrue(
        paths.contains("~/.sbt"),
        paths.contains("~/.cache/sbt"),
        paths.contains("~/.cache/coursier"),
        paths.contains("target"),
      )
    },
    test("LocalDir cache disables setup-java and sbt/setup-sbt internal caching") {
      val wf   = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      val java = wf.jobs("test-core").steps.find(_.uses.exists(_.unwrap.startsWith("actions/setup-java@")))
      val sbt  = wf.jobs("test-core").steps.find(_.uses.exists(_.unwrap.startsWith("sbt/setup-sbt@")))
      assertTrue(
        !java.exists(_.`with`.contains("cache")),
        sbt.exists(_.`with`.get("disk-cache").contains("false")),
      )
    },
    test("LocalDir restore-keys bridge -ci / -SNAPSHOT epochs to the prior release epoch") {
      val prefix                 = "ubuntu-latest-jdk21-sbt-"
      def restore(epoch: String) =
        Planner
          .plan(
            sampleGraph,
            List(Capability.test),
            config.copy(cacheEpoch = CacheEpoch.Fixed(epoch), skipMergedPrPush = false),
          )
          .jobs("test")
          .steps
          .find(_.uses.exists(_.unwrap.startsWith("actions/cache@")))
          .map(_.`with`("restore-keys"))
          .getOrElse("")
      val ci      = restore("1.2.3-ci")
      val snap    = restore("1.2.3-SNAPSHOT")
      val release = restore("1.2.3")
      assertTrue(
        Planner.priorReleaseEpochKey(prefix, "1.2.3-ci").contains(s"${prefix}1.2.3-"),
        Planner.priorReleaseEpochKey(prefix, "1.2.3-SNAPSHOT").contains(s"${prefix}1.2.3-"),
        Planner.priorReleaseEpochKey(prefix, "1.2.3").isEmpty,
        ci.split('\n').toList == List(
          s"${prefix}1.2.3-ci-$${{ github.run_id }}-",
          s"${prefix}1.2.3-ci-",
          s"${prefix}1.2.3-",
          prefix,
        ),
        snap.split('\n').toList == List(
          s"${prefix}1.2.3-SNAPSHOT-$${{ github.run_id }}-",
          s"${prefix}1.2.3-SNAPSHOT-",
          s"${prefix}1.2.3-",
          prefix,
        ),
        release.split('\n').toList == List(
          s"${prefix}1.2.3-$${{ github.run_id }}-",
          s"${prefix}1.2.3-",
          prefix,
        ),
      )
    },
    test("cache key is identical across commits with the same epoch+job template, differs across epochs") {
      def keyFor(epoch: String) =
        Planner
          .plan(sampleGraph, List(Capability.testGraph), config.copy(cacheEpoch = CacheEpoch.Fixed(epoch)))
          .jobs("test-core")
          .steps
          .find(_.uses.exists(_.unwrap.startsWith("actions/cache@")))
          .map(_.`with`("key"))
      assertTrue(
        keyFor("1.2.3-ci") == keyFor("1.2.3-ci"),
        keyFor("1.2.3-ci") != keyFor("1.3.0"),
      )
    },
    test("GitTags epoch configures checkout with full history and tags") {
      val wf       = Planner.plan(sampleGraph, List(Capability.test), config.copy(cacheEpoch = CacheEpoch.GitTags()))
      val checkout = wf.jobs("test").steps.find(_.uses.exists(_.unwrap.contains("checkout")))
      assertTrue(
        checkout.exists(_.`with`.get("fetch-tags").contains("true")),
        checkout.exists(_.`with`.get("fetch-depth").contains("0")),
      )
    },
    test("GitTags resolve step emits epoch via git describe and GITHUB_OUTPUT") {
      val wf      = Planner.plan(sampleGraph, List(Capability.test), config.copy(cacheEpoch = CacheEpoch.GitTags()))
      val steps   = wf.jobs("test").steps
      val resolve = steps.find(_.id.contains(CacheEpoch.GitTagsStepId))
      val run     = resolve.flatMap(_.run).getOrElse("")
      assertTrue(
        resolve.exists(_.name.contains("Resolve cache epoch")),
        run.contains("git describe --tags --abbrev=0 --match"),
        run.contains("GITHUB_OUTPUT"),
        run.contains("::warning title=zipx cache epoch::"),
        run.contains("fewer than origin"),
      )
    },
    test("GitTags wires resolve step outputs into cache key and restore-keys") {
      val wf      = Planner.plan(sampleGraph, List(Capability.test), config.copy(cacheEpoch = CacheEpoch.GitTags()))
      val steps   = wf.jobs("test").steps
      val cache   = steps.find(_.uses.exists(_.unwrap.startsWith("actions/cache@")))
      val key     = cache.map(_.`with`("key")).getOrElse("")
      val restore = cache.map(_.`with`("restore-keys")).getOrElse("")
      assertTrue(
        key.contains("ubuntu-latest-jdk21-sbt-${{ steps.cache-epoch.outputs.epoch }}-${{ github.run_id }}-test"),
        restore.split('\n').toList == List(
          "ubuntu-latest-jdk21-sbt-${{ steps.cache-epoch.outputs.epoch }}-${{ github.run_id }}-",
          "ubuntu-latest-jdk21-sbt-${{ steps.cache-epoch.outputs.epoch }}-",
          "ubuntu-latest-jdk21-sbt-${{ steps.cache-epoch.outputs.release }}-",
          "ubuntu-latest-jdk21-sbt-",
        ),
      )
    },
    test("GitTags resolve step precedes the cache action") {
      val steps = Planner
        .plan(
          sampleGraph,
          List(Capability.test),
          config.copy(cacheEpoch = CacheEpoch.GitTags()),
        )
        .jobs("test")
        .steps
      assertTrue(
        steps.indexWhere(_.id.contains(CacheEpoch.GitTagsStepId)) <
          steps.indexWhere(_.uses.exists(_.unwrap.startsWith("actions/cache@")))
      )
    },
    test("Script epoch strategy uses the caller step id and run body") {
      val custom = CacheEpoch.Script(
        run = """echo "epoch=9.9.9-ci" >> "$GITHUB_OUTPUT"
                |echo "release=9.9.9" >> "$GITHUB_OUTPUT"
                |""".stripMargin,
        stepId = StepId("my-epoch"),
      )
      val wf      = Planner.plan(sampleGraph, List(Capability.test), config.copy(cacheEpoch = custom))
      val steps   = wf.jobs("test").steps
      val resolve = steps.find(_.id.contains("my-epoch"))
      val key     = steps.find(_.uses.exists(_.unwrap.startsWith("actions/cache@"))).map(_.`with`("key")).getOrElse("")
      assertTrue(
        resolve.flatMap(_.run).exists(_.contains("epoch=9.9.9-ci")),
        key.contains("${{ steps.my-epoch.outputs.epoch }}"),
        key.contains("-test"),
      )
    },
    test("release triggers include the tag pattern; PR/test-only builds do not gate on tags") {
      val withPublish = Planner.plan(sampleGraph, List(Capability.testGraph, Capability.publishGraph), config)
      val testOnly    = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      assertTrue(
        withPublish.on.push.exists(_.tags.contains(config.releaseTagPattern)),
        testOnly.on.push.exists(_.tags.isEmpty),
      )
    },
    test("publish jobs are gated on a release tag") {
      val wf = Planner.plan(sampleGraph, List(Capability.publishGraph), config)
      assertTrue(
        wf.jobs("publish-schema").`if`.exists(_.contains("startsWith(github.ref, 'refs/tags/v')"))
      )
    },
    test("affected mode adds a leading `affected` setup job with a modules output") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      assertTrue(
        wf.jobs.contains("affected"),
        wf.jobs.keys.head == "affected",
        wf.jobs("affected").outputs.contains("modules"),
        wf.jobs("affected").steps.exists(_.`with`.get("fetch-depth").contains("0")),
        wf.jobs("affected").steps.exists(_.`with`.get("fetch-tags").contains("true")),
      )
    },
    test("verify jobs gate on affected-set membership (with the `all` sentinel escape hatch)") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      val cond = wf.jobs("test-api").`if`.getOrElse("")
      assertTrue(
        cond.contains("contains(fromJson(needs.affected.outputs.modules), 'api')"),
        cond.contains("'all'"),
        wf.jobs("test-api").needs.contains("affected"),
      )
    },
    test("skipped-needs hazard: affected verify jobs use !cancelled() and tolerate skipped upstreams") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      val cond = wf.jobs("test-api").`if`.getOrElse("")
      assertTrue(
        cond.contains("!cancelled()"),
        cond.contains("needs.test-schema.result != 'failure'"),
        cond.contains("!startsWith(github.ref, 'refs/tags/')"),
      )
    },
    test("by default the affected job builds all on push (no before-sha diff)") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      val script = wf.jobs("affected").steps.find(_.id.contains("compute")).flatMap(_.run).getOrElse("")
      assertTrue(
        script.contains("pull_request"),
        !script.contains("github.event.before"),
        script.contains("""modules='["all"]'"""),
      )
    },
    test("affected script never captures sbt stdout into modules (GITHUB_OUTPUT-safe)") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      val script = wf.jobs("affected").steps.find(_.id.contains("compute")).flatMap(_.run).getOrElse("")
      assertTrue(
        script.contains("sbt -batch --error \"zipxAffectedModules $BASE\""),
        script.contains("modules=$(cat target/zipx-affected.json)"),
        !script.contains("modules=$(sbt"),
        wf.jobs("affected").steps.exists(_.uses.exists(_.unwrap.startsWith("actions/setup-java@"))),
      )
    },
    test("affectedOnPush adds a guarded before-sha diff for pushes") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.testGraph),
        config.copy(affected = AffectedMode.AffectedOnPR, affectedOnPush = true),
      )
      val script = wf.jobs("affected").steps.find(_.id.contains("compute")).flatMap(_.run).getOrElse("")
      assertTrue(
        script.contains("github.event.before"),
        script.contains("zipxAffectedModules $BASE"),
        script.contains("modules=$(cat target/zipx-affected.json)"),
        !script.contains("modules=$(sbt"),
        script.contains("0000000000000000000000000000000000000000"),
      )
    },
    test("Always mode emits no affected job and no affected gating") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.Always))
      assertTrue(
        !wf.jobs.contains("affected"),
        wf.jobs("test-api").`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
        wf.jobs("test-api").`if`.exists(_.contains("github.event_name != 'workflow_dispatch'")),
        !wf.jobs("test-api").`if`.exists(_.contains("needs.affected")),
        !wf.jobs("test-api").needs.contains("affected"),
      )
    },
    test("LocalDir backend caches via epoch-keyed actions/cache and adds no services/env") {
      val wf  = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      val job = wf.jobs("test-core")
      assertTrue(
        job.services.isEmpty,
        job.env.isEmpty,
        job.steps.exists(_.uses.exists(_.unwrap.startsWith("actions/cache@"))),
        job.steps.exists(s =>
          s.uses.exists(_.unwrap.startsWith("sbt/setup-sbt@")) && s.`with`.get("disk-cache").contains("false")
        ),
      )
    },
    test("BazelRemoteSidecar backend emits a service sidecar and the remote-cache env, no actions/cache") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.testGraph),
        config.copy(cache = RemoteCacheProof.sidecar),
      )
      val job = wf.jobs("test-core")
      assertTrue(
        job.services.contains(RemoteCacheProof.serviceName),
        job.services(RemoteCacheProof.serviceName).image == RemoteCacheProof.image,
        job.services(RemoteCacheProof.serviceName).ports == List(RemoteCacheProof.portMapping),
        job.env.get(RemoteCacheProof.envUri).contains(RemoteCacheProof.grpcLocalhost),
        !job.steps.exists(_.uses.exists(_.unwrap.startsWith("actions/cache@"))),
        !job.steps.exists(s => s.uses.exists(_.unwrap.startsWith("sbt/setup-sbt@")) && s.`with`.contains("disk-cache")),
      )
    },
    test("ManagedRemote backend sets the endpoint + header-from-secret env, no service") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.testGraph),
        config.copy(cache = CacheBackend.managedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")),
      )
      val job = wf.jobs("test-core")
      assertTrue(
        job.services.isEmpty,
        job.env.get(RemoteCacheProof.envUri).contains("grpcs://cache.buildbuddy.io"),
        job.env.get(RemoteCacheProof.envHeader).exists(_.contains("secrets.BUILDBUDDY_KEY")),
      )
    },
    test("docker capability emits release-gated Docker/publish jobs only for docker-enabled modules") {
      val withDocker = sampleGraph.mapNodes {
        case n if n.id == "serviceA" || n.id == "serviceB" => n.copy(docker = true)
        case n                                             => n
      }
      val wf = Planner.plan(withDocker, List(Capability.dockerGraph), config)
      assertTrue(
        wf.jobs.contains("docker-serviceA"),
        wf.jobs.contains("docker-serviceB"),
        !wf.jobs.contains("docker-schema"),
        wf.jobs("docker-serviceA").steps.last.run.exists(_.contains("serviceA/Docker/publish")),
        wf.jobs("docker-serviceA").`if`.exists(_.contains("refs/tags/v")),
        wf.jobs("docker-serviceA").strategy.isEmpty,
      )
    },
    test("a capability with no targets still emits a single job (unchanged path)") {
      val wf = Planner.plan(sampleGraph, List(deployCap(Nil)), config)
      assertTrue(
        wf.jobs.contains("deploy-serviceA"),
        wf.jobs("deploy-serviceA").environment.isEmpty,
      )
    },
    test("targets fan out to one explicit job per target, sorted by name") {
      val wf      = Planner.plan(sampleGraph, List(deployCap(stagingProd)), config)
      val deploys = wf.jobs.keys.filter(_.startsWith("deploy-")).toList
      assertTrue(
        deploys == List("deploy-serviceA-prod", "deploy-serviceA-staging"),
        !wf.jobs.contains("deploy-serviceA"),
      )
    },
    test("environment binds only on targets that declare one (the approval gate)") {
      val wf = Planner.plan(sampleGraph, List(deployCap(stagingProd)), config)
      assertTrue(
        wf.jobs("deploy-serviceA-prod").environment.contains("production"),
        wf.jobs("deploy-serviceA-staging").environment.isEmpty,
      )
    },
    test("target env (including secret expressions) is injected into the job env block") {
      val wf   = Planner.plan(sampleGraph, List(deployCap(stagingProd)), config)
      val prod = wf.jobs("deploy-serviceA-prod")
      assertTrue(
        prod.env.get("DEPLOY_ROLE").contains("${{ secrets.PROD_ROLE }}"),
        prod.env.get("TIER").contains("prod"),
        wf.jobs("deploy-serviceA-staging").env.get("TIER").contains("staging"),
      )
    },
    test("target condition is ANDed into the job's release gate") {
      val wf   = Planner.plan(sampleGraph, List(deployCap(stagingProd)), config)
      val cond = wf.jobs("deploy-serviceA-prod").`if`.getOrElse("")
      assertTrue(
        cond.contains("startsWith(github.ref, 'refs/tags/v')"),
        cond.contains(prodOnly.render),
        cond.contains("&&"),
      )
    },
    test("all per-target jobs share the module's command and needs") {
      val wf = Planner.plan(sampleGraph, List(deployCap(stagingProd)), config)
      assertTrue(
        wf.jobs("deploy-serviceA-prod").steps.last.run.exists(_.contains("serviceA/deploy")),
        wf.jobs("deploy-serviceA-staging").steps.last.run.exists(_.contains("serviceA/deploy")),
      )
    },
    test("deploy jobs need the module's docker job (cross-capability needs)") {
      val graph = sampleGraph.mapNodes {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      }
      val deploy = Capability.deployGraph(
        participates = _.id == "serviceA",
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
        targets = _ => stagingProd,
      )
      val wf = Planner.plan(graph, List(Capability.dockerGraph, deploy), config)
      assertTrue(
        wf.jobs("deploy-serviceA-prod").needs.contains("docker-serviceA"),
        wf.jobs("deploy-serviceA-staging").needs.contains("docker-serviceA"),
      )
    },
    test("deploy jobs sort after docker jobs in the workflow (phase order)") {
      val graph = sampleGraph.mapNodes {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      }
      val deploy =
        Capability.deployGraph(
          _.id == "serviceA",
          n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
          _ => stagingProd,
        )
      val wf   = Planner.plan(graph, List(deploy, Capability.dockerGraph), config)
      val keys = wf.jobs.keys.toList
      assertTrue(keys.indexOf("docker-serviceA") < keys.indexOf("deploy-serviceA-prod"))
    },
    test("Capability.permissions renders on the job (OIDC id-token)") {
      val deploy = Capability.deployGraph(
        participates = _.id == "serviceA",
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
        targets = _ => stagingProd,
        permissions = Map("id-token" -> "write", "contents" -> "read"),
      )
      val job = Planner.plan(sampleGraph, List(deploy), config).jobs("deploy-serviceA-prod")
      assertTrue(
        job.permissions.get("id-token").contains("write"),
        job.permissions.get("contents").contains("read"),
      )
    },
    test("a needsCapabilities cycle is rejected") {
      val a = Capability(
        name = CapabilityName("a"),
        phase = Phase.Publish,
        ordering = Ordering.DependencyOrdered,
        gate = Gate.Always,
        participates = _ => true,
        command = CommandSource.Fixed(SbtCommand.unsafeTask("a")),
        matrixed = false,
        needsCapabilities = List(CapabilityName("b")),
      )
      val b = Capability(
        name = CapabilityName("b"),
        phase = Phase.Publish,
        ordering = Ordering.DependencyOrdered,
        gate = Gate.Always,
        participates = _ => true,
        command = CommandSource.Fixed(SbtCommand.unsafeTask("b")),
        matrixed = false,
        needsCapabilities = List(CapabilityName("a")),
      )
      assertTrue(scala.util.Try(Planner.plan(sampleGraph, List(a, b), config)).isFailure)
    },
    test("extraSteps are injected before the command and can reference the target") {
      val cap = Capability
        .deployGraph(
          participates = _.id == "serviceA",
          command = n => SbtCommand.module(n, SbtCommand.unsafeTask("deploy")),
          targets = _ => stagingProd,
        )
        .copy(
          extraSteps = _ =>
            List(
              Step(
                name = Some("Configure credentials"),
                uses = Some(ActionRef("aws-actions/configure-aws-credentials@v6")),
                `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}"),
              )
            )
        )
      val steps   = Planner.plan(sampleGraph, List(cap), config).jobs("deploy-serviceA-prod").steps
      val credIdx = steps.indexWhere(_.uses.contains(ActionRef("aws-actions/configure-aws-credentials@v6")))
      val cmdIdx  = steps.indexWhere(_.run.exists(_.contains("serviceA/deploy")))
      assertTrue(credIdx >= 0, cmdIdx >= 0, credIdx < cmdIdx)
    },
    test("postSteps are injected after the command") {
      val cap = Capability.custom(
        name = Capability.PublishName,
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("publish")),
        participates = _.id == "schema",
        postSteps = _ => List(Step(name = Some("Upload"), run = Some("echo uploaded"))),
      )
      val steps   = Planner.plan(sampleGraph, List(cap), config).jobs("publish-schema").steps
      val cmdIdx  = steps.indexWhere(_.run.exists(_.contains("schema/publish")))
      val postIdx = steps.indexWhere(_.name.contains("Upload"))
      assertTrue(cmdIdx >= 0, postIdx > cmdIdx)
    },
    test("Capability.runsOn overrides the build-level runner (list form)") {
      val cap = Capability.custom(
        name = CapabilityName("release"),
        command = _ => SbtCommand.unsafeTask("release"),
        participates = _.id == "serviceA",
        runsOn = Some(List("self-hosted", "linux")),
      )
      assertTrue(
        Planner.plan(sampleGraph, List(cap), config).jobs("release-serviceA").runsOn == List("self-hosted", "linux")
      )
    },
    test("Capability.custom emits a job with its command and defaults") {
      val cap =
        Capability.custom(
          name = CapabilityName("notify"),
          command = _ => SbtCommand.unsafeTask("notify"),
          participates = _.id == "schema",
        )
      val wf = Planner.plan(sampleGraph, List(cap), config)
      assertTrue(
        wf.jobs.contains("notify-schema"),
        wf.jobs("notify-schema").steps.last.run.exists(_.contains("notify")),
      )
    },
    test("a docker capability fans out over registry targets with per-registry credential steps") {
      val graph = sampleGraph.mapNodes {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      }
      val registries = List(
        Target(
          TargetName("us"),
          env = Map("REGISTRY" -> EnvValue.plain("111.dkr.ecr.us-east-1"), "ROLE" -> secret"US_ROLE"),
        ),
        Target(
          TargetName("eu"),
          env = Map("REGISTRY" -> EnvValue.plain("222.dkr.ecr.eu-west-1"), "ROLE" -> secret"EU_ROLE"),
        ),
      )
      val multiDocker = Capability
        .custom(
          name = Capability.DockerName,
          command = n => SbtCommand.module(n, SbtCommand.unsafeTask("Docker/publish")),
          participates = _.docker,
          phase = Phase.Publish,
          targets = _ => registries,
          permissions = Map("id-token" -> "write"),
        )
        .copy(
          extraSteps = _ =>
            List(
              Step(
                name = Some("Login"),
                uses = Some(ActionRef("aws-actions/amazon-ecr-login@v2")),
                env = Map("R" -> "${{ env.REGISTRY }}"),
              )
            )
        )
      val wf = Planner.plan(graph, List(multiDocker), config)
      assertTrue(
        wf.jobs.contains("docker-serviceA-us"),
        wf.jobs.contains("docker-serviceA-eu"),
        wf.jobs("docker-serviceA-us").env.get("REGISTRY").contains("111.dkr.ecr.us-east-1"),
        wf.jobs("docker-serviceA-eu").env.get("ROLE").contains("${{ secrets.EU_ROLE }}"),
        wf.jobs("docker-serviceA-us").steps.exists(_.uses.contains(ActionRef("aws-actions/amazon-ecr-login@v2"))),
      )
    },
    test("a Once capability emits a single build-wide job (no module suffix)") {
      val fmt = Capability.once(CapabilityName("fmt"), SbtCommand.unsafeTask("scalafmtCheckAll"))
      val wf  = Planner.plan(sampleGraph, List(fmt), config)
      assertTrue(
        wf.jobs.contains("fmt"),
        wf.jobs.keys.count(_.startsWith("fmt")) == 1,
        wf.jobs("fmt").steps.last.run.exists(_.contains("scalafmtCheckAll")),
      )
    },
    test("per-module capabilities can depend on a Once gate by name") {
      val fmt  = Capability.once(CapabilityName("fmt"), SbtCommand.unsafeTask("scalafmtCheckAll"))
      val test = Capability.testGraph.copy(needsCapabilities = List(fmt.name))
      val wf   = Planner.plan(sampleGraph, List(fmt, test), config)
      assertTrue(
        wf.jobs("test-schema").needs.contains("fmt"),
        wf.jobs("test-api").needs.contains("fmt"),
      )
    },
    test("capability.env injects into every job of the capability (no targets)") {
      val pub = Capability.publishGraph.copy(env =
        Map(
          "PGP_PASSPHRASE"    -> secret"PGP_PASSPHRASE",
          "SONATYPE_USERNAME" -> Secret.ref("SONATYPE_USERNAME"),
        )
      )
      val job = Planner.plan(sampleGraph, List(pub), config).jobs("publish-schema")
      assertTrue(
        job.env.get("PGP_PASSPHRASE").contains("${{ secrets.PGP_PASSPHRASE }}"),
        job.env.get("SONATYPE_USERNAME").contains("${{ secrets.SONATYPE_USERNAME }}"),
      )
    },
    test("target.env wins over capability.env on key clash; cache keys survive unless overridden") {
      val wf = Planner.plan(
        sampleGraph,
        List(
          Capability.custom(
            name = CapabilityName("ship"),
            command = _ => SbtCommand.unsafeTask("ship"),
            participates = _.id == "serviceA",
            gate = Gate.Always,
            env = Map("TIER" -> EnvValue.plain("capability-default"), "SHARED" -> EnvValue.plain("from-cap")),
            targets = _ =>
              List(
                Target(
                  TargetName("prod"),
                  env = Map("TIER" -> EnvValue.plain("prod"), "EXTRA" -> EnvValue.plain("only-target")),
                )
              ),
          )
        ),
        config.copy(cache = CacheBackend.managedRemote("grpcs://cache.example", "CACHE_KEY")),
      )
      val job = wf.jobs("ship-serviceA-prod")
      assertTrue(
        job.env.get("TIER").contains("prod"),
        job.env.get("SHARED").contains("from-cap"),
        job.env.get("EXTRA").contains("only-target"),
        job.env.get(RemoteCacheProof.envUri).contains("grpcs://cache.example"),
        job.env.get(RemoteCacheProof.envHeader).contains("${{ secrets.CACHE_KEY }}"),
      )
    },
    test("Once jobs receive capability.env") {
      val fmt =
        Capability.once(
          CapabilityName("fmt"),
          SbtCommand.unsafeTask("scalafmtCheckAll"),
          env = Map("SCALAFMT_VERSION" -> EnvValue.plain("3.8")),
        )
      assertTrue(
        Planner.plan(sampleGraph, List(fmt), config).jobs("fmt").env.get("SCALAFMT_VERSION").contains("3.8")
      )
    },
    test("FromEnv renders as ${{ env.NAME }}") {
      val cap = Capability.custom(
        name = CapabilityName("relay"),
        command = _ => SbtCommand.unsafeTask("relay"),
        participates = _.id == "schema",
        gate = Gate.Always,
        env = Map("UPSTREAM" -> EnvValue.env("DEPLOY_ROLE")),
      )
      assertTrue(
        Planner
          .plan(sampleGraph, List(cap), config)
          .jobs("relay-schema")
          .env
          .get("UPSTREAM")
          .contains("${{ env.DEPLOY_ROLE }}")
      )
    },
    test("Expr is an escape hatch rendered verbatim") {
      val cap = Capability.custom(
        name = CapabilityName("expr"),
        command = _ => SbtCommand.unsafeTask("x"),
        participates = _.id == "schema",
        gate = Gate.Always,
        env = Map("COMPLEX" -> EnvValue.expr("${{ github.sha }}-${{ github.run_id }}")),
      )
      assertTrue(
        Planner
          .plan(sampleGraph, List(cap), config)
          .jobs("expr-schema")
          .env
          .get("COMPLEX")
          .contains("${{ github.sha }}-${{ github.run_id }}")
      )
    },
    test("an invalid headerSecret name never reaches plan time") {
      for bad <- typeCheck("""zipx.core.CacheBackend.managedRemote("grpcs://x", "bad name")""")
      yield assertTrue(
        bad.isLeft,
        CacheBackend.managedRemoteMake("grpcs://x", "bad name").isLeft,
        CacheBackend.managedRemoteMake("grpcs://x", "CACHE_KEY").isRight,
      )
    },
    test("publish contracts edges through a non-publishing intermediate") {
      val g = GraphFixture(
        List(
          ModuleNode(ModuleId("pubRoot"), publishes = true, crossScalaVersions = List(scala3)),
          ModuleNode(
            ModuleId("middle"),
            dependsOn = List("pubRoot"),
            publishes = false,
            crossScalaVersions = List(scala3),
          ),
          ModuleNode(
            ModuleId("pubLeaf"),
            dependsOn = List("middle"),
            publishes = true,
            crossScalaVersions = List(scala3),
          ),
        )
      )
      val wf = Planner.plan(g, List(Capability.publishGraph), config)
      assertTrue(
        wf.jobs("publish-pubLeaf").needs == List("publish-pubRoot"),
        !wf.jobs.contains("publish-middle"),
      )
    },
    test("cross-capability needs fans out over all per-target jobs of the dependency") {
      val graph = sampleGraph.mapNodes {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      }
      val multiDocker = Capability.custom(
        name = Capability.DockerName,
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("Docker/publish")),
        participates = _.docker,
        targets = _ => List(Target(TargetName("us")), Target(TargetName("eu"))),
      )
      val deploy = Capability.deployGraph(
        participates = _.id == "serviceA",
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => List(Target(TargetName("staging"))),
      )
      val needs = Planner.plan(graph, List(multiDocker, deploy), config).jobs("deploy-serviceA-staging").needs
      assertTrue(
        needs.contains("docker-serviceA-eu"),
        needs.contains("docker-serviceA-us"),
      )
    },
    test("unknown needsCapabilities names are ignored (do not crash)") {
      val cap = Capability.testGraph.copy(needsCapabilities = List(CapabilityName("does-not-exist")))
      val wf  = Planner.plan(sampleGraph, List(cap), config)
      assertTrue(!wf.jobs("test-schema").needs.contains("does-not-exist"))
    },
    test("ciRelevant=false modules are excluded from the test fan-out") {
      val g = sampleGraph.mapNodes {
        case n if n.id == "core" => n.copy(ciRelevant = false)
        case n                   => n
      }
      val wf = Planner.plan(g, List(Capability.testGraph), config)
      assertTrue(!wf.jobs.contains("test-core"), wf.jobs.contains("test-schema"))
    },
    test("StepContext.matrixed is true only when a Scala matrix is active") {
      var seen: List[Boolean] = Nil
      val cap                 = Capability.testGraph.copy(
        participates = _.id == "api",
        extraSteps = ctx =>
          seen = ctx.matrixed :: seen; Nil,
      )
      val noMatrix = Capability.testGraph.copy(
        participates = _.id == "core",
        extraSteps = ctx =>
          seen = ctx.matrixed :: seen; Nil,
      )
      val _ = Planner.plan(sampleGraph, List(cap, noMatrix), config)
      assertTrue(seen.contains(true), seen.contains(false))
    },
    test("andConditions with Always gate still applies a bare target condition") {
      val cap = Capability.custom(
        name = CapabilityName("gate"),
        command = _ => SbtCommand.unsafeTask("x"),
        participates = _.id == "schema",
        gate = Gate.Always,
        targets = _ => List(Target(TargetName("only"), condition = Some(JobCondition.refIs("refs/heads/main")))),
      )
      assertTrue(
        Planner
          .plan(sampleGraph, List(cap), config)
          .jobs("gate-schema-only")
          .`if`
          .contains("github.ref == 'refs/heads/main'")
      )
    },
    test("Once capability with OnReleaseTag is gated") {
      val once =
        Capability.once(CapabilityName("releaseNotes"), SbtCommand.unsafeTask("notes"), gate = Gate.OnReleaseTag)
      assertTrue(
        Planner
          .plan(sampleGraph, List(once), config)
          .jobs("releaseNotes")
          .`if`
          .exists(_.contains("refs/tags/v"))
      )
    },
    test("root modules with empty baseDir never own changed files via the planner path") {
      val g =
        GraphFixture(List(ModuleNode(ModuleId("root"), baseDir = ""), ModuleNode(ModuleId("lib"), baseDir = "lib")))
      assertTrue(
        Affected.owningModules(g, "README.md").isEmpty,
        Affected.owningModules(g, "lib/X.scala") == Set("lib"),
      )
    },
    test("Aggregate test emits one root Once job (sbt test)") {
      val wf  = Planner.plan(sampleGraph, List(Capability.test), config)
      val run = wf.jobs("test").steps.last.run.getOrElse("")
      assertTrue(
        wf.jobs.size == 1,
        wf.jobs.contains("test"),
        run.endsWith(" test") || run.contains("'test'"),
        !run.contains("schema/test"),
        !wf.jobs.contains("test-schema"),
      )
    },
    test("Aggregate testJoined joins per-module test commands") {
      val wf  = Planner.plan(sampleGraph, List(Capability.testJoined), config)
      val run = wf.jobs("test").steps.last.run.getOrElse("")
      assertTrue(
        wf.jobs.contains("test"),
        run.contains("schema/test"),
        run.contains("api/test"),
        run.contains(";"),
      )
    },
    test("verifyClean prefixes Aggregate and Graph Verify commands, not Publish") {
      val cleanCfg = config.copy(verifyClean = VerifyClean.CleanFull)
      val agg      = Planner.plan(sampleGraph, List(Capability.test), cleanCfg)
      val graph    = Planner.plan(sampleGraph, List(Capability.testGraph), cleanCfg)
      val pub      = Planner.plan(sampleGraph, List(Capability.publish), cleanCfg)
      assertTrue(
        agg.jobs("test").steps.last.run.exists(_.contains("cleanFull; test")),
        graph.jobs("test-schema").steps.last.run.exists(_.contains("cleanFull; schema/test")),
        !pub.jobs("publish").steps.last.run.exists(_.contains("cleanFull")),
      )
    },
    test("verifyClean.Clean prefixes Graph module commands") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(verifyClean = VerifyClean.Clean))
      assertTrue(wf.jobs("test-api").steps.last.run.exists(_.contains("clean; api/test")))
    },
    test("verifyCleanLabel emits runtime cleanFull when PR has the label") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.test),
        config.copy(verifyCleanLabel = PlanConfig.verifyCleanLabel("clean")),
      )
      val step = wf.jobs("test").steps.find(_.name.contains("test")).get
      assertTrue(
        step.env.get("ZIPX_VERIFY_CLEAN_FULL").exists { e =>
          e.contains("pull_request") && e.contains("labels.*.name") && e.contains("'clean'")
        },
        step.run.exists(_.contains("cleanFull; test")),
        step.run.exists(_.contains("""sbt 'test'""")),
        step.run.exists(_.contains("""ZIPX_VERIFY_CLEAN_FULL" = "true"""")),
      )
    },
    test("verifyCleanLabel does not apply to Publish or when static verifyClean is set") {
      val labeled = config.copy(verifyCleanLabel = PlanConfig.verifyCleanLabel("clean"))
      val pub     = Planner.plan(sampleGraph, List(Capability.publish), labeled)
      val static  =
        Planner.plan(sampleGraph, List(Capability.test), labeled.copy(verifyClean = VerifyClean.CleanFull))
      assertTrue(
        !pub.jobs("publish").steps.exists(_.env.contains("ZIPX_VERIFY_CLEAN_FULL")),
        !pub.jobs("publish").steps.exists(_.run.exists(_.contains("cleanFull"))),
        static.jobs("test").steps.last.run.contains("sbt 'cleanFull; test'"),
        !static.jobs("test").steps.exists(_.env.contains("ZIPX_VERIFY_CLEAN_FULL")),
      )
    },
    test("verifyCleanLabel None keeps a single sbt line") {
      val wf   = Planner.plan(sampleGraph, List(Capability.test), config.copy(verifyCleanLabel = None))
      val step = wf.jobs("test").steps.find(_.name.contains("test")).get
      assertTrue(
        step.run.contains("sbt 'test'"),
        !step.env.contains("ZIPX_VERIFY_CLEAN_FULL"),
      )
    },
    test("Aggregate publish emits one release-gated job with joined publish commands") {
      val wf  = Planner.plan(sampleGraph, List(Capability.publish), config)
      val job = wf.jobs("publish")
      val run = job.steps.last.run.getOrElse("")
      assertTrue(
        wf.jobs.keys.toList == List("publish"),
        run.contains("+schema/publish") || run.contains("schema/publish"),
        run.contains(";"),
        job.`if`.exists(_.contains("refs/tags/v")),
      )
    },
    test("Aggregate docker joins Docker/publish for each docker module") {
      val withDocker = sampleGraph.mapNodes {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      }
      val wf = Planner.plan(withDocker, List(Capability.docker), config)
      assertTrue(
        wf.jobs.keys.toList == List("docker"),
        wf.jobs("docker").steps.last.run.exists(_.contains("serviceA/Docker/publish")),
      )
    },
    test("Aggregate deploy is one job per target, not per module") {
      val graph = sampleGraph.mapNodes {
        case n if n.id == "serviceA" || n.id == "clientA" => n.copy(docker = true)
        case n                                            => n
      }
      val deploy = Capability.deploy(
        participates = n => n.id == "serviceA" || n.id == "clientA",
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ => stagingProd,
      )
      val wf = Planner.plan(graph, List(Capability.docker, deploy), config)
      assertTrue(
        wf.jobs.contains("deploy-staging"),
        wf.jobs.contains("deploy-prod"),
        !wf.jobs.contains("deploy-serviceA-staging"),
        wf.jobs("deploy-prod").environment.contains("production"),
        wf.jobs("deploy-staging").environment.isEmpty,
        wf.jobs("deploy-prod").needs.contains("docker"),
        wf.jobs("deploy-staging")
          .steps
          .last
          .run
          .exists(r => r.contains("serviceA/promote") && r.contains("clientA/promote") && r.contains(";")),
        wf.jobs("deploy-prod").`if`.exists(_.contains(prodOnly.render)),
      )
    },
    test("Layer test emits one job per toposort wave chained by needs") {
      val wf = Planner.plan(sampleGraph, List(Capability.testLayers), config)
      val l0 = wf.jobs.keys.filter(_.startsWith("test-L")).toList.sorted
      assertTrue(
        l0.nonEmpty,
        wf.jobs("test-L0").needs == Nil,
        wf.jobs.get("test-L1").exists(_.needs == List("test-L0")),
        wf.jobs("test-L0").steps.last.run.exists(_.contains(";")),
      )
    },
    test("Aggregate Verify does not emit affected setup even when AffectedOnPR") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.test),
        config.copy(affected = AffectedMode.AffectedOnPR),
      )
      assertTrue(!wf.jobs.contains("affected"), wf.jobs.contains("test"))
    },
    test("skipMergedPrPush emits verify-gate and gates Aggregate test") {
      val wf   = Planner.plan(sampleGraph, List(Capability.test), config.copy(skipMergedPrPush = true))
      val gate = wf.jobs("verify-gate")
      val test = wf.jobs("test")
      assertTrue(
        gate.`if`.contains("""github.event_name == 'push' && !startsWith(github.ref, 'refs/tags/')"""),
        gate.steps.exists(_.run.exists(_.contains("commits/${{ github.sha }}/pulls"))),
        test.needs.contains("verify-gate"),
        test.`if`.exists(_.contains("needs.verify-gate.outputs.run == 'true'")),
        test.`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
        test.`if`.exists(_.contains("github.event_name != 'workflow_dispatch'")),
      )
    },
    test("skipMergedPrPush does not gate Publish jobs") {
      val wf =
        Planner.plan(sampleGraph, List(Capability.test, Capability.publish), config.copy(skipMergedPrPush = true))
      assertTrue(
        wf.jobs("test").needs.contains("verify-gate"),
        !wf.jobs("publish").needs.contains("verify-gate"),
        wf.jobs("test").`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
        !wf.jobs("publish").`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
      )
    },
    test("skipMergedPrPush false omits verify-gate but still skips Verify on tags and dispatch") {
      val wf = Planner.plan(sampleGraph, List(Capability.test), config.copy(skipMergedPrPush = false))
      assertTrue(
        !wf.jobs.contains("verify-gate"),
        !wf.jobs.contains("cache-rehydrate"),
        wf.jobs("test").needs.isEmpty,
        wf.jobs("test").`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
        wf.jobs("test").`if`.exists(_.contains("github.event_name != 'workflow_dispatch'")),
      )
    },
    test("cacheRehydrateOnMerge emits inverted-gate LocalDir job on merged-PR skip") {
      val wf        = Planner.plan(sampleGraph, List(Capability.test), config.copy(skipMergedPrPush = true))
      val rehydrate = wf.jobs("cache-rehydrate")
      assertTrue(
        rehydrate.needs == List("verify-gate"),
        rehydrate.`if`.contains(
          "needs.verify-gate.result == 'success' && needs.verify-gate.outputs.run == 'false'"
        ),
        rehydrate.steps.exists(_.uses.exists(_.unwrap.contains("actions/cache"))),
        rehydrate.steps.exists(_.run.contains("sbt 'compile'")),
        !rehydrate.steps.exists(_.run.exists(_.contains("test"))),
      )
    },
    test("cacheRehydrateOnMerge uses configurable task and does not gate Publish") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.test, Capability.publish),
        config.copy(skipMergedPrPush = true, cacheRehydrateTask = SbtCommand.unsafeTask("Test/compile")),
      )
      assertTrue(
        wf.jobs("cache-rehydrate").steps.exists(_.run.contains("sbt 'Test/compile'")),
        !wf.jobs("publish").needs.contains("cache-rehydrate"),
        !wf.jobs("publish").needs.contains("verify-gate"),
      )
    },
    test("cacheRehydrateOnMerge false or remote cache omits rehydrate job") {
      val off = Planner.plan(
        sampleGraph,
        List(Capability.test),
        config.copy(skipMergedPrPush = true, cacheRehydrateOnMerge = false),
      )
      val remote = Planner.plan(
        sampleGraph,
        List(Capability.test),
        config.copy(skipMergedPrPush = true, cache = RemoteCacheProof.sidecar),
      )
      assertTrue(
        off.jobs.contains("verify-gate"),
        !off.jobs.contains("cache-rehydrate"),
        remote.jobs.contains("verify-gate"),
        !remote.jobs.contains("cache-rehydrate"),
      )
    },
    test("cacheRehydrate extraSteps and env are opt-in and sit between cache and sbt") {
      val browserSetup: StepContext => List[Step] = ctx =>
        List(
          Step(name = Some("Install browsers"), run = Some(s"echo setup-${ctx.node.id}"))
        )
      val withExtras = config.copy(
        skipMergedPrPush = true,
        cacheRehydrateExtraSteps = browserSetup,
        cacheRehydrateEnv = Map(
          "PLAYWRIGHT_BROWSERS_PATH" -> EnvValue.expr("${{ github.workspace }}/target/ms-playwright")
        ),
      )
      val rehydrate = Planner.plan(sampleGraph, List(Capability.test), withExtras).jobs("cache-rehydrate")
      val names     = rehydrate.steps.flatMap(_.name)
      val cacheIdx  = rehydrate.steps.indexWhere(_.uses.exists(_.unwrap.contains("actions/cache")))
      val extraIdx  = rehydrate.steps.indexWhere(_.name.contains("Install browsers"))
      val cmdIdx    = rehydrate.steps.indexWhere(_.run.exists(_.contains("sbt 'compile'")))
      val plain     = Planner.plan(sampleGraph, List(Capability.test), config.copy(skipMergedPrPush = true))
      assertTrue(
        rehydrate.env.get("PLAYWRIGHT_BROWSERS_PATH").contains("${{ github.workspace }}/target/ms-playwright"),
        names.contains("Install browsers"),
        cacheIdx >= 0 && extraIdx > cacheIdx && cmdIdx > extraIdx,
        plain.jobs("cache-rehydrate").env.isEmpty,
        !plain.jobs("cache-rehydrate").steps.exists(_.name.contains("Install browsers")),
      )
    },
    test("PlanConfig.env is build-wide; cacheRehydrateEnv overlays it") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.test, Capability.publish),
        config.copy(
          skipMergedPrPush = true,
          env = Map("SHARED" -> EnvValue.plain("everywhere")),
          cacheRehydrateEnv = Map("SHARED" -> EnvValue.plain("rehydrate-only"), "ONLY" -> EnvValue.plain("rh")),
        ),
      )
      assertTrue(
        wf.jobs("test").env.get("SHARED").contains("everywhere"),
        wf.jobs("publish").env.get("SHARED").contains("everywhere"),
        wf.jobs("verify-gate").env.get("SHARED").contains("everywhere"),
        wf.jobs("cache-rehydrate").env.get("SHARED").contains("rehydrate-only"),
        wf.jobs("cache-rehydrate").env.get("ONLY").contains("rh"),
      )
    },
    test("PlanConfig.env is omitted on workflow_call caller jobs") {
      val docs = Capability
        .steps(
          name = CapabilityName("docs"),
          steps = _ => Nil,
          phase = Phase.Publish,
          gate = Gate.OnReleaseTag,
        )
        .copy(workflowCall = Some(WorkflowCall(ActionRef("org/repo/.github/workflows/pages.yml@main"))))
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.test, docs),
        config.copy(env = Map("SHARED" -> EnvValue.plain("everywhere"))),
      )
      assertTrue(
        wf.jobs("test").env.get("SHARED").contains("everywhere"),
        wf.jobs("docs").uses.contains(ActionRef("org/repo/.github/workflows/pages.yml@main")),
        wf.jobs("docs").env.isEmpty,
        wf.jobs("docs").runsOn.isEmpty,
      )
    },
    test("Graph Verify still emits affected setup under AffectedOnPR") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.testGraph),
        config.copy(affected = AffectedMode.AffectedOnPR),
      )
      assertTrue(wf.jobs.contains("affected"), wf.jobs.contains("test-schema"))
    },
    test("capability condition None leaves Aggregate publish if unchanged") {
      val plain = Planner.plan(sampleGraph, List(Capability.publish), config)
      val withC =
        Planner.plan(sampleGraph, List(Capability.publish.withCondition(None)), config)
      assertTrue(plain.jobs("publish").`if` == withC.jobs("publish").`if`)
    },
    test("capability condition ANDed with OnReleaseTag on Aggregate") {
      val cap  = Capability.publish.withCondition(JobCondition.repositoryIs("acme/fork"))
      val cond = Planner.plan(sampleGraph, List(cap), config).jobs("publish").`if`.getOrElse("")
      assertTrue(
        cond.contains("startsWith(github.ref, 'refs/tags/v')"),
        cond.contains("github.repository == 'acme/fork'"),
        cond.contains("&&"),
      )
    },
    test("Gate.Always + capability condition has no tag clause") {
      val cap = Capability.docker
        .copy(gate = Gate.Always)
        .withCondition(JobCondition.hasPrLabel("deploy-stg"))
      val graph = sampleGraph.mapNodes {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      }
      val cond = Planner.plan(graph, List(cap), config).jobs("docker").`if`.getOrElse("")
      assertTrue(
        cond.contains("deploy-stg"),
        !cond.contains("refs/tags/v"),
      )
    },
    test("capability + target conditions both present on Aggregate-by-target") {
      val cap = Capability.deploy(
        participates = _.id == "serviceA",
        command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
        targets = _ =>
          List(
            Target(TargetName("stg"), condition = Some(JobCondition.hasPrLabel("deploy-stg"))),
            Target(TargetName("prod"), condition = Some(JobCondition.refStartsWith("refs/tags/v"))),
          ),
        needsCapabilities = Nil,
        gate = Gate.Always,
        condition = Some(JobCondition.repositoryIs("acme/app")),
      )
      val wf   = Planner.plan(sampleGraph, List(cap), config)
      val stg  = wf.jobs("deploy-stg").`if`.getOrElse("")
      val prod = wf.jobs("deploy-prod").`if`.getOrElse("")
      assertTrue(
        stg.contains("github.repository == 'acme/app'"),
        stg.contains("deploy-stg"),
        stg.contains("labels"),
        prod.contains("github.repository == 'acme/app'"),
        prod.contains("startsWith(github.ref, 'refs/tags/v')"),
        !stg.contains("startsWith(github.ref, 'refs/tags/v')"),
      )
    },
    test("OnReleaseTag + Target HasPrLabel still includes tag clause (stage-on-PR footgun)") {
      val cap = Capability.dockerGraph.copy(
        gate = Gate.OnReleaseTag,
        targets = _ => List(Target(TargetName("stg"), condition = Some(JobCondition.hasPrLabel("deploy-stg")))),
      )
      val graph = sampleGraph.mapNodes {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      }
      val cond = Planner.plan(graph, List(cap), config).jobs("docker-serviceA-stg").`if`.getOrElse("")
      assertTrue(
        cond.contains("startsWith(github.ref, 'refs/tags/v')"),
        cond.contains("deploy-stg"),
      )
    },
    test("Once capability condition appears on the single job") {
      val cap = Capability.once(
        name = Capability.PublishName,
        command = SbtCommand.session(SbtCommand.unsafeTask("publishSigned"), SbtCommand.unsafeCommand("sonaRelease")),
        phase = Phase.Publish,
        gate = Gate.OnReleaseTag,
        condition = Some(JobCondition.repositoryIs("early-effect/zipx")),
      )
      val cond = Planner.plan(sampleGraph, List(cap), config).jobs("publish").`if`.getOrElse("")
      assertTrue(
        cond.contains("refs/tags/v"),
        cond.contains("early-effect/zipx"),
      )
    },
    test("Once workflowCall job also gets capability condition") {
      val cap = Capability
        .steps(
          name = CapabilityName("docs"),
          steps = _ => Nil,
          phase = Phase.Publish,
          gate = Gate.OnReleaseTag,
        )
        .copy(workflowCall = Some(WorkflowCall(ActionRef("org/repo/.github/workflows/pages.yml@main"))))
        .withCondition(JobCondition.repositoryIs("org/repo"))
      val job = Planner.plan(sampleGraph, List(cap), config).jobs("docs")
      assertTrue(
        job.uses.contains(ActionRef("org/repo/.github/workflows/pages.yml@main")),
        job.`if`.exists(_.contains("github.repository == 'org/repo'")),
        job.`if`.exists(_.contains("refs/tags/v")),
      )
    },
    test("Layer jobs each carry capability condition") {
      val cap = Capability.testLayers.withCondition(JobCondition.varNonEmpty("RUN_LAYERS"))
      val wf  = Planner.plan(sampleGraph, List(cap), config)
      val ifs = wf.jobs.values.flatMap(_.`if`).toList
      assertTrue(ifs.nonEmpty, ifs.forall(_.contains("vars.RUN_LAYERS != ''")))
    },
    test("Graph affected Verify ANDs capability condition without dropping affected clauses") {
      val cap = Capability.testGraph.withCondition(JobCondition.repositoryIs("acme/ci"))
      val wf  = Planner.plan(
        sampleGraph,
        List(cap),
        config.copy(affected = AffectedMode.AffectedOnPR),
      )
      val cond = wf.jobs("test-schema").`if`.getOrElse("")
      assertTrue(
        cond.contains("!cancelled()"),
        cond.contains("needs.affected.outputs.modules"),
        cond.contains("github.repository == 'acme/ci'"),
      )
    },
    test("two Publish caps keep independent conditions") {
      val central = Capability.publish
      val ghp     = Capability.publish
        .copy(name = CapabilityName("github-packages"))
        .withCondition(JobCondition.repositoryIs("acme/fork"))
      val wf = Planner.plan(sampleGraph, List(central, ghp), config)
      assertTrue(
        wf.jobs.contains("publish"),
        wf.jobs.contains("github-packages"),
        !wf.jobs("publish").`if`.exists(_.contains("acme/fork")),
        wf.jobs("github-packages").`if`.exists(_.contains("acme/fork")),
      )
    },
    test("adding only condition leaves needs and permissions stable") {
      val base  = Capability.publish
      val gated = base
        .copy(permissions = Map("contents" -> "read"))
        .withCondition(JobCondition.repositoryIs("a/b"))
      val plainJob =
        Planner.plan(sampleGraph, List(base.copy(permissions = Map("contents" -> "read"))), config).jobs("publish")
      val gatedJob = Planner.plan(sampleGraph, List(gated), config).jobs("publish")
      assertTrue(
        plainJob.needs == gatedJob.needs,
        plainJob.permissions == gatedJob.permissions,
        plainJob.env == gatedJob.env,
        plainJob.`if` != gatedJob.`if`,
      )
    },
    test("andCondition ANDs onto an existing capability condition") {
      val base = Capability.publish.withCondition(JobCondition.onReleaseTag)
      val both = base.andCondition(JobCondition.repositoryIs("a/b"))
      val job  = Planner.plan(sampleGraph, List(both.copy(gate = Gate.Always)), config).jobs("publish")
      assertTrue(
        job.`if`.exists(_.contains("refs/tags/v")),
        job.`if`.exists(_.contains("a/b")),
        job.`if`.exists(_.contains("&&")),
      )
    },
    test("empty ModuleGraph produces valid workflow with no capability jobs") {
      val empty = GraphFixture(Nil)
      val wf    = Planner.plan(empty, List(Capability.testGraph), config)
      assertTrue(
        wf.jobs.isEmpty,
        wf.concurrency.isDefined,
        wf.on.pullRequest.isDefined,
        wf.on.push.isDefined,
      )
    },

    test("Capability with zero participants produces no jobs") {
      val nobody = Capability(
        name = CapabilityName("nobody"),
        phase = Phase.Verify,
        ordering = Ordering.DependencyOrdered,
        gate = Gate.Always,
        participates = _ => false,
        command = CommandSource.PerModule(n => SbtCommand.module(n, SbtCommand.unsafeTask("nobody"))),
        matrixed = false,
        targets = _ => Nil,
        scope = CapabilityScope.Graph,
      )
      val wf = Planner.plan(sampleGraph, List(nobody), config)
      assertTrue(
        wf.jobs.isEmpty,
        wf.concurrency.isDefined,
      )
    },

    test("Aggregate capability with empty graph produces no jobs (nothing to aggregate)") {
      val empty = GraphFixture(Nil)
      val cap   = Capability.testGraph.copy(scope = CapabilityScope.Aggregate)
      val wf    = Planner.plan(empty, List(cap), config)
      assertTrue(
        wf.jobs.isEmpty
      )
    },

    test("Once capability with empty graph produces single job") {
      val empty = GraphFixture(Nil)
      val cap   = Capability.testGraph.copy(scope = CapabilityScope.Once)
      val wf    = Planner.plan(empty, List(cap), config)
      assertTrue(
        wf.jobs.size == 1,
        wf.jobs.contains("test"),
      )
    },

    test("Layer capability with empty graph produces no jobs") {
      val empty = GraphFixture(Nil)
      val cap   = Capability.testGraph.copy(scope = CapabilityScope.Layer)
      val wf    = Planner.plan(empty, List(cap), config)
      assertTrue(
        wf.jobs.isEmpty
      )
    },
  )
end PlannerSpec
