package zipx.core

import zio.test.*

object ModverSpec extends ZIOSpecDefault:

  private def mid(s: String): ModuleId        = ModuleId.unsafeMake(s)
  private def depVer(s: String): DepVersion   = DepVersion.unsafeMake(s)
  private def gname(s: String): ShipGroupName = ShipGroupName.unsafeMake(s)

  private def node(
      id: String,
      publishes: Boolean = true,
      deps: List[String] = Nil,
      base: String = "",
      sources: List[String] = Nil,
      root: String = "",
  ): ModuleNode =
    val matrixRoot = if root.isEmpty then id else root
    ModuleNode(
      id = mid(id),
      dependsOn = deps,
      publishes = publishes,
      baseDir = if base.isEmpty then id else base,
      sourcePaths = sources,
      matrixRootOpt = Option.when(matrixRoot != id)(mid(matrixRoot)),
    )
  end node

  private val graph = GraphFixture(
    List(
      node("models"),
      node("coreLib", deps = List("models"), base = "core-lib"),
      node("client", deps = List("coreLib")),
      node("service", publishes = false, deps = List("coreLib")),
    )
  )

  private val libs     = ShipGroup("libs", "1.4.2")("models", "coreLib")
  private val client   = Ship("client", "0.3.0")
  private val covering = List[PublishedRow](libs, client)

  private val index = Modver.membership(graph, covering) match
    case Right(i)  => i
    case Left(err) => throw AssertionError(s"covering fixture is invalid: $err")

  private val matrix = GraphFixture(
    List(
      node(
        "core",
        base = ".sbt/matrix/core",
        sources = List("core/src/main/scala", "core/src/main/scalajvm"),
      ),
      node(
        "coreJS",
        base = ".sbt/matrix/coreJS",
        sources = List("core/src/main/scala", "core/src/main/scalajs"),
        root = "core",
      ),
      node("cli", deps = List("coreJS")),
    )
  )

  private val gVersion: Gen[Any, String] =
    for
      major <- Gen.int(0, 9)
      minor <- Gen.int(0, 9)
      patch <- Gen.int(0, 9)
    yield s"$major.$minor.$patch"

  private def gChunks(ids: List[String]): Gen[Any, List[List[String]]] =
    Gen.suspend {
      if ids.isEmpty then Gen.const(Nil)
      else
        for
          take <- Gen.int(1, math.min(3, ids.size))
          rest <- gChunks(ids.drop(take))
        yield ids.take(take) :: rest
    }

  private val gCovered: Gen[Any, (ModuleGraph, List[PublishedRow])] =
    for
      n           <- Gen.int(2, 6)
      unpublished <- Gen.boolean
      ver         <- gVersion
      ids = (0 until n).map(i => s"mod$i").toList
      chunks <- gChunks(ids)
    yield
      val nodes =
        ids.map(id => node(id)) ++
          Option.when(unpublished)(node("skip", publishes = false)).toList
      val rows = chunks.zipWithIndex.map {
        case (List(one), _) => Ship(mid(one), depVer(ver))
        case (members, i)   =>
          ShipGroup(gname(s"g$i"), depVer(ver), members.map(mid))
      }
      (GraphFixture(nodes), rows)

  def spec = suite("Modver")(
    suite("literals")(
      test("Ship and ShipGroup catalog literals typecheck") {
        val ship: Ship   = Ship("core", "1.4.2")
        val group        = ShipGroup("foo", "1.4.2")("foo-api", "foo-cli", "foo-impl")
        val emptyMembers = ShipGroup("empty", "1.0.0")()
        assertTrue(
          (ship.id: String) == "core",
          (ship.version: String) == "1.4.2",
          (group.name: String) == "foo",
          group.members.map(m => m: String) == List("foo-api", "foo-cli", "foo-impl"),
          emptyMembers.members.isEmpty,
        )
      },
      test("a Ship literal is rejected while the catalog compiles, not when it runs") {
        for
          good    <- typeCheck("""zipx.core.Ship("core", "1.4.2")""")
          badId   <- typeCheck("""zipx.core.Ship("café", "1.4.2")""")
          badName <- typeCheck("""zipx.core.ShipGroup("", "1.4.2")("core")""")
        yield assertTrue(good.isRight, badId.isLeft, badName.isLeft)
      },
    ),
    suite("bumpVersion")(
      test("patch, minor, and major increment and never write -ci") {
        assertTrue(
          Modver.bumpVersion("1.4.2", BumpKind.Patch) == Right("1.4.3"),
          Modver.bumpVersion("1.4.2", BumpKind.Minor) == Right("1.5.0"),
          Modver.bumpVersion("1.4.2", BumpKind.Major) == Right("2.0.0"),
          Modver.bumpVersion("1.4.2-ci", BumpKind.Patch).isLeft,
        )
      },
      test("rowForProject prefers the exact id then a JS suffix of a Ship root") {
        val rows = List[PublishedRow](Ship("core", "1.4.2"), Ship("cli", "0.3.0"))
        assertTrue(
          Modver.rowForProject("core", rows).exists(_.identity == "core"),
          Modver.rowForProject("coreJS", rows).exists(_.identity == "core"),
          Modver.rowForProject("cli", rows).exists(_.identity == "cli"),
          Modver.rowForProject("service", rows).isEmpty,
        )
      },
    ),
    suite("membership")(
      test("a covering catalog is Right and every publishing root is in exactly one row") {
        check(gCovered) { (g, rows) =>
          Modver.membership(g, rows) match
            case Left(err)    => assertTrue(err == "")
            case Right(built) =>
              val roots = Modver.publishingRoots(g)
              assertTrue(
                roots.forall(built.byRoot.contains),
                roots.forall(r => built.byRoot(r).memberRoots.contains(r)),
                built.byRoot.keySet == roots,
              )
        }
      },
      test("an unpublished module is not a membership hole") {
        assertTrue(Modver.membership(graph, covering).isRight, !index.byRoot.contains(ModuleId("service")))
      },
      test("a missing publishing root names the Ship constructor") {
        val err = Modver.membership(graph, List(client))
        assertTrue(
          err.isLeft,
          err.swap.exists(_.contains("published module 'coreLib' is not in a Ship or ShipGroup")),
          err.swap.exists(_.contains("""Add Ship("coreLib", "…")""")),
        )
      },
      test("the same root in two rows names both constructors") {
        val extra = Ship("models", "9.0.0")
        val err   = Modver.membership(graph, covering :+ extra)
        assertTrue(
          err.isLeft,
          err.swap.exists(_.contains("published module 'models' is in")),
          err.swap.exists(_.contains("""Ship("models")""")),
          err.swap.exists(_.contains("""ShipGroup("libs")""")),
          err.swap.exists(_.contains("exactly one row")),
        )
      },
      test("a duplicate Ship id is refused") {
        val err = Modver.membership(graph, covering :+ Ship("client", "0.4.0"))
        assertTrue(err.swap.exists(_ == """Ship("client") appears twice."""))
      },
      test("a duplicate ShipGroup name is refused") {
        val other = ShipGroup("libs", "9.0.0")("client")
        val err   = Modver.membership(graph, List(libs, other, client))
        assertTrue(err.swap.exists(_ == """ShipGroup name 'libs' appears twice."""))
      },
      test("an empty ShipGroup is refused") {
        val err = Modver.membership(graph, covering :+ ShipGroup("foo", "1.0.0")())
        assertTrue(err.swap.exists(_ == """ShipGroup("foo") has no members."""))
      },
      test("an unknown group member is refused") {
        val err = Modver.membership(graph, covering :+ ShipGroup("x", "1.0.0")("nope"))
        assertTrue(err.swap.exists(_ == """ShipGroup("x") member 'nope' is not an sbt project id."""))
      },
      test("an unpublished group member is refused") {
        val err = Modver.membership(graph, covering :+ ShipGroup("apps", "1.0.0")("service"))
        assertTrue(
          err.swap.exists(
            _ == """ShipGroup("apps") member 'service' does not publish. Drop it or set publish / skip := false."""
          )
        )
      },
      test("a -ci catalog version is refused") {
        val err = Modver.membership(graph, List(ShipGroup("libs", "1.4.2-ci")("models", "coreLib"), client))
        assertTrue(
          err.swap.exists(
            _ == """ShipGroup("libs") version '1.4.2-ci' must be the release number, not a -ci suffix."""
          )
        )
      },
      test("a Ship of a platform row names the matrix root") {
        val err = Modver.membership(matrix, List(Ship("coreJS", "1.4.2"), Ship("cli", "0.3.0")))
        assertTrue(
          err.swap.exists(
            _ == """Ship("coreJS") names a platform row; use Ship("core", …) for the matrix root."""
          )
        )
      },
      test("one Ship of the matrix root covers every platform row") {
        val rows = List[PublishedRow](Ship("core", "1.4.2"), Ship("cli", "0.3.0"))
        Modver.membership(matrix, rows) match
          case Left(err)    => assertTrue(err == "")
          case Right(built) =>
            assertTrue(
              built.byRoot.get(ModuleId("core")).contains(Ship("core", "1.4.2")),
              built.rowFor(ModuleId("core")).isDefined,
            )
      },
    ),
    suite("bump set")(
      test("None files refuse rather than fail open") {
        val err = Modver.liftedBumpSet(graph, index, None)
        assertTrue(err == Left("could not diff changed files for modver; refusing to guess the bump set"))
      },
      test("an empty diff is an empty bump set, not all modules") {
        assertTrue(Modver.liftedBumpSet(graph, index, Some(Nil)) == Right(Set.empty))
      },
      test("a build-file change does not explode the bump set") {
        val files = List("build.sbt", "project/ZipxVersions.scala", "project/plugins.sbt")
        assertTrue(
          Modver.dirtyRoots(graph, files).isEmpty,
          Modver.liftedBumpSet(graph, index, Some(files)) == Right(Set.empty),
        )
      },
      test("any dirty group member lifts to the group, not each member") {
        val lifted = Modver.liftedBumpSet(graph, index, Some(List("core-lib/src/main/scala/Core.scala")))
        assertTrue(lifted == Right(Set(ShipRef.Group(ShipGroupName("libs")))))
      },
      test("a leaf change does not reverse-dep into the bump set") {
        val lifted = Modver.liftedBumpSet(graph, index, Some(List("client/src/main/scala/Client.scala")))
        assertTrue(
          lifted == Right(Set(ShipRef.One(ModuleId("client")))),
          !lifted.exists(_.contains(ShipRef.Group(ShipGroupName("libs")))),
        )
      },
      test("shared matrix sources dirty the root once") {
        val covered = List[PublishedRow](Ship("core", "1.4.2"), Ship("cli", "0.3.0"))
        val built   = Modver.membership(matrix, covered).toOption.get
        val lifted  = Modver.liftedBumpSet(matrix, built, Some(List("core/src/main/scala/Foo.scala")))
        assertTrue(lifted == Right(Set(ShipRef.One(ModuleId("core")))))
      },
      test("min-bump order is None then Patch then Minor then Major") {
        import Modver.minBumpOrd
        assertTrue(
          minBumpOrd.lt(BumpKind.None, BumpKind.Patch),
          minBumpOrd.lt(BumpKind.Patch, BumpKind.Minor),
          minBumpOrd.lt(BumpKind.Minor, BumpKind.Major),
          minBumpOrd.max(BumpKind.Patch, BumpKind.Major) == BumpKind.Major,
        )
      },
    ),
    suite("propagate")(
      test("Never is the lifted set even when reverse-deps exist") {
        check(gCovered) { (g, rows) =>
          val built = Modver.membership(g, rows).toOption.get
          val bumps = BumpSet(built.byIdentity.keys.map(_ -> BumpKind.Patch).toMap)
          assertTrue(
            Modver.expand(bumps, g, built, ModverPropagate.Never).asMap == bumps.asMap,
            ModverPropagate.Never.expand(bumps, g, built).asMap == bumps.asMap,
          )
        }
      },
      test("PatchPublished patches published reverse-deps and skips unpublished ones") {
        val bumps  = BumpSet(Map(ShipRef.Group(ShipGroupName("libs")) -> BumpKind.Minor))
        val out    = Modver.expand(bumps, graph, index, ModverPropagate.PatchPublished).asMap
        val client = ShipRef.One(ModuleId("client"))
        assertTrue(
          out.get(ShipRef.Group(ShipGroupName("libs"))).contains(BumpKind.Minor),
          out.get(client).contains(BumpKind.Patch),
          !out.keys.exists {
            case ShipRef.One(id) => (id: String) == "service"
            case _               => false
          },
        )
      },
      test("MatchBump floors reverse-deps at the triggering kind, not Patch") {
        val bumps = BumpSet(Map(ShipRef.Group(ShipGroupName("libs")) -> BumpKind.Major))
        val out   = Modver.expand(bumps, graph, index, ModverPropagate.MatchBump).asMap
        assertTrue(out.get(ShipRef.One(ModuleId("client"))).contains(BumpKind.Major))
      },
      test("intra-group dependsOn is not an edge") {
        val bumps = BumpSet(Map(ShipRef.Group(ShipGroupName("libs")) -> BumpKind.Patch))
        val deps  = Modver.contractedDependents(graph, index)
        val libs  = ShipRef.Group(ShipGroupName("libs"))
        assertTrue(
          !deps.getOrElse(libs, Set.empty).contains(libs),
          Modver.expand(bumps, graph, index, ModverPropagate.PatchPublished).asMap.get(libs).contains(BumpKind.Patch),
        )
      },
      test("existing kind wins via minBumpOrd.max") {
        val client    = ShipRef.One(ModuleId("client"))
        val patchSeed = BumpSet(
          Map(
            ShipRef.Group(ShipGroupName("libs")) -> BumpKind.Patch,
            client                               -> BumpKind.Minor,
          )
        )
        val majorSeed = BumpSet(
          Map(
            ShipRef.Group(ShipGroupName("libs")) -> BumpKind.Major,
            client                               -> BumpKind.Patch,
          )
        )
        val patch = Modver.expand(patchSeed, graph, index, ModverPropagate.PatchPublished).asMap
        val keep  = Modver.expand(patchSeed, graph, index, ModverPropagate.MatchBump).asMap
        val raise = Modver.expand(majorSeed, graph, index, ModverPropagate.MatchBump).asMap
        assertTrue(
          patch.get(client).contains(BumpKind.Minor),
          keep.get(client).contains(BumpKind.Minor),
          raise.get(client).contains(BumpKind.Major),
        )
      },
      test("MatchBump walks the contracted reverse-dep chain") {
        val chain = GraphFixture(
          List(
            node("a"),
            node("b", deps = List("a")),
            node("c", deps = List("b")),
          )
        )
        val rows   = List[PublishedRow](Ship("a", "1.0.0"), Ship("b", "1.0.0"), Ship("c", "1.0.0"))
        val built  = Modver.membership(chain, rows).toOption.get
        val seed   = BumpSet(Map(ShipRef.One(ModuleId("a")) -> BumpKind.Major))
        val matchB = Modver.expand(seed, chain, built, ModverPropagate.MatchBump).asMap
        val patch  = Modver.expand(seed, chain, built, ModverPropagate.PatchPublished).asMap
        assertTrue(
          matchB.get(ShipRef.One(ModuleId("b"))).contains(BumpKind.Major),
          matchB.get(ShipRef.One(ModuleId("c"))).contains(BumpKind.Major),
          patch.get(ShipRef.One(ModuleId("b"))).contains(BumpKind.Patch),
          patch.get(ShipRef.One(ModuleId("c"))).contains(BumpKind.Patch),
        )
      },
      test("a platform-row dependsOn contracts to the matrix root") {
        val covered = List[PublishedRow](Ship("core", "1.4.2"), Ship("cli", "0.3.0"))
        val built   = Modver.membership(matrix, covered).toOption.get
        val seed    = BumpSet(Map(ShipRef.One(ModuleId("core")) -> BumpKind.Minor))
        val out     = Modver.expand(seed, matrix, built, ModverPropagate.MatchBump).asMap
        assertTrue(out.get(ShipRef.One(ModuleId("cli"))).contains(BumpKind.Minor))
      },
      test("unpublished intermediates are not contracted edges") {
        val hole = GraphFixture(
          List(
            node("core"),
            node("app", publishes = false, deps = List("core")),
            node("client", deps = List("app")),
          )
        )
        val rows  = List[PublishedRow](Ship("core", "1.0.0"), Ship("client", "1.0.0"))
        val built = Modver.membership(hole, rows).toOption.get
        val seed  = BumpSet(Map(ShipRef.One(ModuleId("core")) -> BumpKind.Major))
        val out   = Modver.expand(seed, hole, built, ModverPropagate.MatchBump).asMap
        assertTrue(
          out.get(ShipRef.One(ModuleId("core"))).contains(BumpKind.Major),
          !out.contains(ShipRef.One(ModuleId("client"))),
        )
      },
      test("Custom is the whole policy and can drop reverse-deps") {
        val seed = BumpSet(Map(ShipRef.Group(ShipGroupName("libs")) -> BumpKind.Major))
        val out  =
          Modver.expand(seed, graph, index, ModverPropagate.custom { (bumps, _, _) => bumps }).asMap
        assertTrue(
          out == seed.asMap,
          !out.contains(ShipRef.One(ModuleId("client"))),
        )
      },
      test("check and report consume the post-propagate set") {
        val kinds = Modver
          .expand(
            BumpSet(Map(ShipRef.Group(ShipGroupName("libs")) -> BumpKind.Minor)),
            graph,
            index,
            ModverPropagate.MatchBump,
          )
          .asMap
        val lifted = Set[ShipRef](ShipRef.Group(ShipGroupName("libs")))
        Modver.report(index, index, lifted, MovedRows.empty, kinds, mimaRan = Set.empty) match
          case Left(err)     => assertTrue(err.isEmpty)
          case Right(report) =>
            val client = report.rows.find(_.identity == "client")
            val libs   = report.rows.find(_.identity == "libs")
            assertTrue(
              client.exists(r => r.kind == BumpKind.Minor && r.status == BumpStatus.Missing),
              libs.exists(r => r.kind == BumpKind.Minor && Modver.checkFails(r.status)),
              client.exists(r => Modver.checkFails(r.status)),
            )
        end match
      },
      test("None floors do not seed reverse-deps") {
        val seed = BumpSet(Map(ShipRef.Group(ShipGroupName("libs")) -> BumpKind.None))
        val out  = Modver.expand(seed, graph, index, ModverPropagate.PatchPublished).asMap
        assertTrue(
          out.get(ShipRef.Group(ShipGroupName("libs"))).contains(BumpKind.None),
          !out.contains(ShipRef.One(ModuleId("client"))),
        )
      },
    ),
    suite("movedRows")(
      test("a missing previous catalog is empty, not Left") {
        val parsed = Modver.previousIndex(Right(None), _ => Left("should not parse"))
        assertTrue(parsed == Right(ShipIndex.empty))
      },
      test("a failed git show is Left") {
        assertTrue(
          Modver.previousIndex(Left("git show failed"), _ => Right(ShipIndex.empty)) == Left("git show failed")
        )
      },
      test("a parse error is Left") {
        val parsed = Modver.previousIndex(Right(Some("not scala")), _ => Left("parse failed"))
        assertTrue(parsed == Left("parse failed"))
      },
      test("first adoption adds every current identity") {
        Modver.movedRows(index, Right(ShipIndex.empty)) match
          case Left(err)    => assertTrue(err == "")
          case Right(moved) =>
            assertTrue(
              moved.added == Set(ShipRef.Group(ShipGroupName("libs")), ShipRef.One(ModuleId("client"))),
              moved.versionChanged.isEmpty,
              moved.newMembers.isEmpty,
            )
      },
      test("a failed previous index is Left") {
        assertTrue(Modver.movedRows(index, Left("no git")).isLeft)
      },
      test("a version-equal catalog is not moved") {
        assertTrue(Modver.movedRows(index, Right(index)) == Right(MovedRows.empty))
      },
      test("a version change is versionChanged, not added") {
        val prev = ShipIndex.from(List(ShipGroup("libs", "1.4.1")("models", "coreLib"), client))
        Modver.movedRows(index, Right(prev)) match
          case Left(err)    => assertTrue(err == "")
          case Right(moved) =>
            assertTrue(
              moved.versionChanged == Set(ShipRef.Group(ShipGroupName("libs"))),
              moved.added.isEmpty,
              moved.newMembers.isEmpty,
            )
      },
      test("a new group member is in newMembers without a version bump") {
        val prev = ShipIndex.from(List(ShipGroup("libs", "1.4.2")("models"), client))
        Modver.movedRows(index, Right(prev)) match
          case Left(err)    => assertTrue(err == "")
          case Right(moved) =>
            assertTrue(
              moved.newMembers == Set(ModuleId("coreLib")),
              moved.versionChanged.isEmpty,
              moved.added.isEmpty,
            )
      },
    ),
    suite("filterUnpublished")(
      test("every member of a version-moved group is in the publish set") {
        val moved = MovedRows(
          versionChanged = Set(ShipRef.Group(ShipGroupName("libs"))),
          added = Set.empty,
          newMembers = Set.empty,
        )
        val gav = (id: ModuleId) => List(Gav("org", s"${id}_3", "1.4.2"))
        Modver.filterUnpublished(moved, index, graph, gav, _ => Right(RegistryStatus.Missing)) match
          case Left(err)  => assertTrue(err == "")
          case Right(pub) =>
            assertTrue(
              pub.keySet == Set(ModuleId("models"), ModuleId("coreLib")),
              !pub.contains(ModuleId("client")),
            )
      },
      test("a new group member publishes; version-unchanged siblings skip when already on the registry") {
        val moved = MovedRows(Set.empty, Set.empty, newMembers = Set(ModuleId("coreLib")))
        val gav   = (id: ModuleId) => List(Gav("org", s"${id}_3", "1.4.2"))
        val reg: Gav => Either[String, RegistryStatus] =
          g =>
            if g.artifact.startsWith("coreLib") then Right(RegistryStatus.Missing)
            else Right(RegistryStatus.Published)
        Modver.filterUnpublished(moved, index, graph, gav, reg) match
          case Left(err)  => assertTrue(err == "")
          case Right(pub) =>
            assertTrue(
              pub.keySet == Set(ModuleId("coreLib")),
              !pub.contains(ModuleId("models")),
            )
      },
      test("a version-unchanged sibling whose GAV is missing still publishes") {
        val moved = MovedRows(Set.empty, Set.empty, newMembers = Set(ModuleId("coreLib")))
        val gav   = (id: ModuleId) => List(Gav("org", s"${id}_3", "1.4.2"))
        Modver.filterUnpublished(moved, index, graph, gav, _ => Right(RegistryStatus.Missing)) match
          case Left(err)  => assertTrue(err == "")
          case Right(pub) => assertTrue(pub.keySet == Set(ModuleId("models"), ModuleId("coreLib")))
      },
      test("mixed binaries keep only the Missing GAVs") {
        val moved =
          MovedRows(versionChanged = Set(ShipRef.One(ModuleId("client"))), added = Set.empty, newMembers = Set.empty)
        val g213                                       = Gav("org", "client_2.13", "0.3.1")
        val g3                                         = Gav("org", "client_3", "0.3.1")
        val gav                                        = (_: ModuleId) => List(g213, g3)
        val reg: Gav => Either[String, RegistryStatus] =
          g => if g.artifact.endsWith("_3") then Right(RegistryStatus.Missing) else Right(RegistryStatus.Published)
        Modver.filterUnpublished(moved, index, graph, gav, reg) match
          case Left(err)  => assertTrue(err == "")
          case Right(pub) => assertTrue(pub.get(ModuleId("client")).contains(List(g3)))
      },
      test("a registry lookup error is Left") {
        val moved =
          MovedRows(versionChanged = Set(ShipRef.One(ModuleId("client"))), added = Set.empty, newMembers = Set.empty)
        val err =
          Modver.filterUnpublished(
            moved,
            index,
            graph,
            _ => List(Gav("org", "client_3", "0.3.1")),
            _ => Left("HTTP 500"),
          )
        assertTrue(err == Left("HTTP 500"))
      },
      test("both platform rows of a moved root are candidates") {
        val rows  = List[PublishedRow](Ship("core", "1.4.2"), Ship("cli", "0.3.0"))
        val built = Modver.membership(matrix, rows).toOption.get
        val moved =
          MovedRows(versionChanged = Set(ShipRef.One(ModuleId("core"))), added = Set.empty, newMembers = Set.empty)
        val gav = (id: ModuleId) => List(Gav("org", s"${id}_3", "1.4.2"))
        Modver.filterUnpublished(moved, built, matrix, gav, _ => Right(RegistryStatus.Missing)) match
          case Left(err)  => assertTrue(err == "")
          case Right(pub) => assertTrue(pub.keySet == Set(ModuleId("core"), ModuleId("coreJS")))
      },
    ),
    suite("min-bump")(
      test("early-semver 0.y binary break is minor; 1.y is major") {
        assertTrue(
          Modver.minBumpKind("0.4.2", "early-semver", MemberProbe.BinaryBreak) == BumpKind.Minor,
          Modver.minBumpKind("1.4.2", "early-semver", MemberProbe.BinaryBreak) == BumpKind.Major,
          Modver.minBumpKind("1.4.2", "pvp", MemberProbe.BinaryBreak) == BumpKind.Major,
          Modver.minBumpKind("1.4.2", "semver-spec", MemberProbe.BinaryBreak) == BumpKind.Major,
          Modver.minBumpKind("1.4.2", "early-semver", MemberProbe.Clean) == BumpKind.Patch,
          Modver.minBumpKind("1.4.2", "early-semver", MemberProbe.JsOnly) == BumpKind.Patch,
          Modver.minBumpKind("1.4.2", "early-semver", MemberProbe.FirstPublish) == BumpKind.None,
        )
      },
      test("group max ignores None and uses minBumpOrd") {
        assertTrue(
          Modver.maxKind(List(BumpKind.Patch, BumpKind.Major, BumpKind.None)) == BumpKind.Major,
          Modver.maxKind(List(BumpKind.None)) == BumpKind.None,
          Modver.maxKind(Nil) == BumpKind.None,
        )
      },
      test("writtenStatus is missing when equal, undersized when below the floor, over-bump when above") {
        assertTrue(
          Modver.writtenStatus("1.4.2", "1.4.2", BumpKind.Patch) == BumpStatus.Missing,
          Modver.writtenStatus("1.4.2", "1.4.3", BumpKind.Minor) == BumpStatus.Undersized,
          Modver.writtenStatus("1.4.2", "1.5.0", BumpKind.Patch) == BumpStatus.OverBump,
          Modver.writtenStatus("1.4.2", "1.4.3", BumpKind.Patch) == BumpStatus.Ok,
          Modver.writtenStatus("1.4.2", "1.4.2", BumpKind.None) == BumpStatus.Ok,
          Modver.checkFails(BumpStatus.Missing),
          Modver.checkFails(BumpStatus.Undersized),
          !Modver.checkFails(BumpStatus.OverBump),
          !Modver.checkFails(BumpStatus.Ok),
        )
      },
      test("JS-only roots skip MiMa and first publish has no floor") {
        val jsOnly = GraphFixture(
          List(
            node("uiJS", sources = List("ui/src/main/scala", "ui/src/main/scalajs"), root = "ui")
          )
        )
        assertTrue(
          Modver.isJsOnly(jsOnly, ModuleId.unsafeMake("ui")),
          !Modver.isJsOnly(matrix, ModuleId.unsafeMake("core")),
        )
      },
      test("report JSON round-trips through zio-json and missing bump fails the gate") {
        val kinds  = Map[ShipRef, BumpKind](ShipRef.One(ModuleId.unsafeMake("client")) -> BumpKind.Patch)
        val lifted = Set[ShipRef](ShipRef.One(ModuleId.unsafeMake("client")))
        Modver.report(index, index, lifted, MovedRows.empty, kinds, mimaRan = Set.empty) match
          case Left(err)     => assertTrue(err.isEmpty)
          case Right(report) =>
            val json = ModverReport.render(report)
            assertTrue(
              ModverReport.parse(json) == Right(report),
              report.rows.exists(r => r.identity == "client" && r.status == BumpStatus.Missing),
              report.rows.exists(r => Modver.checkFails(r.status)),
            )
      },
      test("comment body carries the sticky marker and a constructor table") {
        val row = ModverReportRow(
          identity = "client",
          label = "Ship",
          from = "0.3.0",
          written = "0.3.0",
          suggested = "0.3.1",
          constructor = """Ship("client", "0.3.1")""",
          kind = BumpKind.Patch,
          mimaRan = true,
          status = BumpStatus.Missing,
        )
        val body = ModverComment.body(ModverReport(List(row)), Some("""Ship("client", "0.3.1")"""))
        assertTrue(
          body.contains(ModverComment.Marker),
          body.contains("client"),
          body.contains("0.3.1"),
          body.contains("```suggestion"),
        )
      },
    ),
    suite("Capability.modverCheck")(
      test("allJobIds matches plan job keys") {
        val cap = Capability.modverCheck()
        val cfg = PlanConfig(skipMergedPrPush = false, verifyCleanLabel = None, affected = AffectedMode.Always)
        val ids = Planner.allJobIds(cap, graph, cfg).map(id => id: String).sorted
        val wf  = Planner.plan(graph, List(cap), cfg)
        assertTrue(ids == List("modver-check"), wf.jobs.keys.toList.sorted == ids)
      },
      test("planned YAML is pull_request and does not sit on test needs") {
        val cfg     = PlanConfig(skipMergedPrPush = false, verifyCleanLabel = None, affected = AffectedMode.Always)
        val wf      = Planner.plan(graph, List(Capability.modverCheck(), Capability.test), cfg)
        val checkIf = wf.jobs("modver-check").`if`.getOrElse("")
        assertTrue(
          checkIf.contains("pull_request"),
          !wf.jobs("modver-check").needs.contains("test"),
          !wf.jobs("test").needs.contains("modver-check"),
          wf.jobs("modver-check").env.contains(ModverCheck.BaseShaEnv),
        )
      },
    ),
    suite("thisCommitReleases")(
      test("a version change, a first add, or a new member releases that row") {
        val versioned = MovedRows(
          versionChanged = Set(ShipRef.Group(ShipGroupName("libs"))),
          added = Set.empty,
          newMembers = Set.empty,
        )
        val added  = MovedRows(Set.empty, added = Set(ShipRef.One(ModuleId("client"))), newMembers = Set.empty)
        val member = MovedRows(Set.empty, Set.empty, newMembers = Set(ModuleId("coreLib")))
        assertTrue(
          Modver.thisCommitReleases(libs, versioned, index),
          !Modver.thisCommitReleases(client, versioned, index),
          Modver.thisCommitReleases(client, added, index),
          Modver.thisCommitReleases(libs, member, index),
          !Modver.thisCommitReleases(client, member, index),
        )
      }
    ),
  )
end ModverSpec
