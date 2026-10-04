package zipx.core

/** Why one ship cannot be released yet. The coordinate is `group:artifact` as a dependency declares it. */
enum ReleaseBlocker:
  case CommitPin(group: String, artifact: String, pin: SnapshotRevision.Commit)
  case LocalPin(group: String, artifact: String, id: String)
  case Pointer(group: String, artifact: String, revision: String)
  case ChangingSnapshot(group: String, artifact: String, revision: String)

object ReleaseBlocker:

  /** `None` when the revision is a release number. An in-repo module is filtered by the caller: it rides along. */
  def classify(group: String, artifact: String, revision: String): Option[ReleaseBlocker] =
    SnapshotRevision.parse(revision) match
      case Right(pin: SnapshotRevision.Commit) =>
        Some(ReleaseBlocker.CommitPin(group, artifact, pin))
      case Right(other) =>
        Some(ReleaseBlocker.LocalPin(group, artifact, other.id))
      case Left(_) if SnapshotPublishRevision.isPointer(revision) =>
        Some(ReleaseBlocker.Pointer(group, artifact, revision))
      case Left(_) if SnapshotPublishRevision.isImmutablePin(revision) =>
        SnapshotRevision.parse(revision.stripSuffix(Modver.UnreleasedSuffix)) match
          case Right(pin: SnapshotRevision.Commit) => Some(ReleaseBlocker.CommitPin(group, artifact, pin))
          case _                                   => None
      case Left(_) if SnapshotPins.isSnapshot(revision) =>
        Some(ReleaseBlocker.ChangingSnapshot(group, artifact, revision))
      case Left(_) =>
        None
end ReleaseBlocker

/** One unreleased ship and the external pins that block it. `ridesAlong` are in-repo ships that publish with it. */
final case class ShipGate(
    identity: String,
    version: ReleaseVersion,
    blockers: List[ReleaseBlocker],
    ridesAlong: List[String],
)

/** The text `zipxReleasePlan` prints. It uploads nothing. */
object ReleaseReadiness:

  /** `selected` empty means every unreleased ship (`all`). A name the catalog does not have is the command's error. */
  def render(unreleased: List[ShipGate], branch: String, selected: Option[List[String]]): String =
    val chosen = selected.fold(unreleased)(names => unreleased.filter(ship => names.contains(ship.identity)))
    if chosen.isEmpty then "every row's catalog number is already released"
    else
      val blocked = chosen.filter(_.blockers.nonEmpty)
      val ready   = chosen.filter(_.blockers.isEmpty)
      if blocked.isEmpty then
        s"Ready.\n\n${clearBody(ready, unreleased, branch, selected.isEmpty)}\n\n${shadowSentences(ready.map(_.version))}"
      else
        val blocks  = blocked.map(blockedShip).mkString("\n\n")
        val rest    = readyWhileBlocked(ready, chosen, branch)
        val refuses =
          if selected.isEmpty then s"\n\nall refuses, because ${included(blocked.map(_.identity))}." else ""
        s"Not ready.\n\n$blocks$rest$refuses"
    end if
  end render

  /** The paragraph the release job writes after a deployment. One sentence per released line. */
  def shadowSentences(lines: List[ReleaseVersion]): String =
    lines
      .map { line =>
        val next = line.bump(ReleaseBump.Patch)
        s"After the deployment, $line hides further snapshots of that line. Run sbt zipxModverBump and merge it so the next line is $next."
      }
      .mkString("\n")

  /** `libs/v1.4.2` and `v1.4.2` both name `1.4.2`. */
  def versionOfTag(tag: String): Option[ReleaseVersion] =
    val raw = tag match
      case s"$_/v$version" => version
      case s"v$version"    => version
      case other           => other
    ReleaseVersion.make(raw).toOption

  private def clearBody(
      ready: List[ShipGate],
      unreleased: List[ShipGate],
      branch: String,
      everything: Boolean,
  ): String =
    val companions = ready.flatMap(_.ridesAlong).distinct
    val titles     = ready.map(ship => s"${ship.identity} ${ship.version} can release.").mkString("\n")
    val target     = if everything then "all" else ready.map(_.identity).mkString(",")
    val leftOut    =
      if everything then Nil
      else unreleased.filterNot(ship => ready.exists(_.identity == ship.identity)).map(_.identity)
    s"$titles\n${rideLines(companions)}  gh workflow run zipx-release.yml --ref $branch -f ships=$target\n  That is one registry deployment.${stays(leftOut)}"
  end clearBody

  private def readyWhileBlocked(ready: List[ShipGate], chosen: List[ShipGate], branch: String): String =
    if ready.isEmpty then ""
    else
      "\n\n" + ready
        .map { ship =>
          val others = chosen.filterNot(_.identity == ship.identity).map(_.identity)
          shipParagraph(ship, ship.identity, branch, stays(others))
        }
        .mkString("\n\n")

  private def shipParagraph(ship: ShipGate, target: String, branch: String, staying: String): String =
    s"""${ship.identity} ${ship.version} can release.
       |${rideLines(ship.ridesAlong)}  gh workflow run zipx-release.yml --ref $branch -f ships=$target
       |  That is one registry deployment.$staying""".stripMargin

  private def blockedShip(ship: ShipGate): String =
    s"${ship.identity} ${ship.version} cannot release.\n${ship.blockers.map(blockerText).mkString("\n\n")}"

  private def blockerText(block: ReleaseBlocker): String = block match
    case ReleaseBlocker.CommitPin(group, artifact, pin) =>
      s"""  $group:$artifact is ${pin.id} (a snapshot: it is not the release number).
         |  A release POM cannot depend on a snapshot build. The snapshot repository deletes it.
         |
         |  1. Release $artifact ${pin.line} from the build that publishes it. One deployment.
         |  2. Here: sbt 'zipxPinRelease $artifact'
         |     That rewrites the pin from ${pin.id} to ${pin.line}. Same number.
         |  3. sbt zipxReleasePlan""".stripMargin
    case ReleaseBlocker.LocalPin(group, artifact, id) =>
      s"""  $group:$artifact is $id (a local build).
         |  It is not in the snapshot repository, and there is nothing to release it to.
         |  Commit and publish the sha, or drop the pin.""".stripMargin
    case ReleaseBlocker.Pointer(group, artifact, revision) =>
      s"""  $group:$artifact is $revision (the snapshot pointer, not a build).
         |  Run sbt zipxSnapshotStatus.""".stripMargin
    case ReleaseBlocker.ChangingSnapshot(group, artifact, revision) =>
      s"""  $group:$artifact is $revision (a snapshot: it is not the release number).
         |  A release POM cannot depend on a snapshot build. The snapshot repository deletes it.
         |  Pin the release number. zipxPinRelease rewrites a commit pin, not this revision.""".stripMargin

  private def rideLines(names: List[String]): String =
    names.map(name => s"  $name is in this build and is not released, so it rides along.\n").mkString

  private def stays(others: List[String]): String = others match
    case Nil        => ""
    case one :: Nil => s" $one stays a snapshot."
    case many       => s" ${join(many)} stay snapshots."

  private def included(names: List[String]): String = names match
    case one :: Nil => s"$one is included"
    case many       => s"${join(many)} are included"

  private def join(names: List[String]): String =
    names.reverse match
      case Nil          => ""
      case last :: Nil  => last
      case last :: init => s"${init.reverse.mkString(", ")} and $last"
end ReleaseReadiness
