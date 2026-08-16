package zipx.core

/** Setting-time inventory dump so [[PinPrGate.Introduced]] can diff against a worktree at the PR base SHA. */
object PinInventory:

  val RelPath: String = "target/zipx-pin-inventory.json"

  def render(feeds: Seq[PinFeed], pins: Seq[Pin]): String =
    val items = feeds
      .map { feed =>
        val feedPins = PinFeeds
          .inventory(feed, pins)
          .map { pin =>
            s"""{"id":"${PinSnapshot.escape(pin.id)}","current":"${PinSnapshot.escape(pin.current)}"}"""
          }
          .mkString(",")
        s"""{"name":"${PinSnapshot.escape(feed.name)}","pins":[$feedPins]}"""
      }
      .mkString(",")
    s"""{"feeds":[$items]}"""
  end render

  def parse(json: String): Either[String, Map[PinFeedName, List[PinnedDep]]] =
    MiniJson.extractArray(json, "feeds") match
      case None             => Right(Map.empty)
      case Some(Left(err))  => Left(err)
      case Some(Right(arr)) =>
        MiniJson.objects(arr).foldLeft[Either[String, Map[PinFeedName, List[PinnedDep]]]](Right(Map.empty)) {
          (acc, obj) =>
            acc.flatMap { m =>
              MiniJson.stringField(obj, "name") match
                case None       => Left("pin inventory: feed missing name")
                case Some(name) =>
                  PinFeedName.make(name).flatMap { n =>
                    pinsOf(obj).map(ps => m + (n -> ps))
                  }
            }
        }

  private def pinsOf(feedObj: String): Either[String, List[PinnedDep]] =
    MiniJson.extractArray(feedObj, "pins") match
      case None             => Right(Nil)
      case Some(Left(err))  => Left(err)
      case Some(Right(arr)) =>
        MiniJson
          .objects(arr)
          .foldLeft[Either[String, List[PinnedDep]]](Right(Nil)) { (acc, obj) =>
            acc.flatMap { pins =>
              (MiniJson.stringField(obj, "id"), MiniJson.stringField(obj, "current")) match
                case (Some(id), Some(current)) => Right(pins :+ PinnedDep(id, current))
                case _                         => Left("pin inventory: pin missing id or current")
            }
          }
end PinInventory
