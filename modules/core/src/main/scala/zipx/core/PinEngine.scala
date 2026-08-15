package zipx.core

enum PinSignal:
  case Outdated(bump: BumpKind, candidate: String)
  case AdvisoryHit(advisories: List[Advisory])

final case class PinFinding(feed: PinFeedName, pin: PinnedDep, signal: PinSignal)

final case class AppliedPin(feed: PinFeedName, pin: PinnedDep, to: String)

/** A proposed version bump from [[PinEngine.outdated]], independent of [[PinAction]]. */
final case class PinBump(feed: PinFeedName, pin: PinnedDep, bump: BumpKind, to: String)

final case class PinReport(
    scanned: Int,
    skipped: Int,
    findings: List[PinFinding],
    applied: List[AppliedPin],
):
  def failsJob: Boolean = findings.nonEmpty

object PinEngine:

  /** Scheduled path: lookup + classify for outdated, OSV for advisories, apply only under [[PinAction.Update]]. */
  def scheduled(feeds: Seq[PinFeed], source: AdvisorySource): Either[String, PinReport] =
    feeds
      .foldLeft[Either[String, Accum]](Right(Accum.empty)) { (accE, feed) =>
        accE.flatMap(acc => scheduledFeed(feed, source, acc))
      }
      .map(_.report)

  /** PR path: OSV only. Never looks up latest and never applies. */
  def prGate(
      feeds: Seq[PinFeed],
      source: AdvisorySource,
      gate: PinPrGate,
      base: Map[PinFeedName, List[PinnedDep]] = Map.empty,
  ): Either[String, PinReport] =
    if gate == PinPrGate.Off then Right(PinReport(0, 0, Nil, Nil))
    else
      feeds
        .foldLeft[Either[String, Accum]](Right(Accum.empty)) { (accE, feed) =>
          accE.flatMap(acc => prFeed(feed, source, gate, base.getOrElse(feed.name, Nil), acc))
        }
        .map(_.report)

  /** Lookup + classify every pin, ignoring [[PinAction]]. Local `zipxPinUpdate` uses this so alert-only feeds can still
    * bump with approval.
    */
  def outdated(feeds: Seq[PinFeed]): Either[String, List[PinBump]] =
    feeds.foldLeft[Either[String, List[PinBump]]](Right(Nil)) { (accE, feed) =>
      accE.flatMap(acc => outdatedFeed(feed, acc))
    }

  /** Apply a previously listed set of bumps. Never looks up; never applies a pin that is not in `bumps`. */
  def applyBumps(feeds: Seq[PinFeed], bumps: List[PinBump]): Either[String, List[AppliedPin]] =
    val byName = feeds.map(f => f.name -> f).toMap
    bumps.foldLeft[Either[String, List[AppliedPin]]](Right(Nil)) { (accE, bump) =>
      accE.flatMap { acc =>
        byName.get(bump.feed) match
          case None       => Left(s"unknown pin feed '${bump.feed}'")
          case Some(feed) =>
            feed.apply(bump.pin, bump.to).map(_ => acc :+ AppliedPin(feed.name, bump.pin, bump.to))
      }
    }
  end applyBumps

  def formatBumps(bumps: List[PinBump]): String =
    if bumps.isEmpty then "no outdated pins"
    else
      bumps
        .map(b => s"- ${b.feed} ${b.pin.id}: ${b.pin.current} -> ${b.to} (${b.bump})")
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

  private def outdatedFeed(feed: PinFeed, acc: List[PinBump]): Either[String, List[PinBump]] =
    feed.inventory.foldLeft[Either[String, List[PinBump]]](Right(acc)) { (accE, pin) =>
      accE.flatMap { bumps =>
        feed.lookup(pin).map { latest =>
          latest
            .flatMap { candidate =>
              val kind = feed.classify.classify(pin.current, candidate)
              Option.when(kind != BumpKind.None) {
                val to = feed.classify.latestStable(List(candidate)).getOrElse(candidate)
                PinBump(feed.name, pin, kind, to)
              }
            }
            .fold(bumps)(bumps :+ _)
        }
      }
    }

  private def scheduledFeed(feed: PinFeed, source: AdvisorySource, acc: Accum): Either[String, Accum] =
    feed.inventory.foldLeft[Either[String, Accum]](Right(acc)) { (accE, pin) =>
      accE.flatMap(a => scheduledPin(feed, pin, source, a))
    }

  private def scheduledPin(
      feed: PinFeed,
      pin: PinnedDep,
      source: AdvisorySource,
      acc: Accum,
  ): Either[String, Accum] =
    for
      afterOutdated <- handleOutdated(feed, pin, acc)
      afterAdvisory <- handleAdvisory(feed, pin, source, afterOutdated, applyUpdates = true)
    yield afterAdvisory

  private def prFeed(
      feed: PinFeed,
      source: AdvisorySource,
      gate: PinPrGate,
      base: List[PinnedDep],
      acc: Accum,
  ): Either[String, Accum] =
    if feed.advisory == PinAction.Ignore then Right(acc)
    else
      val baseById = base.map(p => p.id -> p.current).toMap
      feed.inventory.foldLeft[Either[String, Accum]](Right(acc)) { (accE, pin) =>
        accE.flatMap { a =>
          val changed = !baseById.contains(pin.id) || baseById(pin.id) != pin.current
          val include = gate == PinPrGate.All || changed
          if !include then Right(a)
          else handleAdvisory(feed, pin, source, a, applyUpdates = false)
        }
      }

  private def handleOutdated(feed: PinFeed, pin: PinnedDep, acc: Accum): Either[String, Accum] =
    feed.outdated match
      case PinAction.Ignore => Right(acc)
      case action           =>
        for
          latest <- feed.lookup(pin)
          bump = latest.map(c => feed.classify.classify(pin.current, c) -> c)
          next <- bump match
            case Some((b, candidate)) if b != BumpKind.None =>
              action match
                case PinAction.Ignore => Right(acc)
                case PinAction.Report =>
                  Right(
                    acc.copy(findings = acc.findings :+ PinFinding(feed.name, pin, PinSignal.Outdated(b, candidate)))
                  )
                case PinAction.Update =>
                  val target = feed.classify.latestStable(List(candidate)).getOrElse(candidate)
                  feed
                    .apply(pin, target)
                    .map(_ => acc.copy(applied = acc.applied :+ AppliedPin(feed.name, pin, target)))
            case _ => Right(acc)
        yield next

  private def handleAdvisory(
      feed: PinFeed,
      pin: PinnedDep,
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
                      latest <- feed.lookup(pin)
                      target <- latest.flatMap(c => feed.classify.latestStable(List(c)).orElse(latest)) match
                        case Some(to) if to != pin.current =>
                          feed.apply(pin, to).map(_ => AppliedPin(feed.name, pin, to))
                        case _ => Right(AppliedPin(feed.name, pin, pin.current))
                    yield
                      if target.to == pin.current then scanned
                      else scanned.copy(applied = scanned.applied :+ target)
            end if
          }
end PinEngine
