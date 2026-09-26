package zipx.syntax

import zipx.core.*

import dotty.tools.dotc.ast.untpd.*
import dotty.tools.dotc.core.Constants
import dotty.tools.dotc.core.Contexts.*

/** Reads a root `build.sbt` diff statement by statement, for affected gating.
  *
  * sbt 2 records no usable source range for a setting inside `project.settings(...)` (a single line, offset from the
  * block, and none at all for a multi-line one), so this compares the source instead. Each top-level definition is
  * matched by name between the two commits:
  *
  *   - a changed project definition affects that project's modules, and every other project whose definition names it,
  *     directly or through a helper value such as `val svcAJvm = LocalProject("svcA")`;
  *   - a changed helper value or `def`, a changed bare setting or import (sbt 2 applies bare settings to every
  *     project), a definition added or removed, a changed aggregator, or a bare setting that names a changed project,
  *     affects every module.
  *
  * Comments and blank lines sit outside every statement, so editing them affects nothing.
  */
object BuildSbtDiff:

  val Path: String = "build.sbt"

  def reading(base: String, head: String, graph: ModuleGraph): BuildFileReading =
    BuildFileReading(Path, seeds(base, head, graph))

  private final case class Stat(text: String, refs: Set[String])
  private final case class Stats(named: Map[String, Stat], bare: List[Stat])

  private def seeds(base: String, head: String, graph: ModuleGraph): Option[Set[String]] =
    for
      b      <- parse(base)
      h      <- parse(head)
      result <- compare(b, h, graph)
    yield result

  private def compare(b: Stats, h: Stats, graph: ModuleGraph): Option[Set[String]] =
    def modulesOf(name: String): List[ModuleNode] =
      graph.nodes.filter(n => (n.id: String) == name || (n.matrixRoot: String) == name)
    val changed             = h.named.keySet.filter(k => b.named.get(k).map(_.text) != h.named.get(k).map(_.text))
    val (projects, helpers) = changed.partition(modulesOf(_).nonEmpty)
    if b.bare.map(_.text).sorted != h.bare.map(_.text).sorted then None
    else if b.named.keySet != h.named.keySet || helpers.nonEmpty then None
    else if projects.exists(p => modulesOf(p).exists(!_.ciRelevant)) then None
    else
      // A name now standing for a changed project: the project, its module ids, and helpers that name either.
      def reach(known: Set[String]): Set[String] =
        val next =
          known ++ h.named.collect { case (n, s) if modulesOf(n).isEmpty && s.refs.exists(known) => n }
        if next == known then known else reach(next)
      val names = reach(projects ++ projects.flatMap(modulesOf).map(n => n.id: String))
      if h.bare.exists(_.refs.exists(names)) then None
      else
        // An aggregator names what it aggregates without reading it, and runs nothing of its own.
        val readers = h.named.collect { case (n, s) if modulesOf(n).nonEmpty && s.refs.exists(names) => n }
        Some((projects ++ readers).flatMap(modulesOf).filter(_.ciRelevant).map(n => n.id: String))
    end if
  end compare

  /** The top-level statements of an sbt file, wrapped as an object body so bare settings parse. */
  private def parse(source: String): Option[Stats] =
    given Context = ScalaParse.freshContext()
    val wrapped   = s"object ZipxBuildSbt {\n$source\n}\n"
    ScalaParse.untyped(wrapped, Path).toOption.flatMap(bodyOf).map { stats =>
      def stat(t: Tree): Stat =
        Stat(if t.span.exists then wrapped.substring(t.span.start, t.span.end) else t.show, refsOf(t))
      val (named, bare) = stats.partitionMap {
        case vd: ValDef    => Left(vd.name.toString -> stat(vd))
        case dd: DefDef    => Left(dd.name.toString -> stat(dd))
        case md: ModuleDef => Left(md.name.toString -> stat(md))
        case td: TypeDef   => Left(td.name.toString -> stat(td))
        case other         => Right(stat(other))
      }
      // Overloads share a name, so they compare as one.
      val byName = named.groupMap(_._1)(_._2).view.mapValues { overloads =>
        Stat(overloads.map(_.text).mkString("\n"), overloads.flatMap(_.refs).toSet)
      }
      Stats(byName.toMap, bare)
    }
  end parse

  private def bodyOf(tree: Tree)(using Context): Option[List[Tree]] =
    tree match
      case PackageDef(_, stats) => stats.collectFirst { case ModuleDef(_, impl) => impl.body }
      case ModuleDef(_, impl)   => Some(impl.body)
      case _                    => None

  /** Every identifier and string literal a statement mentions, so `LocalProject("svcA")` counts as naming `svcA`. */
  private def refsOf(tree: Tree)(using Context): Set[String] =
    val collect = new UntypedTreeAccumulator[Set[String]]:
      def apply(acc: Set[String], t: Tree)(using Context): Set[String] =
        t match
          case Ident(name)                                => foldOver(acc + name.toString, t)
          case Literal(c) if c.tag == Constants.StringTag => acc + c.stringValue
          case _                                          => foldOver(acc, t)
    collect(Set.empty, tree)

end BuildSbtDiff
