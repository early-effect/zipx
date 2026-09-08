package zipx.core

/** A selected catalog row on one sbt project, including `provided`. */
final case class SelectedLib(
    valName: String,
    group: String,
    artifact: String,
    revision: String,
    config: String,
)

/** One caller-to-callee edge from the resolved Maven graph. `name` is the resolved artifact. */
final case class CallerEdge(
    organization: String,
    name: String,
    revision: String,
    callerOrganization: String,
    callerName: String,
    config: String,
)

final case class RedundantLib(selected: SelectedLib, via: String)
final case class AlignableLib(selected: SelectedLib, familyArtifact: String, familyRevision: String)

final case class DepCleanupReport(
    projectId: String,
    redundant: List[RedundantLib],
    alignable: List[AlignableLib],
):
  def isEmpty: Boolean = redundant.isEmpty && alignable.isEmpty

  def render: String =
    val header = s"zipxDepCleanup ($projectId):"
    if isEmpty then s"$header nothing to drop"
    else
      val drop =
        if redundant.isEmpty then Nil
        else
          "already on the graph; drop from libraryDependencies:" +:
            redundant.map(r =>
              s"  ${r.selected.valName}  (${r.selected.group}:${r.selected.artifact}, selected ${r.selected.revision}; ${r.via})"
            )
      val align =
        if alignable.isEmpty then Nil
        else
          "could follow a graph revision (.fromGraph):" +:
            alignable.map(a =>
              s"  ${a.selected.valName}  (${a.selected.group}:${a.selected.artifact}; family ${a.familyArtifact} ${a.familyRevision})"
            )
      (List(header) ++ drop ++ align).mkString("\n")
    end if
  end render
end DepCleanupReport

object DepCleanup:

  def keyOf(group: String, artifact: String): String = s"$group:${FromGraph.artifactFamily(artifact)}"

  /** Reachability after removing the whole redundant set together. `provided` is analyzed. */
  def analyze(
      projectId: String,
      selected: List[SelectedLib],
      edges: List[CallerEdge],
  ): DepCleanupReport =
    def k(s: SelectedLib): String = keyOf(s.group, s.artifact)

    def closure(from: List[SelectedLib]): Set[String] =
      val byCaller = edges.groupBy(e => keyOf(e.callerOrganization, e.callerName))
      val seen     = scala.collection.mutable.Set.empty[String]
      val q        = scala.collection.mutable.Queue.from(from.map(k))
      seen ++= q
      while q.nonEmpty do
        val cur = q.dequeue()
        byCaller.getOrElse(cur, Nil).foreach { e =>
          val dep = keyOf(e.organization, e.name)
          if seen.add(dep) then q.enqueue(dep)
        }
      seen.toSet
    end closure

    val tentative = selected.filter { s =>
      closure(selected.filterNot(o => k(o) == k(s))).contains(k(s))
    }
    val dropKeys     = tentative.map(k).toSet
    val keep         = selected.filterNot(s => dropKeys.contains(k(s)))
    val fromKeep     = closure(keep)
    val together     = tentative.filter(s => fromKeep.contains(k(s)))
    val togetherKeys = together.map(k).toSet

    val redundant = together.map { s =>
      val via = edges
        .find { e =>
          keyOf(e.organization, e.name) == k(s) && !togetherKeys.contains(keyOf(e.callerOrganization, e.callerName))
        }
        .map(e => s"also from ${e.callerName}")
        .getOrElse("also from another selected row")
      RedundantLib(s, via)
    }

    val alignable = selected
      .filterNot(s => togetherKeys.contains(k(s)))
      .flatMap(s => familyCandidate(s, selected, edges).map { (art, rev) => AlignableLib(s, art, rev) })

    DepCleanupReport(projectId, redundant, alignable)
  end analyze

  private def familyCandidate(
      selected: SelectedLib,
      all: List[SelectedLib],
      edges: List[CallerEdge],
  ): Option[(String, String)] =
    val suffix = "-testkit"
    Option
      .when(selected.artifact.endsWith(suffix)) {
        val parent = selected.artifact.dropRight(suffix.length)
        all
          .find(o => o.group == selected.group && o.artifact == parent)
          .map(o => (o.artifact, o.revision))
          .orElse(
            edges
              .find(e => e.organization == selected.group && FromGraph.artifactFamily(e.name) == parent)
              .map(e => (parent, e.revision))
          )
      }
      .flatten
  end familyCandidate
end DepCleanup
