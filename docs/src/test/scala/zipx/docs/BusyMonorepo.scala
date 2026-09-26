package zipx.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zipx.core.*
import zipx.docs.DocsFixtures.*
import zipx.workflow.Cron
import zio.test.*

/** The four ways a monorepo's CI goes wrong at scale, and the one configuration that fixes them together. */
object BusyMonorepo extends DocSpecSuite:

  private val targets = List(
    Target(TargetName("stg"), environment = Some("staging"), group = Some(TargetGroup("pre-prod"))),
    Target(TargetName("prod"), environment = Some("production")),
  )

  /** The page's running example: the builtin test, an image, a deploy, and an integration test over the image. */
  private val capabilities = List(
    Capability.testAffected(onPush = false),
    Capability.dockerGraph.copy(gate = Gate.Always),
    Capability.deployGraph(
      participates = _.docker,
      command = n => SbtCommand.module(n, SbtCommand.unsafeTask("promote")),
      targets = _ => targets,
      gate = Gate.Always,
    ),
    Capability
      .once(
        name = CapabilityName("image-it"),
        command = SbtCommand.unsafeTask("imageIt/testFull"),
        phase = Phase.Verify,
        gate = Gate.Always,
      )
      .withAffectedBy(_.docker),
  )

  private val coverage = Coverage.workflow(
    CoverageTrigger.Scheduled(Cron.daily(hour = 3)),
    CoverageTrigger.Dispatch,
    CoverageTrigger.prLabel("coverage"),
  )

  private val split = DeployWorkflow.split(capabilities)

  def doc = page("CI for a busy monorepo")(
    md"""
zipx's defaults are tuned for a library: one `test` job, a publish job on a version tag, and a build cache. A monorepo
with a dozen services, several deploy environments, and a steady stream of merges needs four more things, and each is
one setting. This page puts them together: what goes wrong without them, the configuration, what every PR and merge
then runs, and what was measured.

Every number below comes from [zipx-ci-lab](https://github.com/early-effect/zipx-ci-lab), a public repository that
reproduces a production monorepo's CI on zipx 0.11.0 and re-measures each fix against a snapshot of the change.
""",
    section("What goes wrong at scale")(
      md"""
| Symptom | Cause | Measured on 0.11.0 | Fix | Page |
| --- | --- | --- | --- | --- |
| Every PR recompiles everything, and the cache quota overflows | Every job saved its own build-cache entry, evicting `main`'s | A one-module PR saved 9 entries of 296 to 389 MB; `test` restored the `docker` job's entry and recompiled 13 module configurations | One save per run, by the builtin `test` | **Caching** |
| Every PR waits on coverage, and images can pick up instrumented classes | Coverage was the required `test`, saving its instrumented build under the shared prefix | Every PR ran the instrumented suite | `zipx-coverage.yml`, off the required path | **Verify** |
| Merges queue behind a production approval nobody is answering | Images and deploys ran in `ci.yml` on every merge | Merge B waited behind merge A's approval, then merge C cancelled it | `zipx-deploy.yml`, run by hand from a plan | **Docker and deploy** |
| Every PR runs every suite and every integration job | `test` ran the root task; Once jobs had no affected gate | A one-library PR ran all 6 suites, the image integration test, and the Scala 2.13 job | `zipxTestAffected`, and `withAffectedBy` on Once jobs | **Affected** |
"""
    ),
    section("The configuration")(
      md"""
```scala
// build.sbt, on the root project

// 1. Cache: nothing to set. The builtin test owns the LocalDir build snapshot and saves once per run;
//    every other job restores it.

// 2. Coverage in its own workflow: nightly, on demand, and on a PR labeled coverage.
zipxCoverageWorkflow := Some(
  Coverage.workflow(
    CoverageTrigger.Scheduled(Cron.daily(hour = 3)),
    CoverageTrigger.Dispatch,
    CoverageTrigger.prLabel("coverage"),
  )
)

// 3. Images and deploys run from a dispatched zipx-deploy.yml, never on a merge.
zipxDeployTrigger := DeployTrigger.Manual()

zipxCapabilities ++= Seq(
  Capability.dockerGraph.copy(gate = Gate.Always),
  zipxTasks.deployGraph(
    participates = _.docker,
    command = promote,
    targets = _ => List(
      Target(TargetName("stg"), environment = Some("staging"), group = Some(TargetGroup("pre-prod"))),
      Target(TargetName("prod"), environment = Some("production")),
    ),
    gate = Gate.Always,
  ),
  // 4. The builtin test is already affected-scoped on PRs. A Once job whose inputs the classpath graph
  //    cannot see names the modules it tests.
  zipxTasks
    .once(name = CapabilityName("image-it"), command = imageIt / testFull, phase = Phase.Verify, gate = Gate.Always)
    .withAffectedBy(_.docker),
)

// On each image module: the references the deploy workflow checks before pushing.
zipxImageRefs := (Docker / dockerAliases).value.map(_.toString)
```

`zipxWorkflowGenerate` then writes three workflows. Rendered live from the planner for a three-module build (`schema`,
`api`, and a `service` image):
""",
      exampleValue {
        val ci     = Planner.plan(libGraph, split.ci, config)
        val deploy = DeployWorkflow.plan(libGraph, split.deploy, config, DeployWorkflow.ImagesEnvironment)
        val cov    = CoverageWorkflow.plan(coverage, config)
        s"""|ci.yml:            ${ci.jobs.keys.mkString(", ")}
            |zipx-coverage.yml: ${cov.jobs.keys.mkString(", ")}
            |zipx-deploy.yml:   ${deploy.jobs.keys.mkString(", ")}""".stripMargin
      }.assert(summary =>
        val lines = summary.linesIterator.toList
        assertTrue(
          lines(0).contains("test"),
          lines(0).contains("image-it"),
          lines(0).contains("affected"),
          !lines(0).contains("docker"),
          !lines(0).contains("deploy"),
          lines(1).endsWith("coverage"),
          lines(2).endsWith("resolve, docker-service, deploy-service-prod, deploy-service-stg"),
        )
      ),
      md"""
`ci.yml` keeps what gates a merge: `test`, `image-it`, and the `affected` job `image-it` reads. The image and its
deploys moved to `zipx-deploy.yml`, behind a `resolve` job that plans each dispatch. Coverage is one job in
`zipx-coverage.yml`. Generate refuses the shapes that would break this: coverage that replaces `test` or saves the
cache, and a deploy the dispatched workflow could not run (see **Validation**).
""",
    ),
    section("What a PR, a merge, and a deploy run")(
      md"""
```mermaid
flowchart TD
  PR([PR opened or pushed]) --> Test[test · zipxTestAffected · the diff's modules only]
  PR --> Gated[image-it · only if an image module changed]
  PR -.->|labeled coverage| Cov[zipx-coverage.yml · restores the cache, never saves]
  Merge([merge to main]) --> Rehydrate[cache-rehydrate · saves main's snapshot]
  Merge --> Nothing([no image, no deploy])
  Dispatch([Actions → Run workflow · target stg]) --> Resolve[resolve · changed since each Environment's last deploy]
  Resolve --> Image[docker · push only a missing tag]
  Image --> Deploy[deploy · binds the Environment · records module and commit]
  class PR,Merge,Dispatch warn
  class Test,Gated,Cov,Rehydrate,Nothing,Resolve,Image,Deploy happy
```

**On a PR**, `test` diffs against the PR base and runs the test tasks of the changed modules and their dependents, as
one parallel `all` command. A Once job with `withAffectedBy` runs only when one of its modules is in that set. A diff
that cannot run tests everything. The PR's first push restores `main`'s snapshot; later pushes restore the PR's own.

**On a merge**, `cache-rehydrate` compiles `main` and saves the snapshot the next PRs restore. Nothing ships.

**On a dispatch**, `resolve` reads each Environment's deployments from GitHub and diffs each module's last deployed
commit against the one being deployed. The image jobs push only tags a registry lacks. Each deploy job binds its
Environment, so approvals still apply, and records the module and commit it shipped for the next `changed` deploy.
Deploys to one target queue behind each other and are never cancelled.
"""
    ),
    section("Measured")(
      md"""
Each row is one lab scenario on 0.11.0, then on a snapshot of the change that fixed it. Run ids are in the lab
repository's Actions tab and its README.

| Scenario | 0.11.0 | After the fix |
| --- | --- | --- |
| Four one-module PRs | 9 cache saves per PR; `main`'s entries evicted; 13 configurations recompiled | 1 save per run across 12 runs; `test` restored `main`'s entry and compiled the one module; `main`'s entry survived |
| Coverage on a labeled PR, then an image | Coverage was the required `test` and saved under the shared prefix | 0 saves from coverage; no instrumented class reached an image job; the image compiled nothing |
| Three merges with a `prd` approval pending | The second merge waited behind the first, then the third cancelled it | Merges ran no image or deploy job; the dispatched deploy waited alone |
| Two `changed` deploys to `stg`, one `svcB` merge between | No such workflow | The second plan was `svcB`'s image and no worker; an existing tag was not rebuilt |
| A `lib`, a `svcA`, and a `legacy` change | Each ran all 6 suites, the image test, and the 2.13 job | 5, 1, and 0 suites; the image test ran for the first two only; the 2.13 job ran for the third only |
"""
    ),
    section("Adopting it")(
      md"""
In order, one PR each, so each step's effect on required checks is visible on its own.

1. **Upgrade.** `test` becomes affected-scoped on PRs with no setting. Its check name stays `test`. A build that
   replaces `test` by name keeps its own command.
2. **Coverage.** If coverage replaced `test` (`Coverage.once(name = Capability.TestName)`), generate now refuses it.
   Set `zipxCoverageWorkflow`, drop the capability, and remove `coverage` from the required checks if it was there.
3. **Deploys.** Set `zipxDeployTrigger := DeployTrigger.Manual()` and regenerate. Generate names each fix it needs:
   drop push-only conditions such as `onMainPush`, give every deploy target an Environment, and keep image and
   deploy capabilities Graph-scoped. Set `zipxImageRefs` on image modules, tag images from `ZIPX_DEPLOY_SHA` before
   `GITHUB_SHA`, and give each tier a `Target.group` if you deploy several targets at once.
4. **Integration jobs.** Add `withAffectedBy` to each Once job whose inputs the classpath graph cannot see.
5. **Check the output.** `zipxWorkflowCheck` in CI keeps the committed workflows honest; `actionlint` in the repo root
   catches anything GitHub would reject before a push does.
"""
    ),
    section("Limits")(
      md"""
- Any edit to `build.sbt` affects every module, because it is a build file. A catalog bump is the exception: it affects
  the modules that declare the library (see **Affected**, *Catalog bumps*).
- `zipxTestAffected` scopes the builtin `test` only. A user Aggregate capability with per-module commands still runs
  all of them.
- Registry-style steps that need an image (LaunchPad, a manifest registry) run whenever their module's image is in the
  deploy plan, including when its tag already existed, so they must be idempotent.
"""
    ),
  )
end BusyMonorepo
