package zipx.core

import zio.test.*

object ReleasePlanSpec extends ZIOSpecDefault:

  private def node(id: String, deps: List[String] = Nil, root: String = ""): ModuleNode =
    ModuleNode(
      id = ModuleId.unsafeMake(id),
      dependsOn = deps,
      publishes = true,
      matrixRootOpt = Option.when(root.nonEmpty)(ModuleId.unsafeMake(root)),
    )

  private val graph = GraphFixture(
    List(
      node("models"),
      node("modelsJS", root = "models"),
      node("coreLib", deps = List("models")),
      node("client", deps = List("coreLib")),
    )
  )

  private val libs   = ShipGroup("libs", "1.4.2")("models", "coreLib")
  private val client = Ship("client", "0.3.0")

  private val catalog = ShipIndex.from(List(libs, client))
  private val lone    = ShipIndex.from(List(Ship("core", "1.0.0")))

  private def statuses(released: Set[PublishedRow]): PublishedRow => Either[ReleaseError, RowStatus] =
    row => Right(if released.contains(row) then RowStatus.Released else RowStatus.Unreleased)

  private def plan(request: String, released: Set[PublishedRow] = Set.empty) =
    ReleaseRequest.fromRef(request).flatMap(ReleasePlan.plan(_, catalog, graph, statuses(released)))

  private def rowsOf(p: Either[ReleaseError, ReleasePlan]): Option[List[String]] =
    p.toOption.map(_.entries.map(_.row.identity))

  private val gav = Gav("com.example", "models_3", "1.4.2")

  def spec = suite("ReleasePlan")(
    suite("the ref that started the run")(
      test("a tag releases that tag; a branch releases every unreleased row; anything else is refused") {
        assertTrue(
          ReleaseRequest.fromRef("refs/tags/client/v0.3.0") == Right(ReleaseRequest.Tagged("client/v0.3.0")),
          ReleaseRequest.fromRef("refs/heads/main") == Right(ReleaseRequest.AllUnreleased),
          ReleaseRequest.fromRef("refs/pull/7/merge") == Left(ReleaseError.UnknownRef("refs/pull/7/merge")),
        )
      }
    ),
    suite("tags")(
      test("a single-row catalog tags v<n>; several rows tag <identity>/v<n>, leaving v* to the image") {
        assertTrue(
          TagScheme.of(lone).tag(Ship("core", "1.0.0")) == "v1.0.0",
          TagScheme.of(catalog).tag(client) == "client/v0.3.0",
          TagScheme.of(catalog).tag(libs) == "libs/v1.4.2",
          TagScheme.of(lone).pattern == "v*",
          TagScheme.of(catalog).pattern == "*/v*",
        )
      },
      test("a tag for the right row but the wrong number names the number to tag") {
        val refused = plan("refs/tags/client/v0.3.1").swap.toOption
        assertTrue(
          refused.contains(ReleaseError.TagMismatch("client/v0.3.1", client, "client/v0.3.0")),
          refused.exists(_.message.contains("""tag client/v0.3.1 does not match Ship("client") 0.3.0""")),
        )
      },
      test("in a single-row catalog a v<n> tag with another number is a mismatch") {
        val refused = ReleaseRequest
          .fromRef("refs/tags/v1.0.1")
          .flatMap(ReleasePlan.plan(_, lone, GraphFixture(List(node("core"))), statuses(Set.empty)))
        assertTrue(refused.swap.exists(_.message.contains("tag v1.0.1 does not match Ship(\"core\") 1.0.0")))
      },
      test("in a multi-row catalog a bare v<n> tag names no row, and the error lists the tags that would") {
        val refused = plan("refs/tags/v0.3.0").swap.toOption
        assertTrue(
          refused.contains(ReleaseError.UnknownTag("v0.3.0", List("libs/v1.4.2", "client/v0.3.0"))),
          refused.exists(_.message.contains("libs/v1.4.2, client/v0.3.0")),
        )
      },
    ),
    suite("what a run releases")(
      test("a tagged row releases with its unreleased upstream rows, upstream first") {
        assertTrue(rowsOf(plan("refs/tags/client/v0.3.0")).contains(List("libs", "client")))
      },
      test("a released upstream row stays out") {
        assertTrue(rowsOf(plan("refs/tags/client/v0.3.0", released = Set(libs))).contains(List("client")))
      },
      test("a downstream row never rides along") {
        assertTrue(rowsOf(plan("refs/tags/libs/v1.4.2")).contains(List("libs")))
      },
      test("all, a branch, and a cleared field are not the same request") {
        assertTrue(
          ReleaseRequest.fromRef("all") == Right(ReleaseRequest.AllUnreleased),
          ReleaseRequest.fromRef("  all  ") == Right(ReleaseRequest.AllUnreleased),
          ReleaseRequest.fromRef("client, libs") == Right(ReleaseRequest.Ships(::("client", List("libs")))),
          ReleaseRequest.fromRef("client,client") == Right(ReleaseRequest.Ships(::("client", Nil))),
          ReleaseRequest.fromRef(",,") == Left(ReleaseError.UnknownRef(",,")),
          ReleaseRequest.fromRef("   ") == Left(ReleaseError.UnknownRef("   ")),
        )
      },
      test("a named set releases those ships plus unreleased upstreams, and leaves an unnamed ship out") {
        val tools     = Ship("tools", "0.1.0")
        val withTools = ShipIndex.from(List(libs, client, tools))
        val wider     = GraphFixture(
          List(
            node("models"),
            node("modelsJS", root = "models"),
            node("coreLib", deps = List("models")),
            node("client", deps = List("coreLib")),
            node("tools"),
          )
        )
        def planned(ref: String, releasedRows: Set[PublishedRow] = Set.empty) =
          ReleaseRequest
            .fromRef(ref)
            .flatMap(ReleasePlan.plan(_, withTools, wider, statuses(releasedRows)))
        def ids(ref: String, releasedRows: Set[PublishedRow] = Set.empty) =
          planned(ref, releasedRows).toOption.map(_.entries.map(_.row.identity).toSet)
        val unknown = planned("nope")
        assertTrue(
          ids("client").contains(Set("libs", "client")),
          ids("client,libs").contains(Set("libs", "client")),
          ids("tools").contains(Set("tools")),
          ids("all").contains(Set("libs", "client", "tools")),
          ids("client", Set(libs)).contains(Set("client")),
          planned("client", Set(client)) == Left(ReleaseError.AlreadyReleased(client)),
          unknown.swap.exists {
            case ReleaseError.UnknownShip("nope", ships) => ships.toSet == Set("libs", "client", "tools")
            case _                                       => false
          },
        )
      },
      test("a dispatch releases every unreleased row and stops when there are none") {
        assertTrue(
          rowsOf(plan("refs/heads/main")).contains(List("libs", "client")),
          rowsOf(plan("refs/heads/main", released = Set(libs))).contains(List("client")),
          plan("refs/heads/main", released = Set(libs, client)) == Left(ReleaseError.NothingToRelease),
        )
      },
      test("a tag for a released row is refused") {
        assertTrue(
          plan("refs/tags/client/v0.3.0", released = Set(client)) == Left(ReleaseError.AlreadyReleased(client))
        )
      },
      test("a partly released row and an unreachable registry both fail closed") {
        val partial = ReleaseRequest
          .fromRef("refs/heads/main")
          .flatMap(ReleasePlan.plan(_, catalog, graph, _ => Right(RowStatus.Partial(::(gav, Nil)))))
        val unreachable = ReleaseRequest
          .fromRef("refs/heads/main")
          .flatMap(ReleasePlan.plan(_, catalog, graph, row => Left(ReleaseError.RegistryUnreachable(row, "timeout"))))
        assertTrue(
          partial == Left(ReleaseError.PartiallyReleased(libs, ::(gav, Nil))),
          unreachable == Left(ReleaseError.RegistryUnreachable(libs, "timeout")),
        )
      },
      test("an empty catalog has nothing to release") {
        val empty = ReleasePlan.plan(ReleaseRequest.AllUnreleased, ShipIndex.empty, graph, statuses(Set.empty))
        assertTrue(empty == Left(ReleaseError.NoRows))
      },
      test("for any released set, a tagged unreleased row's plan is closed under unreleased upstream rows") {
        check(Gen.setOf(Gen.fromIterable(List[PublishedRow](libs, client)))) { released =>
          val result = plan("refs/tags/client/v0.3.0", released)
          assertTrue(
            if released.contains(client) then result == Left(ReleaseError.AlreadyReleased(client))
            else
              rowsOf(result).exists { rows =>
                rows.lastOption.contains("client") && rows.contains("libs") == !released.contains(libs)
              }
          )
        }
      },
    ),
    suite("projects")(
      test("a plan publishes each row's projects, platform rows included, in build order") {
        assertTrue(
          plan("refs/tags/client/v0.3.0").toOption
            .map(_.projects(graph, catalog).map(id => id: String))
            .contains(
              List("models", "modelsJS", "coreLib", "client")
            )
        )
      }
    ),
    suite("RowStatus")(
      test("every binary on the registry is released, none is unreleased, some is partial") {
        val other = gav.copy(artifact = "models_sjs1_3")
        assertTrue(
          RowStatus.of(List(gav -> RegistryStatus.Published, other -> RegistryStatus.Published)) == RowStatus.Released,
          RowStatus.of(List(gav -> RegistryStatus.Missing, other -> RegistryStatus.Missing)) == RowStatus.Unreleased,
          RowStatus.of(List(gav -> RegistryStatus.Published, other -> RegistryStatus.Missing)) ==
            RowStatus.Partial(::(other, Nil)),
          RowStatus.of(Nil) == RowStatus.Unreleased,
        )
      }
    ),
  )
end ReleasePlanSpec
