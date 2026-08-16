package zipx.core

enum PinSignal:
  case Outdated(bump: BumpKind, candidate: String)
  case AdvisoryHit(advisories: List[Advisory])

final case class PinFinding(feed: PinFeedName, pin: Pin, signal: PinSignal)

final case class AppliedPin(pin: Pin, candidate: PinCandidate):
  def feed: PinFeedName = pin.feed
  def to: String        = candidate.version

/** A proposed version bump from [[PinEngine.outdated]], independent of [[PinAction]]. */
final case class PinBump(pin: Pin, bump: BumpKind, candidate: PinCandidate):
  def feed: PinFeedName = pin.feed
  def from: String      = pin.current
  def to: String        = candidate.version

  def applied: AppliedPin = AppliedPin(pin, candidate)

final case class PinReport(
    scanned: Int,
    skipped: Int,
    findings: List[PinFinding],
    applied: List[AppliedPin],
):
  def failsJob: Boolean = findings.nonEmpty

object PinEngine:

  /** Scheduled path: lookup + classify for outdated, OSV for advisories. Update records applied pins; catalog rewrite
    * and [[PinFeed.materialize]] happen in the plugin after this returns.
    */
  def scheduled(feeds: Seq[PinFeed], pins: Seq[Pin], source: AdvisorySource): Either[String, PinReport] =
    feeds
      .foldLeft[Either[String, Accum]](Right(Accum.empty)) { (accE, feed) =>
        accE.flatMap(acc => scheduledFeed(feed, pins, source, acc))
      }
      .map(_.report)

  /** PR path: OSV only. Never looks up latest and never applies. */
  def prGate(
      feeds: Seq[PinFeed],
      pins: Seq[Pin],
      source: AdvisorySource,
      gate: PinPrGate,
      base: Map[PinFeedName, List[PinnedDep]] = Map.empty,
  ): Either[String, PinReport] =
    if gate == PinPrGate.Off then Right(PinReport(0, 0, Nil, Nil))
    else
      feeds
        .foldLeft[Either[String, Accum]](Right(Accum.empty)) { (accE, feed) =>
          accE.flatMap(acc => prFeed(feed, pins, source, gate, base.getOrElse(feed.name, Nil), acc))
        }
        .map(_.report)

  /** Lookup + classify every pin, ignoring [[PinAction]]. Local `zipxPinUpdate` uses this so alert-only feeds can still
    * bump with approval.
    */
  def outdated(feeds: Seq[PinFeed], pins: Seq[Pin]): Either[String, List[PinBump]] =
    feeds.foldLeft[Either[String, List[PinBump]]](Right(Nil)) { (accE, feed) =>
      accE.flatMap(acc => outdatedFeed(feed, pins, acc))
    }

  /** Call [[PinFeed.materialize]] for a previously listed set. Never looks up; never materializes a pin that is not in
    * `items`.
    */
  def materialize(feeds: Seq[PinFeed], items: List[AppliedPin]): Either[String, List[AppliedPin]] =
    val byName = feeds.map(f => f.name -> f).toMap
    items.foldLeft[Either[String, List[AppliedPin]]](Right(Nil)) { (accE, item) =>
      accE.flatMap { acc =>
        byName.get(item.feed) match
          case None       => Left(s"unknown pin feed '${item.feed}'")
          case Some(feed) =>
            feed.materialize(item.pin, item.candidate).map(_ => acc :+ item)
      }
    }
  end materialize

  def formatBumps(bumps: List[PinBump]): String =
    if bumps.isEmpty then "no outdated pins"
    else
      bumps
        .map(b => s"- ${b.feed} ${b.pin.id}: ${b.from} -> ${b.to} (${b.bump})")
        .mkString("\n")

  def summary(report: PinReport): String =
    val findingLines =
      if report.findings.isEmpty then "- no findings"
      else
        report.findings
          .map { f =>
            val detail = f.signal match
              case PinSignal.Outdated(bump, candidate) =>
                s"outdated ${f.pin.current} -> $candidate ($bump)"
              case PinSignal.AdvisoryHit(advisories) =>
                advisories.map(a => s"${a.id} ${a.severity}").mkString(", ")
            s"- ${f.feed} ${f.pin.id}: $detail"
          }
          .mkString("\n")
    s"""## zipx pin check
scanned: ${report.scanned}
skipped: ${report.skipped}
findings: ${report.findings.size}
applied: ${report.applied.size}

$findingLines
"""
  end summary

  private final case class Accum(
      scanned: Int,
      skipped: Int,
      findings: List[PinFinding],
      applied: List[AppliedPin],
  ):
    def report: PinReport = PinReport(scanned, skipped, findings, applied)

  private object Accum:
    val empty: Accum = Accum(0, 0, Nil, Nil)

  private def targetCandidate(feed: PinFeed, candidate: PinCandidate): PinCandidate =
    val ver = feed.classify.latestStable(List(candidate.version)).getOrElse(candidate.version)
    candidate.copy(version = ver)

  private def outdatedFeed(feed: PinFeed, pins: Seq[Pin], acc: List[PinBump]): Either[String, List[PinBump]] =
    PinFeeds.inventory(feed, pins).foldLeft[Either[String, List[PinBump]]](Right(acc)) { (accE, pin) =>
      accE.flatMap { bumps =>
        feed.lookup(pin).map { latest =>
          latest
            .flatMap { candidate =>
              val kind = feed.classify.classify(pin.current, candidate.version)
              Option.when(kind != BumpKind.None) {
                PinBump(pin, kind, targetCandidate(feed, candidate))
              }
            }
            .fold(bumps)(bumps :+ _)
        }
      }
    }

  private def scheduledFeed(
      feed: PinFeed,
      pins: Seq[Pin],
      source: AdvisorySource,
      acc: Accum,
  ): Either[String, Accum] =
    PinFeeds.inventory(feed, pins).foldLeft[Either[String, Accum]](Right(acc)) { (accE, pin) =>
      accE.flatMap(a => scheduledPin(feed, pin, source, a))
    }

  private def scheduledPin(
      feed: PinFeed,
      pin: Pin,
      source: AdvisorySource,
      acc: Accum,
  ): Either[String, Accum] =
    for
      afterOutdated <- handleOutdated(feed, pin, acc)
      afterAdvisory <- handleAdvisory(feed, pin, source, afterOutdated, applyUpdates = true)
    yield afterAdvisory

  private def prFeed(
      feed: PinFeed,
      pins: Seq[Pin],
      source: AdvisorySource,
      gate: PinPrGate,
      base: List[PinnedDep],
      acc: Accum,
  ): Either[String, Accum] =
    if feed.advisory == PinAction.Ignore then Right(acc)
    else
      val baseById = base.map(p => p.id -> p.current).toMap
      PinFeeds.inventory(feed, pins).foldLeft[Either[String, Accum]](Right(acc)) { (accE, pin) =>
        accE.flatMap { a =>
          val changed = !baseById.contains(pin.id) || baseById(pin.id) != pin.current
          val include = gate == PinPrGate.All || changed
          if !include then Right(a)
          else handleAdvisory(feed, pin, source, a, applyUpdates = false)
        }
      }

  private def handleOutdated(feed: PinFeed, pin: Pin, acc: Accum): Either[String, Accum] =
    feed.outdated match
      case PinAction.Ignore => Right(acc)
      case action           =>
        for
          latest <- feed.lookup(pin)
          bump = latest.map(c => feed.classify.classify(pin.current, c.version) -> c)
          next <- bump match
            case Some((b, candidate)) if b != BumpKind.None =>
              action match
                case PinAction.Ignore => Right(acc)
                case PinAction.Report =>
                  Right(
                    acc.copy(findings =
                      acc.findings :+ PinFinding(feed.name, pin, PinSignal.Outdated(b, candidate.version))
                    )
                  )
                case PinAction.Update =>
                  Right(acc.copy(applied = acc.applied :+ AppliedPin(pin, targetCandidate(feed, candidate))))
            case _ => Right(acc)
        yield next

  private def handleAdvisory(
      feed: PinFeed,
      pin: Pin,
      source: AdvisorySource,
      acc: Accum,
      applyUpdates: Boolean,
  ): Either[String, Accum] =
    if feed.advisory == PinAction.Ignore then Right(acc)
    else
      pin.purl match
        case None       => Right(acc.copy(skipped = acc.skipped + 1))
        case Some(purl) =>
          source.advisories(purl, pin.current).flatMap { all =>
            val hits    = all.filter(_.severity.rank >= feed.minSeverity.rank)
            val scanned = acc.copy(scanned = acc.scanned + 1)
            if hits.isEmpty then Right(scanned)
            else
              feed.advisory match
                case PinAction.Ignore => Right(scanned)
                case PinAction.Report =>
                  Right(
                    scanned.copy(findings = scanned.findings :+ PinFinding(feed.name, pin, PinSignal.AdvisoryHit(hits)))
                  )
                case PinAction.Update =>
                  if !applyUpdates then
                    Right(
                      scanned
                        .copy(findings = scanned.findings :+ PinFinding(feed.name, pin, PinSignal.AdvisoryHit(hits)))
                    )
                  else
                    for
                      latest  <- feed.lookup(pin)
                      applied <- latest.map(targetCandidate(feed, _)) match
                        case Some(to) if to.version != pin.current =>
                          Right(Some(AppliedPin(pin, to)))
                        case _ => Right(None)
                    yield applied.fold(scanned)(a => scanned.copy(applied = scanned.applied :+ a))
            end if
          }
end PinEngine
