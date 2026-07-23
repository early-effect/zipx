package zipx.core

import zio.test.*
import zipx.workflow.*

object PlannerSpec extends ZIOSpecDefault:
  import Fixtures.*
  import EnvValue.secret

  // Base config for M1/M2 tests: Always mode keeps job graphs focused (no affected setup job / gating).
  // M3 tests opt into AffectedOnPR explicitly via config.copy(...).
  private val config = PlanConfig(
    workflowName = "CI",
    cacheEpoch = "1.2.3-ci",
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
  )

  // A deploy-style capability on serviceA fanning out to staging + prod (prod carries a GitHub Environment).
  private def deployCap(targets: List[Target]) = Capability(
    name = "deploy",
    phase = Phase.Publish,
    ordering = Ordering.DependencyOrdered,
    gate = Gate.OnReleaseTag,
    participates = _.id == "serviceA",
    command = n => s"${n.id}/deploy",
    matrixed = false,
    targets = _ => targets,
    scope = CapabilityScope.Graph,
  )
  private val stagingProd = List(
    Target("staging", env = Map("DEPLOY_ROLE" -> secret"STAGING_ROLE", "TIER" -> EnvValue.plain("staging"))),
    Target(
      "prod",
      environment = Some("production"),
      env = Map("DEPLOY_ROLE" -> secret"PROD_ROLE", "TIER" -> EnvValue.plain("prod")),
      condition = Some(JobCondition.refIs("refs/heads/main")),
    ),
  )

  def spec = suite("Planner")(
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
      // schema is cross-built → matrix with both versions.
      assertTrue(
        wf.jobs("test-schema").strategy.exists(_.matrix("scala") == cross),
        // legacyClient is 2.13-only → single version, so no matrix axis emitted.
        wf.jobs("test-legacyClient").strategy.isEmpty,
        // core is 3-only → no matrix.
        wf.jobs("test-core").strategy.isEmpty,
      )
    },
    test("publish jobs are dependency-ordered (the L0/L1/L2 headline case)") {
      val wf = Planner.plan(sampleGraph, List(Capability.publishGraph), config)
      assertTrue(
        // L0: schema has no publishing ancestors.
        wf.jobs("publish-schema").needs == Nil,
        // L1: api and legacyClient need only schema.
        wf.jobs("publish-api").needs == List("publish-schema"),
        wf.jobs("publish-legacyClient").needs == List("publish-schema"),
        // L2: clientA and clientB need api.
        wf.jobs("publish-clientA").needs == List("publish-api"),
        wf.jobs("publish-clientB").needs == List("publish-api"),
        // Only publishing modules get publish jobs.
        !wf.jobs.contains("publish-core"),
        !wf.jobs.contains("publish-serviceA"),
      )
    },
    test("publish command crosses when the module is cross-built, single otherwise") {
      val wf    = Planner.plan(sampleGraph, List(Capability.publishGraph), config)
      val runOf = (id: String) => wf.jobs(id).steps.last.run.getOrElse("")
      assertTrue(
        runOf("publish-api").contains("+api/publish"), // cross → +
        runOf("publish-legacyClient").contains("legacyClient/publish"),
        !runOf("publish-legacyClient").contains("+legacyClient"), // 2.13-only → no +
      )
    },
    test("publish jobs are never matrixed — the `+publish` leg crosses internally") {
      val wf = Planner.plan(sampleGraph, List(Capability.publishGraph), config)
      // api is cross-built, but its publish job must NOT expand into a scala matrix
      // (else the run would be a contradictory `++${{ matrix.scala }} +api/publish`).
      assertTrue(
        wf.jobs("publish-api").strategy.isEmpty,
        !wf.jobs("publish-api").steps.last.run.getOrElse("").contains("matrix.scala"),
      )
    },
    test("LocalDir cache keys include run_id + job id so same-run jobs accumulate and can save") {
      val wf       = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      val coreStep = wf.jobs("test-core").steps.find(_.uses.exists(_.startsWith("actions/cache@")))
      val apiStep  = wf.jobs("test-api").steps.find(_.uses.exists(_.startsWith("actions/cache@")))
      val coreKey  = coreStep.map(_.`with`("key")).getOrElse("")
      val apiKey   = apiStep.map(_.`with`("key")).getOrElse("")
      val restore  = coreStep.map(_.`with`("restore-keys")).getOrElse("")
      val paths    = coreStep.map(_.`with`("path")).getOrElse("")
      val java     = wf.jobs("test-core").steps.find(_.uses.exists(_.startsWith("actions/setup-java@")))
      val sbt      = wf.jobs("test-core").steps.find(_.uses.exists(_.startsWith("sbt/setup-sbt@")))
      assertTrue(
        // Primary key always misses within a run (run_id) so the job can save after restoring upstream.
        coreKey.contains("ubuntu-latest-jdk21-sbt-1.2.3-ci-${{ github.run_id }}-test-core"),
        apiKey.contains("ubuntu-latest-jdk21-sbt-1.2.3-ci-${{ github.run_id }}-test-api"),
        coreKey != apiKey,
        // Same-run prefix first (accumulated upstream), then epoch, then older epochs.
        restore.contains("ubuntu-latest-jdk21-sbt-1.2.3-ci-${{ github.run_id }}-"),
        restore.contains("ubuntu-latest-jdk21-sbt-1.2.3-ci-"),
        restore.contains("ubuntu-latest-jdk21-sbt-"),
        paths.contains("~/.sbt"),
        paths.contains("~/.cache/sbt"),
        paths.contains("~/.cache/coursier"),
        paths.contains("target"),
        !java.exists(_.`with`.contains("cache")),
        sbt.exists(_.`with`.get("disk-cache").contains("false")),
      )
    },
    test("cache key is identical across commits with the same epoch+job template, differs across epochs") {
      def keyFor(epoch: String) =
        Planner
          .plan(sampleGraph, List(Capability.testGraph), config.copy(cacheEpoch = epoch))
          .jobs("test-core")
          .steps
          .find(_.uses.exists(_.startsWith("actions/cache@")))
          .map(_.`with`("key"))
      assertTrue(
        keyFor("1.2.3-ci") == keyFor("1.2.3-ci"),
        keyFor("1.2.3-ci") != keyFor("1.3.0"),
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
    // ---- M3 affected-only ----
    test("affected mode adds a leading `affected` setup job with a modules output") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      assertTrue(
        wf.jobs.contains("affected"),
        wf.jobs.keys.head == "affected", // emitted first
        wf.jobs("affected").outputs.contains("modules"),
        wf.jobs("affected").steps.exists(_.`with`.get("fetch-depth").contains("0")),
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
        // upstream test-schema must not have failed, but skipped/success are both allowed.
        cond.contains("needs.test-schema.result != 'failure'"),
        // Tag pushes skip Verify entirely (release only needs Publish/Deploy).
        cond.contains("!startsWith(github.ref, 'refs/tags/')"),
      )
    },
    test("by default the affected job builds all on push (no before-sha diff)") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      val script = wf.jobs("affected").steps.find(_.id.contains("compute")).flatMap(_.run).getOrElse("")
      assertTrue(
        script.contains("pull_request"),
        !script.contains("github.event.before"), // push path not present
        script.contains("""modules='["all"]'"""),
      )
    },
    test("affected script never captures sbt stdout into modules (GITHUB_OUTPUT-safe)") {
      // Regression: sbt 2 prints server banners on stdout; `modules=$(sbt …)` poisoned GITHUB_OUTPUT and failed CI.
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.AffectedOnPR))
      val script = wf.jobs("affected").steps.find(_.id.contains("compute")).flatMap(_.run).getOrElse("")
      assertTrue(
        script.contains("sbt -batch --error \"zipxAffectedModules $BASE\""),
        script.contains("modules=$(cat target/zipx-affected.json)"),
        !script.contains("modules=$(sbt"),
        wf.jobs("affected").steps.exists(_.uses.exists(_.startsWith("actions/setup-java@"))),
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
        // force-push / branch-create guard: all-zero sha → build everything.
        script.contains("0000000000000000000000000000000000000000"),
      )
    },
    test("Always mode emits no affected job and no affected gating") {
      val wf = Planner.plan(sampleGraph, List(Capability.testGraph), config.copy(affected = AffectedMode.Always))
      assertTrue(
        !wf.jobs.contains("affected"),
        // Verify still skips tag pushes and workflow_dispatch; no affected membership gating.
        wf.jobs("test-api").`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
        wf.jobs("test-api").`if`.exists(_.contains("github.event_name != 'workflow_dispatch'")),
        !wf.jobs("test-api").`if`.exists(_.contains("needs.affected")),
        !wf.jobs("test-api").needs.contains("affected"),
      )
    },
    // ---- M5 remote caches ----
    test("LocalDir backend caches via epoch-keyed actions/cache and adds no services/env") {
      val wf  = Planner.plan(sampleGraph, List(Capability.testGraph), config)
      val job = wf.jobs("test-core")
      assertTrue(
        job.services.isEmpty,
        job.env.isEmpty,
        job.steps.exists(_.uses.exists(_.startsWith("actions/cache@"))),
        job.steps.exists(s =>
          s.uses.exists(_.startsWith("sbt/setup-sbt@")) && s.`with`.get("disk-cache").contains("false")
        ),
      )
    },
    test("BazelRemoteSidecar backend emits a service sidecar and the remote-cache env, no actions/cache") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.testGraph),
        config.copy(cache = CacheBackend.BazelRemoteSidecar("buchgr/bazel-remote:latest", 9092)),
      )
      val job = wf.jobs("test-core")
      assertTrue(
        job.services.contains("bazel-remote"),
        job.services("bazel-remote").image == "buchgr/bazel-remote:latest",
        job.services("bazel-remote").ports == List("9092:9092"),
        job.env.get("ZIPX_REMOTE_CACHE").contains("grpc://localhost:9092"),
        !job.steps.exists(_.uses.exists(_.startsWith("actions/cache@"))),
        // remote backends leave setup-sbt disk-cache at its default (no disk-cache: false)
        !job.steps.exists(s => s.uses.exists(_.startsWith("sbt/setup-sbt@")) && s.`with`.contains("disk-cache")),
      )
    },
    test("ManagedRemote backend sets the endpoint + header-from-secret env, no service") {
      val wf = Planner.plan(
        sampleGraph,
        List(Capability.testGraph),
        config.copy(cache = CacheBackend.ManagedRemote("grpcs://cache.buildbuddy.io", "BUILDBUDDY_KEY")),
      )
      val job = wf.jobs("test-core")
      assertTrue(
        job.services.isEmpty,
        job.env.get("ZIPX_REMOTE_CACHE").contains("grpcs://cache.buildbuddy.io"),
        job.env.get("ZIPX_REMOTE_CACHE_HEADER").exists(_.contains("secrets.BUILDBUDDY_KEY")),
      )
    },
    // ---- M4 docker ----
    test("docker capability emits release-gated Docker/publish jobs only for docker-enabled modules") {
      // Mark the two service modules as docker targets.
      val withDocker = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" || n.id == "serviceB" => n.copy(docker = true)
        case n                                             => n
      })
      val wf = Planner.plan(withDocker, List(Capability.dockerGraph), config)
      assertTrue(
        wf.jobs.contains("docker-serviceA"),
        wf.jobs.contains("docker-serviceB"),
        !wf.jobs.contains("docker-schema"), // library, not a docker target
        wf.jobs("docker-serviceA").steps.last.run.exists(_.contains("serviceA/Docker/publish")),
        wf.jobs("docker-serviceA").`if`.exists(_.contains("refs/tags/v")),
        wf.jobs("docker-serviceA").strategy.isEmpty, // never matrixed
      )
    },
    // ---- M6a/M6b — environments, target fan-out, env injection ----
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
        deploys == List("deploy-serviceA-prod", "deploy-serviceA-staging"), // sorted by target name
        !wf.jobs.contains("deploy-serviceA"),                               // no un-suffixed job when fanned out
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
        cond.contains("startsWith(github.ref, 'refs/tags/v')"), // capability gate preserved
        cond.contains("github.ref == 'refs/heads/main'"),       // target condition ANDed in
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
    // ---- M6c — cross-capability needs, Phase.Deploy, permissions, cycle guard ----
    test("deploy jobs need the module's docker job (cross-capability needs)") {
      // serviceA is a docker target and the deploy target.
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val deploy = Capability.deployGraph(
        participates = _.id == "serviceA",
        command = n => s"${n.id}/deploy",
        targets = _ => stagingProd,
      )
      val wf = Planner.plan(graph, List(Capability.dockerGraph, deploy), config)
      assertTrue(
        wf.jobs("deploy-serviceA-prod").needs.contains("docker-serviceA"),
        wf.jobs("deploy-serviceA-staging").needs.contains("docker-serviceA"),
      )
    },
    test("deploy jobs sort after docker jobs in the workflow (phase order)") {
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val deploy = Capability.deployGraph(_.id == "serviceA", n => s"${n.id}/deploy", _ => stagingProd)
      // Declare deploy BEFORE docker to prove phase order (not declaration order) governs.
      val wf   = Planner.plan(graph, List(deploy, Capability.dockerGraph), config)
      val keys = wf.jobs.keys.toList
      assertTrue(keys.indexOf("docker-serviceA") < keys.indexOf("deploy-serviceA-prod"))
    },
    test("Capability.permissions renders on the job (OIDC id-token)") {
      val deploy = Capability.deployGraph(
        participates = _.id == "serviceA",
        command = n => s"${n.id}/deploy",
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
        "a",
        Phase.Publish,
        Ordering.DependencyOrdered,
        Gate.Always,
        _ => true,
        _ => "a",
        false,
        needsCapabilities = List("b"),
      )
      val b = Capability(
        "b",
        Phase.Publish,
        Ordering.DependencyOrdered,
        Gate.Always,
        _ => true,
        _ => "b",
        false,
        needsCapabilities = List("a"),
      )
      assertTrue(scala.util.Try(Planner.plan(sampleGraph, List(a, b), config)).isFailure)
    },
    // ---- M6d — extension seam, custom capabilities, list runners ----
    test("extraSteps are injected before the command and can reference the target") {
      val cap = Capability
        .deployGraph(
          participates = _.id == "serviceA",
          command = n => s"${n.id}/deploy",
          targets = _ => stagingProd,
        )
        .copy(
          extraSteps = _ =>
            List(
              Step(
                name = Some("Configure credentials"),
                uses = Some("aws-actions/configure-aws-credentials@v6"),
                `with` = Map("role-to-assume" -> "${{ env.DEPLOY_ROLE }}"),
              )
            )
        )
      val steps   = Planner.plan(sampleGraph, List(cap), config).jobs("deploy-serviceA-prod").steps
      val credIdx = steps.indexWhere(_.uses.contains("aws-actions/configure-aws-credentials@v6"))
      val cmdIdx  = steps.indexWhere(_.run.exists(_.contains("serviceA/deploy")))
      assertTrue(credIdx >= 0, cmdIdx >= 0, credIdx < cmdIdx) // injected before the command
    },
    test("postSteps are injected after the command") {
      val cap = Capability.custom(
        name = "publish",
        command = n => s"${n.id}/publish",
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
        name = "release",
        command = _ => "release",
        participates = _.id == "serviceA",
        runsOn = Some(List("self-hosted", "linux")),
      )
      assertTrue(
        Planner.plan(sampleGraph, List(cap), config).jobs("release-serviceA").runsOn == List("self-hosted", "linux")
      )
    },
    test("Capability.custom emits a job with its command and defaults") {
      val cap = Capability.custom(name = "notify", command = _ => "notify", participates = _.id == "schema")
      val wf  = Planner.plan(sampleGraph, List(cap), config)
      assertTrue(
        wf.jobs.contains("notify-schema"),
        wf.jobs("notify-schema").steps.last.run.exists(_.contains("notify")),
      )
    },
    // ---- Gap 1: multi-registry image push (docker fanned out over registry targets) ----
    test("a docker capability fans out over registry targets with per-registry credential steps") {
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val registries = List(
        Target("us", env = Map("REGISTRY" -> EnvValue.plain("111.dkr.ecr.us-east-1"), "ROLE" -> secret"US_ROLE")),
        Target("eu", env = Map("REGISTRY" -> EnvValue.plain("222.dkr.ecr.eu-west-1"), "ROLE" -> secret"EU_ROLE")),
      )
      // A docker capability that pushes to multiple registries — same shape as deploy.
      val multiDocker = Capability
        .custom(
          name = "docker",
          command = n => s"${n.id}/Docker/publish",
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
                uses = Some("aws-actions/amazon-ecr-login@v2"),
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
        wf.jobs("docker-serviceA-us").steps.exists(_.uses.contains("aws-actions/amazon-ecr-login@v2")),
      )
    },
    // ---- Gap 3: run-once build-wide gate ----
    test("a Once capability emits a single build-wide job (no module suffix)") {
      val fmt = Capability.once("fmt", "scalafmtCheckAll")
      val wf  = Planner.plan(sampleGraph, List(fmt), config)
      assertTrue(
        wf.jobs.contains("fmt"),                      // bare name, not fmt-<module>
        wf.jobs.keys.count(_.startsWith("fmt")) == 1, // exactly one job
        wf.jobs("fmt").steps.last.run.exists(_.contains("scalafmtCheckAll")),
      )
    },
    test("per-module capabilities can depend on a Once gate by name") {
      val fmt  = Capability.once("fmt", "scalafmtCheckAll")
      val test = Capability.testGraph.copy(needsCapabilities = List("fmt"))
      val wf   = Planner.plan(sampleGraph, List(fmt, test), config)
      assertTrue(
        wf.jobs("test-schema").needs.contains("fmt"),
        wf.jobs("test-api").needs.contains("fmt"),
      )
    },
    // ---- M7 — typed secrets & capability env ----
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
            name = "ship",
            command = _ => "ship",
            participates = _.id == "serviceA",
            gate = Gate.Always,
            env = Map("TIER" -> EnvValue.plain("capability-default"), "SHARED" -> EnvValue.plain("from-cap")),
            targets = _ =>
              List(
                Target(
                  "prod",
                  env = Map("TIER" -> EnvValue.plain("prod"), "EXTRA" -> EnvValue.plain("only-target")),
                )
              ),
          )
        ),
        config.copy(cache = CacheBackend.ManagedRemote("grpcs://cache.example", "CACHE_KEY")),
      )
      val job = wf.jobs("ship-serviceA-prod")
      assertTrue(
        job.env.get("TIER").contains("prod"),                               // target wins
        job.env.get("SHARED").contains("from-cap"),                         // capability preserved
        job.env.get("EXTRA").contains("only-target"),                       // target-only
        job.env.get("ZIPX_REMOTE_CACHE").contains("grpcs://cache.example"), // cache preserved
        job.env.get("ZIPX_REMOTE_CACHE_HEADER").contains("${{ secrets.CACHE_KEY }}"),
      )
    },
    test("Once jobs receive capability.env") {
      val fmt = Capability.once("fmt", "scalafmtCheckAll", env = Map("SCALAFMT_VERSION" -> EnvValue.plain("3.8")))
      assertTrue(
        Planner.plan(sampleGraph, List(fmt), config).jobs("fmt").env.get("SCALAFMT_VERSION").contains("3.8")
      )
    },
    test("FromEnv renders as ${{ env.NAME }}") {
      val cap = Capability.custom(
        name = "relay",
        command = _ => "relay",
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
        name = "expr",
        command = _ => "x",
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
    test("ManagedRemote rejects an invalid headerSecret name at plan time") {
      assertTrue(
        scala.util
          .Try(
            Planner.plan(
              sampleGraph,
              List(Capability.testGraph),
              config.copy(cache = CacheBackend.ManagedRemote("grpcs://x", "bad name")),
            )
          )
          .isFailure
      )
    },
    // ---- Adversarial / gap coverage for existing planner behavior ----
    test("publish contracts edges through a non-publishing intermediate") {
      // pubRoot → middle(no publish) → pubLeaf : publish-pubLeaf must need publish-pubRoot, not a missing middle job.
      val g = ModuleGraph(
        List(
          ModuleNode("pubRoot", publishes = true, crossScalaVersions = List(scala3)),
          ModuleNode("middle", dependsOn = List("pubRoot"), publishes = false, crossScalaVersions = List(scala3)),
          ModuleNode("pubLeaf", dependsOn = List("middle"), publishes = true, crossScalaVersions = List(scala3)),
        )
      )
      val wf = Planner.plan(g, List(Capability.publishGraph), config)
      assertTrue(
        wf.jobs("publish-pubLeaf").needs == List("publish-pubRoot"),
        !wf.jobs.contains("publish-middle"),
      )
    },
    test("cross-capability needs fans out over all per-target jobs of the dependency") {
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val multiDocker = Capability.custom(
        name = "docker",
        command = n => s"${n.id}/Docker/publish",
        participates = _.docker,
        targets = _ => List(Target("us"), Target("eu")),
      )
      val deploy = Capability.deployGraph(
        participates = _.id == "serviceA",
        command = n => s"${n.id}/promote",
        targets = _ => List(Target("staging")),
      )
      val needs = Planner.plan(graph, List(multiDocker, deploy), config).jobs("deploy-serviceA-staging").needs
      assertTrue(
        needs.contains("docker-serviceA-eu"),
        needs.contains("docker-serviceA-us"),
      )
    },
    test("unknown needsCapabilities names are ignored (do not crash)") {
      val cap = Capability.testGraph.copy(needsCapabilities = List("does-not-exist"))
      val wf  = Planner.plan(sampleGraph, List(cap), config)
      assertTrue(!wf.jobs("test-schema").needs.contains("does-not-exist"))
    },
    test("ciRelevant=false modules are excluded from the test fan-out") {
      val g = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "core" => n.copy(ciRelevant = false)
        case n                   => n
      })
      val wf = Planner.plan(g, List(Capability.testGraph), config)
      assertTrue(!wf.jobs.contains("test-core"), wf.jobs.contains("test-schema"))
    },
    test("StepContext.matrixed is true only when a Scala matrix is active") {
      var seen: List[Boolean] = Nil
      val cap                 = Capability.testGraph.copy(
        participates = _.id == "api", // cross-built
        extraSteps = ctx =>
          seen = ctx.matrixed :: seen; Nil,
      )
      val noMatrix = Capability.testGraph.copy(
        participates = _.id == "core", // single scala
        extraSteps = ctx =>
          seen = ctx.matrixed :: seen; Nil,
      )
      val _ = Planner.plan(sampleGraph, List(cap, noMatrix), config)
      assertTrue(seen.contains(true), seen.contains(false))
    },
    test("andConditions with Always gate still applies a bare target condition") {
      val cap = Capability.custom(
        name = "gate",
        command = _ => "x",
        participates = _.id == "schema",
        gate = Gate.Always,
        targets = _ => List(Target("only", condition = Some(JobCondition.refIs("refs/heads/main")))),
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
      val once = Capability.once("releaseNotes", "notes", gate = Gate.OnReleaseTag)
      assertTrue(
        Planner
          .plan(sampleGraph, List(once), config)
          .jobs("releaseNotes")
          .`if`
          .exists(_.contains("refs/tags/v"))
      )
    },
    test("root modules with empty baseDir never own changed files via the planner path") {
      // Sanity: Affected is what the affected job uses; empty baseDir is excluded by design.
      val g = ModuleGraph(List(ModuleNode("root", baseDir = ""), ModuleNode("lib", baseDir = "lib")))
      assertTrue(Affected.owningModule(g, "README.md").isEmpty, Affected.owningModule(g, "lib/X.scala").contains("lib"))
    },
    // ---- Aggregate / Layer / deploy-by-target ----
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
      val withDocker = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val wf = Planner.plan(withDocker, List(Capability.docker), config)
      assertTrue(
        wf.jobs.keys.toList == List("docker"),
        wf.jobs("docker").steps.last.run.exists(_.contains("serviceA/Docker/publish")),
      )
    },
    test("Aggregate deploy is one job per target, not per module") {
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" || n.id == "clientA" => n.copy(docker = true)
        case n                                            => n
      })
      val deploy = Capability.deploy(
        participates = n => n.id == "serviceA" || n.id == "clientA",
        command = n => s"${n.id}/promote",
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
        wf.jobs("deploy-prod").`if`.exists(_.contains("refs/heads/main")),
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
        wf.jobs("test").needs.isEmpty,
        wf.jobs("test").`if`.exists(_.contains("!startsWith(github.ref, 'refs/tags/')")),
        wf.jobs("test").`if`.exists(_.contains("github.event_name != 'workflow_dispatch'")),
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
    // ---- JobCondition (capability + target) ----
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
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val cond = Planner.plan(graph, List(cap), config).jobs("docker").`if`.getOrElse("")
      assertTrue(
        cond.contains("deploy-stg"),
        !cond.contains("refs/tags/v"),
      )
    },
    test("capability + target conditions both present on Aggregate-by-target") {
      val cap = Capability.deploy(
        participates = _.id == "serviceA",
        command = n => s"${n.id}/promote",
        targets = _ =>
          List(
            Target("stg", condition = Some(JobCondition.hasPrLabel("deploy-stg"))),
            Target("prod", condition = Some(JobCondition.refStartsWith("refs/tags/v"))),
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
        targets = _ => List(Target("stg", condition = Some(JobCondition.hasPrLabel("deploy-stg")))),
      )
      val graph = sampleGraph.copy(nodes = sampleGraph.nodes.map {
        case n if n.id == "serviceA" => n.copy(docker = true)
        case n                       => n
      })
      val cond = Planner.plan(graph, List(cap), config).jobs("docker-serviceA-stg").`if`.getOrElse("")
      assertTrue(
        cond.contains("startsWith(github.ref, 'refs/tags/v')"),
        cond.contains("deploy-stg"),
      )
    },
    test("Once capability condition appears on the single job") {
      val cap = Capability.once(
        name = "publish",
        command = "publishSigned; sonaRelease",
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
        .once(name = "docs", command = "true", phase = Phase.Publish, gate = Gate.OnReleaseTag)
        .copy(workflowCall = Some(WorkflowCall("org/repo/.github/workflows/pages.yml@main")))
        .withCondition(JobCondition.repositoryIs("org/repo"))
      val job = Planner.plan(sampleGraph, List(cap), config).jobs("docs")
      assertTrue(
        job.uses.contains("org/repo/.github/workflows/pages.yml@main"),
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
        .copy(name = "github-packages")
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
  )
end PlannerSpec
