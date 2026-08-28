package zipx.core

import zio.json.*

/** Setting-time inventory dump so [[PinPrGate.Introduced]] can diff against a worktree at the PR base SHA. */
object PinInventory:

  val RelPath: String = "target/zipx-pin-inventory.json"

  final case class File(feeds: List[Feed]) derives JsonCodec
  final case class Feed(name: String, pins: List[PinRow]) derives JsonCodec
  final case class PinRow(id: String, current: String) derives JsonCodec

  def render(feeds: Seq[PinFeed], pins: Seq[Pin]): String =
    File(
      feeds.toList.map { feed =>
        Feed(
          name = feed.name,
          pins = PinFeeds.inventory(feed, pins).map(p => PinRow(p.id, p.current)),
        )
      }
    ).toJson

  def parse(json: String): Either[String, Map[PinFeedName, List[PinnedDep]]] =
    json.fromJson[File] match
      case Left(err)   => Left(s"pin inventory: $err")
      case Right(file) =>
        file.feeds.foldLeft[Either[String, Map[PinFeedName, List[PinnedDep]]]](Right(Map.empty)) { (acc, feed) =>
          acc.flatMap { m =>
            PinFeedName.make(feed.name).map { n =>
              m + (n -> feed.pins.map(p => PinnedDep(p.id, p.current)))
            }
          }
        }
end PinInventory
