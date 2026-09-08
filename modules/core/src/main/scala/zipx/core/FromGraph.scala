package zipx.core

/** Selected module on a probe resolve, after eviction. `name` is the resolved artifact (with Scala suffix when
  * crossed).
  */
final case class ProbeModule(organization: String, name: String, revision: String)

/** Fill [[Lib.fromGraph]] revisions from a probe graph. No I/O. */
object FromGraph:

  final case class Request(lib: Lib, resolvedName: String)

  def requests(libs: List[Lib], resolvedName: Lib => String): List[Request] =
    libs.filter(_.isAligned).map(l => Request(l, resolvedName(l)))

  /** For each aligned lib, the unique selected revision of its `alignTo` GAV on the probe. */
  def revisions(
      requests: List[Request],
      probe: List[ProbeModule],
  ): Either[String, List[(Lib, String)]] =
    val alignedArtifacts = requests.map(r => r.lib.artifact: String).toSet
    val chained          = requests.collect {
      case r if r.lib.alignTo.exists(a => alignedArtifacts.contains(a: String)) => r.lib.artifact: String
    }
    if chained.nonEmpty then
      Left(s"zipx: fromGraph cannot chain (${chained.mkString(", ")}). Point alignTo at a concrete selected GAV.")
    else
      requests.foldLeft[Either[String, List[(Lib, String)]]](Right(Nil)) { (accE, req) =>
        accE.flatMap { acc =>
          val srcName = req.lib.alignTo.map(a => a: String).getOrElse(req.lib.artifact: String)
          val hits    =
            probe.filter(p => p.organization == (req.lib.group: String) && artifactFamily(p.name) == srcName)
          hits.map(_.revision).distinct match
            case List(rev) => Right(acc :+ (req.lib -> rev))
            case Nil       =>
              Left(
                s"zipx: fromGraph '${req.lib.artifact}' did not find ${req.lib.group}:$srcName on the probe graph"
              )
            case many =>
              Left(
                s"zipx: fromGraph '${req.lib.artifact}' found multiple revisions of ${req.lib.group}:$srcName (${many.mkString(", ")})"
              )
          end match
        }
      }
    end if
  end revisions

  /** Strip a trailing `_2.13` / `_3` / `_sjs1_3` family suffix for family matching. */
  def artifactFamily(resolvedName: String): String =
    val sjs = "_sjs"
    val n   = resolvedName.indexOf(sjs)
    val cut = if n > 0 then resolvedName.take(n) else resolvedName
    cut.lastIndexOf('_') match
      case i if i > 0 => cut.take(i)
      case _          => cut
end FromGraph
