package zipx.core

/** A representative cross-published monorepo graph used across the core tests.
  *
  * The shape deliberately exercises the interesting cases a real monorepo hits:
  *   - `schema` (∅, publishes, cross 2.13+3) — a root library
  *   - `api` (→ schema, publishes, cross) — depends on the root library
  *   - `legacyClient` (→ schema, publishes, **2.13-only**) — the cross-version edge case
  *   - `clientA`, `clientB` (→ api, publish, cross) — two downstream clients (a diamond over `api`)
  *   - `serviceA`..`serviceD` (→ core/api, non-publishing) — apps, not libraries; docker candidates
  *   - `workerA`, `workerB` (→ core, non-publishing) — background apps
  *   - `core` (∅, non-publishing) — a shared internal dependency
  *
  * Publish partial order (what dependency-ordered publishing must produce):
  * L0 `schema`; L1 `api`, `legacyClient`; L2 `clientA`, `clientB`.
  */
object Fixtures:

  val scala2 = "2.13.16"
  val scala3 = "3.8.4"
  val cross  = List(scala2, scala3)

  val sampleGraph: ModuleGraph = ModuleGraph(
    List(
      ModuleNode("core", crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("schema", publishes = true, crossScalaVersions = cross),
      ModuleNode("api", dependsOn = List("schema"), publishes = true, crossScalaVersions = cross),
      ModuleNode("legacyClient", dependsOn = List("schema"), publishes = true, crossScalaVersions = List(scala2)),
      ModuleNode("clientA", dependsOn = List("api"), publishes = true, crossScalaVersions = cross),
      ModuleNode("clientB", dependsOn = List("api"), publishes = true, crossScalaVersions = cross),
      ModuleNode("serviceA", dependsOn = List("core", "api"), crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("serviceB", dependsOn = List("core", "api"), crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("serviceC", dependsOn = List("api"), crossScalaVersions = List(scala3), testTask = "testFull"),
      ModuleNode("serviceD", dependsOn = List("core", "api"), crossScalaVersions = List(scala3)),
      ModuleNode("workerA", dependsOn = List("core"), crossScalaVersions = List(scala3)),
      ModuleNode("workerB", dependsOn = List("core"), crossScalaVersions = List(scala3)),
    ),
  )
end Fixtures
