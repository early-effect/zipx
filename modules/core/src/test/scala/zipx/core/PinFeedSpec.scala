package zipx.core

import neotype.unwrap
import zipx.core.Rendered.yaml
import zio.test.*

import scala.collection.mutable

/** Pin-feed laws: fake lookup and OSV, synthetic ids, never a live registry. */
object PinFeedSpec extends ZIOSpecDefault:

  private val graph = GraphFixture(
    List(ModuleNode(ModuleId("lib"), publishes = true, crossScalaVersions = List("3.8.4"), baseDir = "lib"))
  )

  private val planConfig = PlanConfig(
    cacheEpoch = CacheEpoch.Fixed("1.0.0-ci"),
    affected = AffectedMode.Always,
    skipMergedPrPush = false,
    verifyCleanLabel = None,
  )

  private val cdn = PinFeedName("cdn")

  private def purlFor(id: String): Purl =
    Purl.make(s"pkg:npm/$id").fold(err => throw AssertionError(err), identity)

  private def pin(id: String, ver: String, named: Boolean = true, feedName: PinFeedName = cdn): Pin =
    val version = DepVersion.make(ver).fold(err => throw AssertionError(err), identity)
    Pin(feedName, id, version, None, Option.when(named)(purlFor(id)))

  private def feed(
      outdated: PinAction = PinAction.Ignore,
      advisory: PinAction = PinAction.Report,
      lookup: PinLookup = _ => Right(None),
      materialize: PinMaterialize = (_, _) => Right(()),
      minSeverity: AdvisorySeverity = AdvisorySeverity.Low,
      submitSnapshot: Boolean = false,
      name: PinFeedName = cdn,
      classify: VersionStrategy = VersionStrategy.npm,
  ): PinFeed =
    PinFeed(
      name = name,
      classify = classify,
      lookup = lookup,
      materialize = materialize,
      outdated = outdated,
      advisory = advisory,
      submitSnapshot = submitSnapshot,
      minSeverity = minSeverity,
    )

  private def cand(version: String): PinCandidate = PinCandidate(version)

  private def source(hits: Map[String, List[Advisory]]): AdvisorySource =
    (purl, _) => Right(hits.getOrElse(purl, Nil))

  private def failingSource: AdvisorySource =
    (_, _) => Left("osv: injected failure")

  private val gId: Gen[Any, String] =
    Gen.elements("lib-a", "lib-b", "lib-c", "lib-d")

  private val gPatch: Gen[Any, Int] = Gen.int(0, 9)

  private val gStable: Gen[Any, String] =
    for
      major <- Gen.int(0, 4)
      minor <- Gen.int(0, 9)
      patch <- gPatch
    yield s"$major.$minor.$patch"

  private val gPre: Gen[Any, String] =
    gStable.map(v => s"$v-rc.1")

  private val gSemver: Gen[Any, String] =
    Gen.oneOf(gStable, gPre)

  private val gAction: Gen[Any, PinAction] =
    Gen.elements(PinAction.Ignore, PinAction.Report, PinAction.Update)

  private val gSeverity: Gen[Any, AdvisorySeverity] =
    Gen.elements(AdvisorySeverity.Low, AdvisorySeverity.Moderate, AdvisorySeverity.High, AdvisorySeverity.Critical)

  private val gInventory: Gen[Any, List[Pin]] =
    for
      ids     <- Gen.listOfBounded(1, 4)(gId).map(_.distinct).filter(_.nonEmpty)
      named   <- Gen.listOfN(ids.size)(Gen.boolean)
      current <- Gen.listOfN(ids.size)(gStable)
    yield ids.zip(named).zip(current).map { case ((id, n), v) => pin(id, v, n) }

  def spec = suite("PinFeed")(
    suite("PinAction")(
      test("Ignore and Report never apply; Update records matching outdated pins without materializing") {
        check(gInventory, gAction, gStable) { (inventory, action, candidate) =>
          val materialized = mutable.ListBuffer.empty[String]
          val latest       = inventory.map(p => p.id -> candidate).toMap
          val f            = feed(
            outdated = action,
            advisory = PinAction.Ignore,
            lookup = p => Right(latest.get(p.id).map(cand)),
            materialize = (p, _) =>
              materialized += p.id; Right(()),
          )
          val report   = PinEngine.scheduled(List(f), inventory, source(Map.empty))
          val matching =
            inventory.filter(p => f.classify.classify(p.current, candidate) != BumpKind.None).map(_.id).toSet
          report match
            case Left(err) => assertTrue(err.isEmpty)
            case Right(r)  =>
              action match
                case PinAction.Ignore | PinAction.Report =>
                  assertTrue(materialized.isEmpty, r.applied.isEmpty)
                case PinAction.Update =>
                  assertTrue(materialized.isEmpty, r.applied.map(_.pin.id).toSet == matching)
        }
      },
      test("advisory Update records applied only on pins with a hit and a newer candidate") {
        check(gInventory.filter(_.exists(_.purl.isDefined)), gStable) { (inventory, candidate) =>
          val named = inventory.filter(_.purl.isDefined)
          val hits  = named.map(p => (p.purl.get: String) -> List(Advisory("GHSA-a", AdvisorySeverity.High, "x"))).toMap
          val f     = feed(
            advisory = PinAction.Update,
            lookup = _ => Right(Some(cand(candidate))),
          )
          val report = PinEngine.scheduled(List(f), inventory, source(hits))
          val expect = named.filter(p => p.current != candidate).map(_.id).toSet
          report match
            case Left(_)  => assertTrue(false)
            case Right(r) => assertTrue(r.applied.map(_.pin.id).toSet == expect)
        }
      },
    ),
    suite("PinPrGate")(
      test("All findings are a superset of Introduced; Introduced is only new or changed ids") {
        check(gInventory, gInventory) { (current, baseInv) =>
          val named = current.filter(_.purl.isDefined)
          val hits  =
            named.map(p => (p.purl.get: String) -> List(Advisory("GHSA-a", AdvisorySeverity.High, "x"))).toMap
          val f        = feed()
          val src      = source(hits)
          val base     = Map(cdn -> baseInv.map(_.toPinnedDep))
          val all      = PinEngine.prGate(List(f), current, src, PinPrGate.All, base)
          val intro    = PinEngine.prGate(List(f), current, src, PinPrGate.Introduced, base)
          val off      = PinEngine.prGate(List(f), current, src, PinPrGate.Off, base)
          val baseById = baseInv.map(p => p.id -> p.current).toMap
          (all, intro, off) match
            case (Right(a), Right(i), Right(o)) =>
              val allIds   = a.findings.map(_.pin.id).toSet
              val introIds = i.findings.map(_.pin.id).toSet
              val changed  = current.filter(p => baseById.get(p.id).forall(_ != p.current)).map(_.id).toSet
              assertTrue(
                introIds.subsetOf(allIds),
                introIds.subsetOf(changed),
                o.findings.isEmpty,
                a.findings.forall(_.pin.purl.isDefined),
                i.findings.forall(_.pin.purl.isDefined),
              )
            case _ => assertTrue(false)
          end match
        }
      },
      test("Off and empty feeds omit the builtin") {
        val f = feed()
        assertTrue(
          !PinFeeds.emitPrGate(Nil, PinPrGate.All),
          !PinFeeds.emitPrGate(List(f), PinPrGate.Off),
          PinFeeds.emitPrGate(List(f), PinPrGate.All),
          PinFeeds.emitPrGate(List(f), PinPrGate.Introduced),
          !PinFeeds.emitPrGate(List(feed(advisory = PinAction.Ignore)), PinPrGate.All),
        )
      },
    ),
    suite("min-severity")(
      test("raising the threshold never adds findings; lowering never drops a higher-severity one") {
        check(gInventory.filter(_.exists(_.purl.isDefined)), gSeverity, gSeverity) { (inventory, low, high) =>
          val (lo, hi) = if low.rank <= high.rank then (low, high) else (high, low)
          val named    = inventory.filter(_.purl.isDefined)
          val hits     = named.map { p =>
            (p.purl.get: String) -> List(Advisory("GHSA-a", AdvisorySeverity.High, "x"))
          }.toMap
          val src = source(hits)
          val a   = PinEngine.prGate(List(feed(minSeverity = lo)), inventory, src, PinPrGate.All)
          val b   = PinEngine.prGate(List(feed(minSeverity = hi)), inventory, src, PinPrGate.All)
          (a, b) match
            case (Right(lower), Right(higher)) =>
              val lowerIds  = lower.findings.map(_.pin.id).toSet
              val higherIds = higher.findings.map(_.pin.id).toSet
              assertTrue(higherIds.subsetOf(lowerIds))
            case _ => assertTrue(false)
        }
      }
    ),
    suite("OSV trichotomy")(
      test("empty vulns is not a finding, including a private PURL") {
        val named = pin("lib-a", "1.2.3")
        val priv  = pin("internal", "1.0.0")
        val f     = feed()
        PinEngine.prGate(List(f), List(named, priv), source(Map.empty), PinPrGate.All) match
          case Left(_)  => assertTrue(false)
          case Right(r) =>
            assertTrue(r.findings.isEmpty, r.scanned == 2, r.skipped == 0, !r.failsJob)
      },
      test("no PURL is skip; all-private inventory is a successful no-op") {
        val pins = List(pin("vendor", "1.0.0", named = false), pin("blob", "2.0.0", named = false))
        PinEngine.prGate(List(feed()), pins, failingSource, PinPrGate.All) match
          case Left(_)  => assertTrue(false)
          case Right(r) =>
            assertTrue(r.skipped == 2, r.scanned == 0, r.findings.isEmpty, !r.failsJob)
      },
      test("injected OSV failure is Left, never a green empty report") {
        PinEngine.prGate(List(feed()), List(pin("lib-a", "1.2.3")), failingSource, PinPrGate.All) match
          case Left(err) => assertTrue(err.contains("osv"))
          case Right(_)  => assertTrue(false)
      },
      test("injected lookup failure is Left") {
        val f = feed(outdated = PinAction.Report, lookup = _ => Left("lookup: down"))
        PinEngine.scheduled(List(f), List(pin("lib-a", "1.2.3")), source(Map.empty)) match
          case Left(err) => assertTrue(err.contains("lookup"))
          case Right(_)  => assertTrue(false)
      },
      test("parse empty vulns as no finding") {
        assertTrue(
          OsvAdvisorySource.parseResponse("""{"vulns":[]}""") == Right(Nil),
          OsvAdvisorySource.parseResponse("{}") == Right(Nil),
        )
      },
    ),
    suite("VersionStrategy.npm")(
      test("equal versions classify as None") {
        check(gSemver) { v =>
          assertTrue(VersionStrategy.npm.classify(v, v) == BumpKind.None)
        }
      },
      test("classify is a function of the two versions only") {
        check(gSemver, gSemver) { (a, b) =>
          assertTrue(VersionStrategy.npm.classify(a, b) == VersionStrategy.npm.classify(a, b))
        }
      },
      test("usual semver precedence") {
        assertTrue(
          VersionStrategy.npm.classify("1.2.3", "1.2.4") == BumpKind.Patch,
          VersionStrategy.npm.classify("1.2.3", "1.3.0") == BumpKind.Minor,
          VersionStrategy.npm.classify("1.2.3", "2.0.0") == BumpKind.Major,
          VersionStrategy.npm.classify("1.2.3", "1.2.4-rc.1") == BumpKind.PreRelease,
        )
      },
      test("latestStable never returns a pre-release when any stable candidate exists") {
        check(Gen.listOfBounded(1, 5)(gSemver)) { candidates =>
          val latest    = VersionStrategy.npm.latestStable(candidates)
          val hasStable = candidates.exists(c => !c.contains("-"))
          assertTrue(
            latest.forall(v => !v.contains("-") || !hasStable),
            !hasStable || latest.exists(v => !v.contains("-")),
          )
        }
      },
    ),
    suite("VersionStrategy.exact")(
      test("equal is None; any other string is not None") {
        check(Gen.elements("a", "b", "tag-1", "v2"), Gen.elements("a", "b", "tag-1", "v2")) { (a, b) =>
          assertTrue(
            VersionStrategy.exact.classify(a, a) == BumpKind.None,
            a == b || VersionStrategy.exact.classify(a, b) != BumpKind.None,
          )
        }
      }
    ),
    suite("Snapshot")(
      test("every pin with a PURL appears; pins without a PURL are omitted") {
        check(gInventory) { inventory =>
          val f    = feed(submitSnapshot = true)
          val json =
            PinSnapshot.render(List(f), inventory, "abc", "refs/heads/main", "1", "2020-01-01T00:00:00Z", "0.1.0")
          val named   = inventory.filter(_.purl.isDefined)
          val unnamed = inventory.filter(_.purl.isEmpty)
          assertTrue(
            named.forall(p => json.contains(p.id)),
            unnamed.forall(p => !json.contains(s""""${p.id}"""") || named.exists(_.id == p.id)),
            json.contains(PinSnapshot.Correlator),
          )
        }
      },
      test("multi-feed merge is a union of manifests; correlator is stable") {
        val aPins = List(pin("lib-a", "1.0.0", feedName = PinFeedName("cdn")))
        val bPins = List(pin("lib-b", "2.0.0", feedName = PinFeedName("vendor")))
        val a     = feed(name = PinFeedName("cdn"))
        val b     = feed(name = PinFeedName("vendor"))
        val json  = PinSnapshot.render(List(a, b), aPins ++ bPins, "sha", "refs/heads/main", "9", "t", "0.1.0")
        val again = PinSnapshot.render(List(a, b), aPins ++ bPins, "other", "refs/heads/dev", "8", "t2", "0.2.0")
        assertTrue(
          json.contains("\"cdn\""),
          json.contains("\"vendor\""),
          json.contains("lib-a"),
          json.contains("lib-b"),
          json.contains(PinSnapshot.Correlator),
          again.contains(PinSnapshot.Correlator),
        )
      },
      test("inventory round-trip") {
        check(gInventory) { inventory =>
          val f    = feed()
          val json = PinInventory.render(List(f), inventory)
          PinInventory.parse(json) match
            case Left(_)  => assertTrue(false)
            case Right(m) =>
              val got = m.getOrElse(cdn, Nil).map(p => p.id -> p.current)
              assertTrue(got == inventory.map(p => p.id -> p.current))
        }
      },
    ),
    suite("Capability.pinCheck")(
      test("allJobIds matches plan job keys") {
        val cap = Capability.pinCheck()
        val ids = Planner.allJobIds(cap, graph, planConfig).map(id => id: String).sorted
        val wf  = Planner.plan(graph, List(cap), planConfig)
        assertTrue(ids == List("pin-check"), wf.jobs.keys.toList.sorted == ids)
      },
      test("planned YAML is pull_request and does not sit on test needs") {
        val wf    = Planner.plan(graph, List(Capability.pinCheck(), Capability.test), planConfig)
        val pinIf = wf.jobs("pin-check").`if`.getOrElse("")
        assertTrue(
          pinIf.contains("pull_request"),
          wf.jobs("pin-check").needs.isEmpty || !wf.jobs("pin-check").needs.contains("test"),
          !wf.jobs("test").needs.contains("pin-check"),
          wf.jobs("pin-check").env.contains(PinCheck.BaseShaEnv),
        )
      },
    ),
    suite("Companions")(
      test("empty feeds emit no companions; submitSnapshot false omits snapshot") {
        val none  = Seq.empty[PinFeed]
        val alert = List(feed())
        val snap  = List(feed(submitSnapshot = true))
        assertTrue(
          !PinFeeds.emitCompanions(none),
          PinFeeds.emitCompanions(alert),
          !PinFeeds.emitSnapshot(alert),
          PinFeeds.emitSnapshot(snap),
        )
      },
      test("check companion always has a schedule, dispatch, and checkout before sbt") {
        check(Gen.boolean) { hasUpdate =>
          val yaml       = PinCheckWorkflow.render(ActionPins.Defaults, "21", "ubuntu-latest", hasUpdate).yaml
          val checkoutAt = yaml.indexOf(ActionPins.Defaults.checkout.unwrap)
          val sbtAt      = yaml.indexOf("sbt zipxPinCheck")
          assertTrue(
            yaml.contains("cron:") && (yaml.contains("0 0 * * 0") || yaml.contains("\"0 0 * * 0\"")),
            yaml.contains("workflow_dispatch"),
            checkoutAt >= 0,
            sbtAt > checkoutAt,
            hasUpdate == yaml.contains("gh pr create"),
            hasUpdate == (yaml.contains("pull-requests: write") || yaml.contains("pull-requests:write")),
            hasUpdate == yaml.contains("secrets.GITHUB_TOKEN"),
          )
        }
      },
      test("snapshot companion is default-branch push with contents write") {
        val yaml       = PinSnapshotWorkflow.render(ActionPins.Defaults, "21", "ubuntu-latest", List("main")).yaml
        val checkoutAt = yaml.indexOf(ActionPins.Defaults.checkout.unwrap)
        val sbtAt      = yaml.indexOf("sbt zipxPinSubmit")
        assertTrue(
          yaml.contains("pin-snapshot"),
          yaml.contains("contents: write") || yaml.contains("contents:write"),
          checkoutAt >= 0,
          sbtAt > checkoutAt,
          !yaml.contains("pull_request"),
        )
      },
    ),
    suite("local outdated with approval")(
      test("outdated ignores PinAction and never materializes") {
        check(gInventory, gAction, gStable) { (inventory, action, candidate) =>
          val materialized = mutable.ListBuffer.empty[String]
          val latest       = inventory.map(p => p.id -> candidate).toMap
          val f            = feed(
            outdated = action,
            lookup = p => Right(latest.get(p.id).map(cand)),
            materialize = (p, _) =>
              materialized += p.id; Right(()),
          )
          val expect =
            inventory.filter(p => f.classify.classify(p.current, candidate) != BumpKind.None).map(_.id).toSet
          PinEngine.outdated(List(f), inventory) match
            case Left(err)    => assertTrue(err.isEmpty)
            case Right(bumps) =>
              assertTrue(materialized.isEmpty, bumps.map(_.pin.id).toSet == expect)
        }
      },
      test("materialize runs only on the listed set") {
        check(gInventory.filter(_.sizeIs >= 2)) { inventory =>
          val materialized = mutable.ListBuffer.empty[String]
          val f            = feed(
            lookup = _ => Right(Some(cand("99.0.0"))),
            materialize = (p, _) =>
              materialized += p.id; Right(()),
          )
          PinEngine.outdated(List(f), inventory).flatMap { bumps =>
            val chosen = bumps.take(1)
            PinEngine.materialize(List(f), chosen.map(_.applied)).map(_ -> chosen)
          } match
            case Left(err)                    => assertTrue(err.isEmpty)
            case Right((appliedPins, chosen)) =>
              assertTrue(
                materialized.toSet == chosen.map(_.pin.id).toSet,
                appliedPins.map(_.pin.id) == chosen.map(_.pin.id),
                chosen.size == 1,
                materialized.size == 1,
              )
          end match
        }
      },
      test("materialize is Left when the feed is not in the list") {
        val item = AppliedPin(pin("lib-a", "1.2.3", feedName = PinFeedName("other")), cand("1.2.4"))
        PinEngine.materialize(List(feed()), List(item)) match
          case Left(err) => assertTrue(err.contains("unknown pin feed"))
          case Right(_)  => assertTrue(false)
      },
      test("formatBumps names feed, id, and versions") {
        val bumps = List(PinBump(pin("lib-a", "1.2.3"), BumpKind.Patch, cand("1.2.4")))
        val text  = PinEngine.formatBumps(bumps)
        assertTrue(
          text.contains("cdn"),
          text.contains("lib-a"),
          text.contains("1.2.3"),
          text.contains("1.2.4"),
          PinEngine.formatBumps(Nil) == "no outdated pins",
        )
      },
    ),
    suite("PinFeedName / Purl")(
      test("invalid ids and non-pkg PURLs are Left at make") {
        assertTrue(
          PinFeedName.make("").isLeft,
          PinFeedName.make("cdn/foo").isLeft,
          PinFeedName.make("1cdn").isLeft,
          PinFeedName.make("cdn").isRight,
          Purl.make("npm/lib-a").isLeft,
          Purl.make("pkg:npm/lib-a").isRight,
        )
      }
    ),
  )
end PinFeedSpec
