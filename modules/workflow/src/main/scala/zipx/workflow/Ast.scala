package zipx.workflow

import zio.blocks.schema.*
import scala.collection.immutable.ListMap

/** A GitHub Actions workflow, modeled as an algebraic data type.
  *
  * The sub-types (`Job`, `Step`, `Strategy`, `Container`, `Concurrency`) `derive Schema` and are rendered by
  * zio-blocks' YAML deriver, which kebab-cases every field name — exactly what GitHub Actions wants for job/step keys
  * (`runs-on`, `timeout-minutes`, `fail-fast`, ...). See [[Render]] for the one place derivation cannot reach: the
  * `on:` triggers block, whose keys (`pull_request`, `workflow_dispatch`, ...) use underscores that kebab-casing would
  * mangle.
  *
  * Map fields use plain `Map` (zio-blocks derives `Schema[Map]` but not `Schema[ListMap]`); populate them with
  * `ListMap` to keep insertion order deterministic — the derived codec preserves it.
  */
final case class Workflow(
    name: String,
    on: Triggers,
    jobs: ListMap[String, Job],
    concurrency: Option[Concurrency] = None,
    permissions: Map[String, String] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
)

/** The `on:` block. Rendered by a hand-written codec (see [[Render.triggersYaml]]) because GitHub's event keys use
  * underscores that the kebab-casing deriver cannot produce.
  */
final case class Triggers(
    push: Option[BranchFilter] = None,
    pullRequest: Option[BranchFilter] = None,
    workflowDispatch: Boolean = false,
    workflowCall: Boolean = false,
)

/** Branch/tag/path filters for a `push` or `pull_request` trigger. Empty lists are pruned at render time. */
final case class BranchFilter(
    branches: List[String] = Nil,
    tags: List[String] = Nil,
    paths: List[String] = Nil,
)

final case class Job(
    name: Option[String] = None,
    // One or more runner labels. A single label renders as a scalar (`runs-on: ubuntu-latest`); multiple render as a
    // YAML sequence (`runs-on: [self-hosted, linux]` in block form) — see Render.jobsYaml.
    // Empty when this job is a reusable-workflow call ([[uses]]); prune drops the empty sequence.
    runsOn: List[String] = List("ubuntu-latest"),
    needs: List[String] = Nil,
    `if`: Option[String] = None,
    environment: Option[String] = None,
    permissions: Map[String, String] = ListMap.empty,
    strategy: Option[Strategy] = None,
    container: Option[String] = None,
    services: Map[String, JobService] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
    outputs: Map[String, String] = ListMap.empty,
    steps: List[Step] = Nil,
    /** Reusable workflow call (`jobs.<id>.uses`). When set, [[steps]] / [[runsOn]] should be empty. */
    uses: Option[String] = None,
    /** Inputs for a reusable workflow call (`jobs.<id>.with`). */
    `with`: Map[String, String] = ListMap.empty,
) derives Schema

/** A GitHub Actions service container (a sidecar running for the duration of a job) — e.g. a `bazel-remote` gRPC cache.
  */
final case class JobService(
    image: String,
    ports: List[String] = Nil,
    options: Option[String] = None,
) derives Schema

final case class Strategy(
    failFast: Boolean = false,
    matrix: Map[String, List[String]] = ListMap.empty,
) derives Schema

/** A single step. A GitHub Actions step is a flat mapping with optional keys — modeling it as one case class with
  * all-optional fields (rather than a `uses`-vs-`run` sum type) avoids variant discriminator wrappers and matches the
  * on-disk shape exactly. `None`/empty fields are omitted at render time.
  */
final case class Step(
    name: Option[String] = None,
    id: Option[String] = None,
    `if`: Option[String] = None,
    uses: Option[String] = None,
    run: Option[String] = None,
    `with`: Map[String, String] = ListMap.empty,
    env: Map[String, String] = ListMap.empty,
    workingDirectory: Option[String] = None,
) derives Schema

final case class Concurrency(
    group: String,
    cancelInProgress: Boolean = false,
) derives Schema
