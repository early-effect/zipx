package zipx.core

import zio.test.*

object ModverSpec extends ZIOSpecDefault:

  private def mid(s: String): ModuleId        = ModuleId.unsafeMake(s)
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

  private val index = ShipIndex.from(covering)

  private val libsRef: ShipRef = ShipRef.Group(gname("libs"))

  private def libsReleasedAt(version: ReleaseVersion): ShipIndex =
    ShipIndex.from(List(libs.at(version), client))

  private def bumpStatus(lastReleases: ShipIndex, probe: MemberProbe): zio.IO[String, Map[String, BumpStatus]] =
    zio.ZIO.fromEither(
      for
        kinds  <- Modver.minBumps(Set(libsRef), index, graph, lastReleases, _ => "early-semver", _ => Right(probe))
        report <- Modver.report(index, lastReleases, kinds, mimaRan = Set.empty)
      yield report.rows.map(r => r.identity -> r.status).toMap
    )

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

  private val gVersion: Gen[Any, ReleaseVersion] =
    (for
      major <- Gen.int(0, 9)
      minor <- Gen.int(0, 9)
      patch <- Gen.int(0, 9)
    yield ReleaseVersion.make(s"$major.$minor.$patch")).collect { case Right(v) => v }

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
        case (List(one), _) => Ship(mid(one), ver)
        case (members, i)   =>
          ShipGroup(gname(s"g$i"), ver, members.map(mid))
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
          good     <- typeCheck("""zipx.core.Ship("core", "1.4.2")""")
          badId    <- typeCheck("""zipx.core.Ship("café", "1.4.2")""")
          badName  <- typeCheck("""zipx.core.ShipGroup("", "1.4.2")("core")""")
          snapshot <- typeCheck("""zipx.core.Ship("core", "1.4.2-SNAPSHOT")""")
          grouped  <- typeCheck("""zipx.core.ShipGroup("libs", "1.4.2-SNAPSHOT")("core")""")
        yield assertTrue(
          good.isRight,
          badId.isLeft,
          badName.isLeft,
          snapshot.left.exists(_.contains("a release number is major.minor.patch")),
          grouped.isLeft,
        )
      },
    ),
    suite("ReleaseVersion")(
      test("patch, minor, and major bumps reset the lower parts") {
        val v = ReleaseVersion("1.4.2")
        assertTrue(
          v.bump(ReleaseBump.Patch) == ReleaseVersion("1.4.3"),
          v.bump(ReleaseBump.Minor) == ReleaseVersion("1.5.0"),
          v.bump(ReleaseBump.Major) == ReleaseVersion("2.0.0"),
        )
      },
      test("every bump of any release number is a release number that npm semver orders after it") {
        check(gVersion, Gen.fromIterable(ReleaseBump.values)) { (v, by) =>
          val next = v.bump(by)
          assertTrue(
            ReleaseVersion.make(next).isRight,
            ReleaseBump.of(VersionStrategy.npm.classify(v, next)).contains(by),
          )
        }
      },
      test("only major 0 is initial development") {
        assertTrue(ReleaseVersion("0.9.9").isInitialDevelopment, !ReleaseVersion("1.0.0").isInitialDevelopment)
      },
      test("the runtime constructor refuses a snapshot and a qualifier") {
        assertTrue(
          ReleaseVersion.make("1.4.2-SNAPSHOT").isLeft,
          ReleaseVersion.make("1.4.2-M1").isLeft,
          ReleaseVersion.make("1.4").isLeft,
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
    suite("BuildSession")(
      test("a row member builds at <row>-SNAPSHOT, and at its catalog number only in a release session") {
        check(gVersion, Gen.boolean) { (v, grouped) =>
          val row: PublishedRow =
            if grouped then ShipGroup(gname("libs"), v, List(mid("models"))) else Ship(mid("client"), v)
          assertTrue(
            BuildSession.Development.versionOf(row) == s"$v-SNAPSHOT",
            BuildSession.SnapshotPublish.versionOf(row) == s"$v-SNAPSHOT",
            BuildSession.Release.versionOf(row) == (v: String),
          )
        }
      },
      test("the zipx.session JVM property names the session") {
        checkAll(Gen.fromIterable(BuildSession.values)) { session =>
          assertTrue(BuildSession.of(Map(BuildSession.Property -> session.id)) == Right(session))
        }
      },
      test("without the property a build is a development session, and an unknown session is refused") {
        assertTrue(
          BuildSession.of(Map("zipx.other" -> "x")) == Right(BuildSession.Development),
          BuildSession.of(Map(BuildSession.Property -> "nightly")) == Left(UnknownBuildSession("nightly")),
          UnknownBuildSession("nightly").message.contains("development, snapshot, release"),
        )
      },
      test("only a snapshot publish drops scaladoc") {
        assertTrue(BuildSession.values.filterNot(_.publishesDocs).toList == List(BuildSession.SnapshotPublish))
      },
    ),
    suite("membership")(
      test("the fixture catalog covers its graph") {
        assertTrue(Modver.membership(graph, covering) == Right(index))
      },
      test("a covering catalog is Right and every publishing root is in exactly one row") {
        check(gCovered) { (g, rows) =>
          Modver.membership(g, rows) match
            case Left(err)    => assertTrue(err == "")
            case Right(built) =>
              val roots = Modver.publishingRoots(g)
              assertTrue(
                roots.forall(built.byRoot.contains),
                roots.forall(r => built.byRoot.get(r).exists(_.memberRoots.contains(r))),
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
        val lifted  = Modver
          .membership(matrix, covered)
          .flatMap(built => Modver.liftedBumpSet(matrix, built, Some(List("core/src/main/scala/Foo.scala"))))
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
          val built = ShipIndex.from(rows)
          val bumps = BumpSet(built.byIdentity.keys.map(_ -> BumpKind.Patch).toMap)
          assertTrue(
            Modver.membership(g, rows) == Right(built),
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
        val built  = ShipIndex.from(rows)
        val seed   = BumpSet(Map(ShipRef.One(ModuleId("a")) -> BumpKind.Major))
        val matchB = Modver.expand(seed, chain, built, ModverPropagate.MatchBump).asMap
        val patch  = Modver.expand(seed, chain, built, ModverPropagate.PatchPublished).asMap
        assertTrue(
          Modver.membership(chain, rows) == Right(built),
          matchB.get(ShipRef.One(ModuleId("b"))).contains(BumpKind.Major),
          matchB.get(ShipRef.One(ModuleId("c"))).contains(BumpKind.Major),
          patch.get(ShipRef.One(ModuleId("b"))).contains(BumpKind.Patch),
          patch.get(ShipRef.One(ModuleId("c"))).contains(BumpKind.Patch),
        )
      },
      test("a platform-row dependsOn contracts to the matrix root") {
        val covered = List[PublishedRow](Ship("core", "1.4.2"), Ship("cli", "0.3.0"))
        val built   = ShipIndex.from(covered)
        val seed    = BumpSet(Map(ShipRef.One(ModuleId("core")) -> BumpKind.Minor))
        val out     = Modver.expand(seed, matrix, built, ModverPropagate.MatchBump).asMap
        assertTrue(
          Modver.membership(matrix, covered) == Right(built),
          out.get(ShipRef.One(ModuleId("cli"))).contains(BumpKind.Minor),
        )
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
        val built = ShipIndex.from(rows)
        val seed  = BumpSet(Map(ShipRef.One(ModuleId("core")) -> BumpKind.Major))
        val out   = Modver.expand(seed, hole, built, ModverPropagate.MatchBump).asMap
        assertTrue(
          Modver.membership(hole, rows) == Right(built),
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
        Modver.report(index, index, kinds, mimaRan = Set.empty) match
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
    suite("min-bump")(
      test("early-semver 0.y binary break is minor; 1.y is major") {
        assertTrue(
          Modver.minBumpKind(ReleaseVersion("0.4.2"), "early-semver", MemberProbe.BinaryBreak) == BumpKind.Minor,
          Modver.minBumpKind(ReleaseVersion("1.4.2"), "early-semver", MemberProbe.BinaryBreak) == BumpKind.Major,
          Modver.minBumpKind(ReleaseVersion("1.4.2"), "pvp", MemberProbe.BinaryBreak) == BumpKind.Major,
          Modver.minBumpKind(ReleaseVersion("1.4.2"), "semver-spec", MemberProbe.BinaryBreak) == BumpKind.Major,
          Modver.minBumpKind(ReleaseVersion("1.4.2"), "early-semver", MemberProbe.Clean) == BumpKind.Patch,
          Modver.minBumpKind(ReleaseVersion("1.4.2"), "early-semver", MemberProbe.JsOnly) == BumpKind.Patch,
          Modver.minBumpKind(ReleaseVersion("1.4.2"), "early-semver", MemberProbe.FirstPublish) == BumpKind.None,
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
        val kinds = Map[ShipRef, BumpKind](ShipRef.One(ModuleId.unsafeMake("client")) -> BumpKind.Patch)
        Modver.report(index, index, kinds, mimaRan = Set.empty) match
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
    suite("cache epoch")(
      test("the ShipCatalog epoch moves exactly when some row number moves, whatever the row order") {
        val gRows = gCovered.collect { case (_, first :: rest) => (first, rest) }
        check(gRows, gVersion) { case ((first, rest), v) =>
          val rows = first :: rest
          assertTrue(
            (Modver.epochHash(first.at(v) :: rest) == Modver.epochHash(rows)) == (first.version == v),
            Modver.epochHash(rows.reverse) == Modver.epochHash(rows),
          )
        }
      }
    ),
    suite("last release")(
      test("latestRelease is the highest release number, ignoring snapshots and qualified versions") {
        val xml =
          "<metadata><versioning><versions><version>1.9.0</version><version>1.10.0</version>" +
            "<version>1.11.0-SNAPSHOT</version><version>1.12.0-M1</version></versions></versioning></metadata>"
        assertTrue(
          MavenMetadata.latestRelease(xml).contains(ReleaseVersion("1.10.0")),
          MavenMetadata.latestRelease("<metadata/>").isEmpty,
        )
      },
      test("a changed row still at its last release must bump") {
        bumpStatus(lastReleases = index, probe = MemberProbe.Clean).map { status =>
          assertTrue(status.get("libs").contains(BumpStatus.Missing))
        }
      },
      test("a row already past its last release takes further compatible changes without another bump") {
        bumpStatus(lastReleases = libsReleasedAt(ReleaseVersion("1.4.1")), probe = MemberProbe.Clean).map { status =>
          assertTrue(status.get("libs").contains(BumpStatus.Ok))
        }
      },
      test("a binary break needs more than the row already declares") {
        bumpStatus(lastReleases = libsReleasedAt(ReleaseVersion("1.4.1")), probe = MemberProbe.BinaryBreak).map {
          status =>
            assertTrue(status.get("libs").contains(BumpStatus.Undersized))
        }
      },
      test("a row with no release is a first release, with no floor") {
        bumpStatus(lastReleases = ShipIndex.empty, probe = MemberProbe.BinaryBreak).map { status =>
          assertTrue(status.get("libs").contains(BumpStatus.Ok))
        }
      },
      test("an unreadable released artifact fails the check") {
        val bumps =
          Modver.minBumps(Set(libsRef), index, graph, index, _ => "early-semver", _ => Left("download: HTTP 503"))
        assertTrue(bumps == Left("download: HTTP 503"))
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
        val cfg   = PlanConfig(skipMergedPrPush = false, verifyCleanLabel = None, affected = AffectedMode.Always)
        val wf    = Planner.plan(graph, List(Capability.modverCheck(), Capability.test), cfg)
        val check = wf.jobs.get("modver-check")
        assertTrue(
          check.exists(_.`if`.exists(_.contains("pull_request"))),
          check.exists(!_.needs.contains("test")),
          wf.jobs.get("test").exists(!_.needs.contains("modver-check")),
          check.exists(_.env.contains(ModverCheck.BaseShaEnv)),
        )
      },
    ),
  )
end ModverSpec
