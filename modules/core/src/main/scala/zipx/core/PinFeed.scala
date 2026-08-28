package zipx.core

import neotype.Subtype
import zipx.workflow.Names
import zio.json.*

/** A feed's name, which is also a snapshot manifest key and must be a GitHub Actions id. */
type PinFeedName = PinFeedName.Type
object PinFeedName extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.isEmpty then "a pin feed name must be non-empty"
    else if input.matches(Names.ActionsId) then true
    else
      s"invalid pin feed name '$input': it becomes a snapshot manifest key, so it must start with an ASCII letter or _ and contain only ASCII letters, digits, - or _"
end PinFeedName

/** What zipx does when a signal fires for a pin. */
enum PinAction:
  case Ignore, Report, Update

/** How the PR `pin-check` capability treats advisory findings. */
enum PinPrGate:
  case All, Introduced, Off

enum BumpKind derives JsonCodec:
  case None, Patch, Minor, Major, PreRelease

/** Whether catalog / pin lookup may list a pre-release as a bump. Default [[Skip]]. */
enum PreRelease:
  case Skip, Include

  def allows(kind: BumpKind): Boolean = this != Skip || kind != BumpKind.PreRelease

/** A Package URL. Pins without one skip OSV and snapshot submit. */
type Purl = Purl.Type
object Purl extends Subtype[String]:
  override inline def validate(input: String): Boolean | String =
    if input.startsWith("pkg:") then true
    else "a PURL must start with pkg:"

/** One currently pinned dependency in a feed's inventory (PR-gate JSON, snapshots). */
final case class PinnedDep(id: String, current: String, purl: Option[Purl] = None)

/** Lookup result: next version plus the checksum / PURL that must move with it. */
final case class PinCandidate(version: String, sha256: Option[String] = None, purl: Option[Purl] = None)

type PinLookup      = Pin => Either[String, Option[PinCandidate]]
type PinMaterialize = (Pin, PinCandidate) => Either[String, Unit]

/** Topology and policy in zipx. Inventory is catalog [[Pin]] vals; catalog rewrite is zipx-owned. */
final case class PinFeed(
    name: PinFeedName,
    classify: VersionStrategy,
    lookup: PinLookup,
    materialize: PinMaterialize = (_, _) => Right(()),
    outdated: PinAction = PinAction.Ignore,
    advisory: PinAction = PinAction.Report,
    submitSnapshot: Boolean = false,
    minSeverity: AdvisorySeverity = AdvisorySeverity.Low,
)

object PinFeeds:

  def emitPrGate(feeds: Seq[PinFeed], gate: PinPrGate): Boolean =
    gate != PinPrGate.Off && feeds.exists(_.advisory != PinAction.Ignore)

  def emitCompanions(feeds: Seq[PinFeed]): Boolean = feeds.nonEmpty

  def emitSnapshot(feeds: Seq[PinFeed]): Boolean = feeds.exists(_.submitSnapshot)

  def hasUpdate(feeds: Seq[PinFeed]): Boolean =
    feeds.exists(f => f.outdated == PinAction.Update || f.advisory == PinAction.Update)

  def inventory(feed: PinFeed, pins: Seq[Pin]): List[Pin] =
    pins.filter(_.feed == feed.name).toList

  def orphanPins(feeds: Seq[PinFeed], pins: Seq[Pin]): List[Pin] =
    val names = feeds.map(_.name).toSet
    pins.filterNot(p => names.contains(p.feed)).toList
end PinFeeds
